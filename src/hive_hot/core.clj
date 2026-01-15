(ns hive-hot.core
  "Hot-reload registry built on clj-reload.

   Extends tonsky/clj-reload with:
   - Named component registry
   - Event-driven listeners (for hive-events integration)
   - Status tracking and introspection
   - Cascade control

   Design: Composition over reimplementation."
  (:require [clj-reload.core :as reload]))

;; =============================================================================
;; Registry
;; =============================================================================

(defonce ^:private registry
  "Registry of hot-reloadable components.

   Structure:
   {component-id {:ns symbol
                  :on-reload fn
                  :on-error fn
                  :status :idle|:reloading|:error
                  :last-reload instant}}"
  (atom {}))

(defonce ^:private listeners
  "Reload event listeners.

   Structure:
   {listener-id (fn [event] ...)}"
  (atom {}))

(defonce ^:private initialized?
  "Track if clj-reload has been initialized."
  (atom false))

;; =============================================================================
;; Initialization
;; =============================================================================

(defn init!
  "Initialize hive-hot with source directories.

   Options (passed to clj-reload/init):
   - :dirs      - Source directories (default: [\"src\"])
   - :no-reload - Namespaces to never reload
   - :no-unload - Namespaces to reload but not unload

   Example:
   ```clojure
   (init! {:dirs [\"src\" \"dev\"]
           :no-reload '#{user}})
   ```"
  ([] (init! {:dirs ["src"]}))
  ([opts]
   (reload/init opts)
   (reset! initialized? true)
   :initialized))

;; =============================================================================
;; Listener API
;; =============================================================================

(defn add-listener!
  "Add a reload event listener.

   Events:
   - {:type :reload-start}
   - {:type :reload-success :unloaded [...] :loaded [...] :ms elapsed}
   - {:type :reload-error :failed ns :error ex}
   - {:type :component-callback :component id :callback :on-reload|:on-error}"
  [listener-id listener-fn]
  (swap! listeners assoc listener-id listener-fn))

(defn remove-listener!
  "Remove a reload event listener."
  [listener-id]
  (swap! listeners dissoc listener-id))

(defn- notify! [event]
  (doseq [[_id listener-fn] @listeners]
    (try
      (listener-fn event)
      (catch Exception e
        (println "[hive-hot] Listener error:" (.getMessage e))))))

;; =============================================================================
;; Component API
;; =============================================================================

(defn reg-hot
  "Register a component for hot-reload callbacks.

   Options:
   - :ns        - Namespace symbol (required)
   - :on-reload - Callback after successful reload (fn [])
   - :on-error  - Callback on reload failure (fn [exception])

   Note: clj-reload handles dependency tracking automatically.
   Use this for application-level callbacks (restart server, etc).

   Example:
   ```clojure
   (reg-hot :http-server
     {:ns 'my.server
      :on-reload #(println \"Server code reloaded!\")})
   ```"
  [component-id {:keys [ns on-reload on-error] :as opts}]
  {:pre [(keyword? component-id) (symbol? ns)]}
  (swap! registry assoc component-id
         {:ns ns
          :on-reload (or on-reload (fn []))
          :on-error (or on-error (fn [_]))
          :status :idle
          :last-reload nil})
  component-id)

(defn unreg-hot
  "Unregister a component."
  [component-id]
  (swap! registry dissoc component-id)
  nil)

(defn get-component
  "Get component registration by ID."
  [component-id]
  (get @registry component-id))

(defn list-components
  "List all registered component IDs."
  []
  (keys @registry))

;; =============================================================================
;; Core Reload
;; =============================================================================

(defn- run-component-callbacks!
  "Run callbacks for components whose namespaces were reloaded."
  [result]
  (let [{:keys [loaded failed exception]} result
        loaded-set (set loaded)]
    (doseq [[id {:keys [ns on-reload on-error]}] @registry]
      (cond
        ;; Namespace failed to load
        (= ns failed)
        (do
          (swap! registry assoc-in [id :status] :error)
          (notify! {:type :component-callback :component id :callback :on-error})
          (when on-error (on-error exception)))

        ;; Namespace was reloaded successfully
        (contains? loaded-set ns)
        (do
          (swap! registry update id merge
                 {:status :idle
                  :last-reload (java.time.Instant/now)})
          (notify! {:type :component-callback :component id :callback :on-reload})
          (when on-reload (on-reload)))))))

(defn reload!
  "Reload changed namespaces and their dependents.

   Options (passed to clj-reload/reload):
   - :throw - Throw on error (default: false, returns result map)
   - :only  - :loaded | :all | #\"pattern\"

   Returns:
   {:success bool
    :unloaded [ns ...]
    :loaded [ns ...]
    :failed ns-or-nil
    :error exception-or-nil
    :ms elapsed}

   Example:
   ```clojure
   (reload!)                        ; Reload changed
   (reload! {:only :all})           ; Reload everything
   (reload! {:only #\".*-test\"})   ; Reload matching
   ```"
  ([] (reload! {}))
  ([opts]
   (when-not @initialized?
     (init!))
   (notify! {:type :reload-start :opts opts})
   (let [start (System/currentTimeMillis)
         result (reload/reload (merge {:throw false} opts))
         elapsed (- (System/currentTimeMillis) start)
         success? (nil? (:failed result))]

     ;; Run component callbacks
     (run-component-callbacks! result)

     ;; Notify listeners
     (if success?
       (notify! {:type :reload-success
                 :unloaded (:unloaded result)
                 :loaded (:loaded result)
                 :ms elapsed})
       (notify! {:type :reload-error
                 :failed (:failed result)
                 :error (:exception result)}))

     (merge result
            {:success success?
             :ms elapsed}))))

(defn reload-all!
  "Force reload of all namespaces.

   Use sparingly - prefer reload! for incremental reloads."
  []
  (reload! {:only :all}))

;; =============================================================================
;; Status & Introspection
;; =============================================================================

(defn status
  "Get current hot-reload status.

   Returns:
   {:initialized? bool
    :components {...}
    :listener-count n}"
  []
  {:initialized? @initialized?
   :components @registry
   :listener-count (count @listeners)})

(defn reset!
  "Reset all registrations. Use in tests."
  []
  (reset! registry {})
  (reset! listeners {})
  (reset! initialized? false)
  nil)

;; =============================================================================
;; Convenience
;; =============================================================================

(defmacro with-reload
  "Execute body, then reload.

   Useful for REPL development:
   ```clojure
   (with-reload
     (spit \"src/my/service.clj\" new-code))
   ```"
  [& body]
  `(do
     ~@body
     (reload!)))

(defn find-namespaces
  "Find namespaces matching a pattern.
   Delegates to clj-reload/find-namespaces."
  [pattern]
  (reload/find-namespaces pattern))
