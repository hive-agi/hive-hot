(ns hive-hot.core
  "Hot-reload registry built on clj-reload.

   Extends tonsky/clj-reload with:
   - Named component registry
   - Event-driven listeners (for hive-events integration)
   - Status tracking and introspection
   - Cascade control
   - Watcher integration with coordinated debouncing

   Design: Composition over reimplementation."
  (:require [clj-reload.core :as reload]
            [hive-hot.events :as events]))

;; =============================================================================
;; Registry
;; =============================================================================

;; Registry of hot-reloadable components.
;;
;; Structure:
;; {component-id {:ns symbol
;;                :on-reload fn
;;                :on-error fn
;;                :status :idle|:reloading|:error
;;                :last-reload instant}}
(defonce ^:private registry (atom {}))

;; Reload event listeners.
;;
;; Structure:
;; {listener-id (fn [event] ...)}
(defonce ^:private listeners (atom {}))

;; Track if clj-reload has been initialized.
(defonce ^:private initialized? (atom false))

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

   Emits events via hive-events:
   - :hot/reload-start before reload
   - :hot/reload-success or :hot/reload-error after

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
   ;; Notify internal listeners
   (notify! {:type :reload-start :opts opts})
   ;; Emit to hive-events
   (events/emit-reload-start!)

   (let [start (System/currentTimeMillis)
         result (reload/reload (merge {:throw false} opts))
         elapsed (- (System/currentTimeMillis) start)
         success? (nil? (:failed result))]

     ;; Run component callbacks
     (run-component-callbacks! result)

     ;; Notify internal listeners and emit to hive-events
     (if success?
       (do
         (notify! {:type :reload-success
                   :unloaded (:unloaded result)
                   :loaded (:loaded result)
                   :ms elapsed})
         (events/emit-reload-success! (:loaded result)
                                      (:unloaded result)
                                      elapsed))
       (do
         (notify! {:type :reload-error
                   :failed (:failed result)
                   :error (:exception result)})
         (events/emit-reload-error! (:failed result)
                                    (:exception result))))

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

(defn reset-all!
  "Reset all registrations. Use in tests."
  []
  (clojure.core/reset! registry {})
  (clojure.core/reset! listeners {})
  (clojure.core/reset! initialized? false)
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

;; =============================================================================
;; Watcher Integration
;; =============================================================================

;; State for active watcher/debouncer
(defonce ^:private watcher-state (atom nil))

(defn init-with-watcher!
  "Initialize hive-hot with file watcher and coordinating debouncer.

   This wires together:
   - FileWatcher (from hive-hot.watcher)
   - CoordinatingDebouncer (from hive-hot.debounce)
   - reload! for actual reloading

   Options:
   - :dirs          - Source directories to watch (default: [\"src\"])
   - :claim-checker - Function returning set of claimed files
                      (default: (constantly #{}))
   - :debounce-ms   - Debounce window in ms (default: 100)
   - :no-reload     - Namespaces to never reload
   - :no-unload     - Namespaces to reload but not unload

   The claim-checker is typically created via:
   ```clojure
   (events/make-claim-checker logic/get-all-claims)
   ```

   When a file changes:
   1. FileWatcher detects change
   2. Debouncer checks claim-checker
      - If file is claimed: buffer until released
      - If unclaimed: apply debounce-ms window
   3. After debounce: emit :file/changed, call reload!

   Example:
   ```clojure
   ;; Basic usage (no coordination)
   (init-with-watcher! {:dirs [\"src\" \"dev\"]})

   ;; With claim-aware coordination
   (init-with-watcher!
     {:dirs [\"src\"]
      :claim-checker (events/make-claim-checker logic/get-all-claims)})
   ```

   Returns :watching on success."
  ([] (init-with-watcher! {}))
  ([{:keys [dirs claim-checker debounce-ms no-reload no-unload]
     :or {dirs ["src"]
          claim-checker (constantly #{})
          debounce-ms 100}}]
   ;; Initialize clj-reload
   (init! (cond-> {:dirs dirs}
            no-reload (assoc :no-reload no-reload)
            no-unload (assoc :no-unload no-unload)))

   ;; Require watcher and debouncer namespaces dynamically
   (require 'hive-hot.watcher)
   (require 'hive-hot.debounce)

   (let [create-watcher (resolve 'hive-hot.watcher/create-watcher)
         watcher-start! (resolve 'hive-hot.watcher/start!)
         watcher-stop! (resolve 'hive-hot.watcher/stop!)
         create-debouncer (resolve 'hive-hot.debounce/create-debouncer)
         handle-event! (resolve 'hive-hot.debounce/handle-event!)
         stop-debouncer! (resolve 'hive-hot.debounce/stop-debouncer!)]

     ;; Stop existing watcher if any
     (when-let [state @watcher-state]
       (when-let [stop (:stop-fn state)]
         (stop)))

     ;; Create debouncer that triggers reload on file changes
     ;; Callback receives a set of file paths
     (let [debouncer (create-debouncer
                      (fn [files]
                        ;; Emit file/changed events for each file
                        (doseq [file files]
                          (events/emit-file-changed! (str file) :modify))
                        ;; Trigger reload
                        (reload!))
                      claim-checker
                      :cooldown-ms debounce-ms)
           ;; Create watcher
           watcher (create-watcher)]

       ;; Start watcher with callback that feeds events to debouncer
       (watcher-start! watcher dirs
                       (fn [{:keys [file type]}]
                         (handle-event! debouncer {:file file :type type})))

       ;; Store state for cleanup (include dirs for introspection)
       (reset! watcher-state
               {:watcher watcher
                :debouncer debouncer
                :dirs dirs
                :stop-fn (fn []
                           (watcher-stop! watcher)
                           (stop-debouncer! debouncer)
                           (reset! watcher-state nil))})

       :watching))))

(defn stop-watcher!
  "Stop the file watcher if running."
  []
  (when-let [state @watcher-state]
    (when-let [stop (:stop-fn state)]
      (stop)
      :stopped)))

(defn watcher-status
  "Get watcher status.

   Returns nil if not watching, or map with:
   - :watching? true
   - :dirs watched directories"
  []
  (when-let [state @watcher-state]
    {:watching? true
     :dirs (:dirs state)}))

(defn watching-paths
  "Get list of directories being watched.
   Returns empty vector if not watching."
  []
  (or (:dirs @watcher-state) []))
