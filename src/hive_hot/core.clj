(ns hive-hot.core
  "Hot-reload registry built on clj-reload.

   Extends tonsky/clj-reload with:
   - Named component registry
   - Event-driven listeners (for hive-events integration)
   - Status tracking and introspection
   - Cascade control
   - Watcher integration with coordinated debouncing
   - Scoped reload: load the changes under a set of source roots, drag in
     their dependents, DECLINE every other change — with a per-file baseline
     so a declined change stays pending for the reload that owns it
     (clj-reload keeps one scalar :since for the whole image)

   Design: Composition over reimplementation."
  (:require [hive-hot.diagnostic :as diagnostic]
            [clj-reload.core :as reload]
            [clj-reload.parse :as parse]
            [clojure.java.io :as io]
            [hive-hot.events :as events])
  (:import [java.io File]))

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

;; Per-file baseline: {File -> mtime this registry last LOADED it at}.
;; clj-reload keeps one scalar :since for the whole image, so a change one
;; reload declined would vanish from the next reload's view; this map is what
;; keeps a declined change pending for the reload that owns its root.
(defonce ^:private seen (atom {}))

(defn- clj-var
  "A clj-reload var by name, private or not — find-var skips the compiler's
   privacy check. hive-hot pins clj-reload 1.0.0; throws when an internal moved."
  [sym]
  (or (find-var sym)
      (throw (ex-info (str "clj-reload internal not found: " sym) {:var sym}))))

(defn- reload-config
  "clj-reload's live config map, or nil before any init."
  []
  (let [v (clj-var 'clj-reload.core/*config*)]
    (when (bound? v) @v)))

(defn- state-atom [] @(clj-var 'clj-reload.core/*state))

(defn- reload-state [] @(state-atom))

(defn- canonical ^String [f] (.getCanonicalPath (io/file f)))

(defn- under-root?
  [^String root ^String path]
  (or (= root path) (.startsWith path (str root File/separator))))

(defn- tracked-files
  "Every source file clj-reload tracks: the files under its :dirs whose name
   matches its :files pattern — the same enumeration its own scan makes, keyed
   by the same relative File objects."
  []
  (let [{:keys [dirs files]} (reload-config)
        pattern (or files #".*\.cljc?")]
    (into []
          (comp (mapcat #(file-seq (io/file %)))
                (filter #(.isFile ^File %))
                (filter #(re-matches pattern (.getName ^File %))))
          dirs)))

(defn- file-namespaces
  "Namespaces `f` defines: from clj-reload's state when it knows the file, else
   by parsing it. An unparsable file defines nothing here — the reload itself
   reports it."
  [state ^File f]
  (or (get-in state [:files f :namespaces])
      (let [parsed (parse/read-file f)]
        (when (map? parsed) (set (keys parsed))))))

;; =============================================================================
;; Initialization
;; =============================================================================

(declare init!)

(defn- ensure-initialized!
  "Make a reload possible on a registry nobody initialized. clj-reload
   initializes itself at load with every classpath dir; when that config is
   present it is ADOPTED — resetting it to [\"src\"] would silently drop the
   dirs a REPL user expects to be watched. Only a bare clj-reload gets `init!`."
  []
  (when-not @initialized?
    (if (seq (:dirs (reload-config)))
      (do (reset! seen {})
          (reset! initialized? true))
      (init!))))

(defn init!
  "Initialize hive-hot with source directories.

   Options (passed to clj-reload/init):
   - :dirs      - Source directories (default: [\"src\"])
   - :no-reload - Namespaces to never reload
   - :no-unload - Namespaces to reload but not unload
   - :since     - Epoch ms the change baseline starts from (default: now). A
                  file modified after it counts as changed on the first reload;
                  pass the JVM start time so edits made before this init are
                  not silently taken as the baseline.

   Resets the per-file baseline. Use `ensure-init!` to extend an initialized
   registry without resetting it.

   Example:
   ```clojure
   (init! {:dirs [\"src\" \"dev\"]
           :no-reload '#{user}})
   ```"
  ([] (init! {:dirs ["src"]}))
  ([opts]
   (reload/init (dissoc opts :since))
   (when-let [since (:since opts)]
     (swap! (state-atom) assoc :since (long since)))
   (reset! seen {})
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

(defn scope-plan
  "What a scoped reload of `roots` WOULD do — no effects.

   Compares every tracked file's mtime against this registry's per-file
   baseline (falling back to clj-reload's :since), splits the changed files by
   root, and closes the wanted namespaces forward over the current require
   graph. `roots` nil or empty means every tracked dir.

   Returns
     {:roots     [canonical root ...]   nil when unscoped
      :want      [File ...]             changed under the roots — loaded
      :dragged   [File ...]             changed outside, but a dependent of a
                                        wanted namespace — loaded, since the
                                        cascade must recompile it anyway
      :skipped   [File ...]             changed outside, unrelated — DECLINED;
                                        stays pending for its own root
      :mask      #{ns ...}              namespaces pinned :no-reload for the run
      :since     long | nil             the :since window the run needs, nil
                                        when there is nothing to load
      :old-since long}"
  [roots]
  (let [state     (reload-state)
        old-since (:since state 0)
        roots*    (some->> (seq roots) (mapv canonical))
        mtime     (fn [^File f] (.lastModified f))
        all       (tracked-files)
        baseline  (fn [f] (get @seen f old-since))
        changed   (filterv #(> (mtime %) (baseline %)) all)
        in-roots? (if roots*
                    (fn [f] (let [p (canonical f)]
                              (boolean (some #(under-root? % p) roots*))))
                    (constantly true))
        {want true outside false} (group-by in-roots? changed)
        want      (vec want)
        nses-of   (fn [f] (or (file-namespaces state f) #{}))
        want-nses (into #{} (mapcat nses-of) want)
        deps      (parse/dependees (:namespaces state))
        closure   (set (parse/transitive-closure deps (vec want-nses)))
        {dragged true skipped false} (group-by (fn [f] (boolean (some closure (nses-of f))))
                                               outside)
        dragged   (vec dragged)
        admitted  (into (set want) dragged)
        window    (when (seq admitted)
                    (dec (transduce (map mtime) min Long/MAX_VALUE admitted)))
        exposed   (when window (filter #(> (mtime %) window) all))
        mask      (into #{}
                        (comp (remove admitted) (mapcat nses-of) (remove closure))
                        exposed)]
    {:roots roots* :want want :dragged dragged :skipped (vec skipped)
     :mask mask :since window :old-since old-since}))

(defn- run-clj-reload!
  "Drive one clj-reload pass for `plan`: lower :since to the plan's window and
   pin the plan's :mask as :no-reload for the duration, so the pass loads the
   admitted files (plus their dependents) and nothing else. Returns clj-reload's
   result map; a scan that throws — a wanted file that will not parse — is
   folded into {:failed sym :exception t} rather than escaping."
  [{:keys [mask since]} clj-opts]
  (let [cfg-var (clj-var 'clj-reload.core/*config*)
        cfg     (reload-config)]
    (when since
      (swap! (state-atom) update :since #(min (or % since) since)))
    (try
      (with-bindings {cfg-var (update cfg :no-reload (fnil into #{}) mask)}
        (reload/reload (merge {:throw false} clj-opts)))
      (catch Throwable t
        {:unloaded [] :loaded []
         :failed (or (:failed (ex-data t))
                     (some-> (:file (ex-data t)) str symbol)
                     'clj-reload.core/scan)
         :exception t}))))

(defn- record-loaded!
  "Advance the per-file baseline after a pass. A file whose namespace loaded is
   now seen at the mtime clj-reload read it at; so is a file whose namespaces
   the image has never required (there is no version to be stale about); every
   other tracked file that had no explicit baseline keeps `old-since`, so the
   :since the pass advanced cannot hide a change it declined."
  [old-since loaded]
  (let [state  (reload-state)
        loaded (set loaded)
        live   @@(clj-var 'clojure.core/*loaded-libs*)]
    (swap! seen
           (fn [m]
             (reduce-kv (fn [m f {:keys [namespaces modified]}]
                          (cond
                            (and modified (some loaded namespaces))  (assoc m f modified)
                            (and modified (not-any? live namespaces)) (assoc m f modified)
                            (contains? m f)                          m
                            :else                                    (assoc m f old-since)))
                        m
                        (:files state))))))

(defn- multi-file-namespaces
  "Loaded namespaces that clj-reload found in MORE THAN ONE file. Every file is
   loaded and the last one wins, so an edit to one can be shadowed by another —
   the silent \"reloaded, but the var did not change\" shape."
  [loaded]
  (let [state (reload-state)]
    (into {}
          (keep (fn [ns]
                  (let [files (get-in state [:namespaces ns :ns-files])]
                    (when (> (count files) 1)
                      [ns (mapv str files)]))))
          loaded)))

(defn- finish!
  "Common tail of every reload pass: component callbacks, listeners, events,
   and the result map hive-hot answers with."
  [result start]
  (let [elapsed  (- (System/currentTimeMillis) start)
        success? (nil? (:failed result))]
    (run-component-callbacks! result)
    (if success?
      (do (notify! {:type :reload-success
                    :unloaded (:unloaded result)
                    :loaded (:loaded result)
                    :ms elapsed})
          (events/emit-reload-success! (:loaded result) (:unloaded result) elapsed))
      (do (notify! {:type :reload-error
                    :failed (:failed result)
                    :error (:exception result)})
          (events/emit-reload-error! (:failed result) (:exception result))))
    (diagnostic/reload-outcome
      (cond-> (merge result {:success success? :ms elapsed})
      (:exception result) (assoc :error (ex-message (:exception result)))))))

(defn reload-scoped!
  "Reload the changes under `roots` — and only those.

   clj-reload holds ONE changed-set across every tracked dir, so a plain reload
   loads whatever any co-tenant has saved anywhere. This pass admits the
   changed files under `roots`, drags in the namespaces that depend on them
   (the cascade has to recompile those against the new vars, wherever they
   live), and DECLINES every other change: those files keep their baseline and
   stay pending for the reload that owns their root. `roots` nil or empty means
   every tracked dir.

   Returns clj-reload's result plus:
     :success    bool
     :ms         elapsed
     :scoped?    true when roots were given
     :roots      the canonical roots
     :skipped    [ns-string ...]  changed outside the roots, NOT loaded
     :dragged    [ns-string ...]  changed outside the roots, loaded as dependents
     :unchanged? true when nothing under the roots had changed — no pass ran
     :multi-file {ns [path ...]}  loaded namespaces found in more than one file"
  ([roots] (reload-scoped! roots {}))
  ([roots opts]
   (ensure-initialized!)
   (notify! {:type :reload-start :opts (assoc opts :roots roots)})
   (events/emit-reload-start!)
   (let [start   (System/currentTimeMillis)
         state   (reload-state)
         plan    (scope-plan roots)
         nses-of (fn [files]
                   (into [] (comp (mapcat #(file-namespaces state %)) (distinct) (map str))
                         files))
         skipped (nses-of (:skipped plan))
         dragged (nses-of (:dragged plan))
         result  (if (:since plan)
                   (run-clj-reload! plan (select-keys opts [:log-fn]))
                   {:unloaded [] :loaded []})
         _       (record-loaded! (:old-since plan) (:loaded result))
         multi   (multi-file-namespaces (:loaded result))
         out     (finish! result start)]
     (cond-> (assoc out
                    :scoped? (some? (:roots plan))
                    :roots (or (:roots plan) [])
                    :skipped skipped
                    :dragged dragged
                    :unchanged? (nil? (:since plan)))
       (seq multi) (assoc :multi-file multi)))))

(defn reload!
  "Reload changed namespaces and their dependents.

   Without :only this is `(reload-scoped! nil opts)`: every change the
   registry's per-file baseline has not seen, under every tracked dir — which
   includes the changes an earlier SCOPED reload declined.

   Options:
   - :only  - :loaded | :all | #\"pattern\" — clj-reload's explicit selection,
              passed straight through (bypasses the baseline)
   - :throw - Throw on error (default: false, returns result map)

   Emits events via hive-events:
   - :hot/reload-start before reload
   - :hot/reload-success or :hot/reload-error after

   Returns:
   {:success bool
    :unloaded [ns ...]
    :loaded [ns ...]
    :failed ns-or-nil
    :error message-or-nil
    :exception throwable-or-nil
    :ms elapsed}

   Example:
   ```clojure
   (reload!)                        ; Reload changed
   (reload! {:only :all})           ; Reload everything
   (reload! {:only #\".*-test\"})   ; Reload matching
   ```"
  ([] (reload! {}))
  ([opts]
   (if-not (contains? opts :only)
     (reload-scoped! nil opts)
     (do
       (ensure-initialized!)
       (notify! {:type :reload-start :opts opts})
       (events/emit-reload-start!)
       (let [start     (System/currentTimeMillis)
             old-since (:since (reload-state) 0)
             result    (reload/reload (merge {:throw false} opts))]
         (record-loaded! old-since (:loaded result))
         (finish! result start))))))

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
    :listener-count n
    :dirs [...]           tracked source dirs (when initialized)
    :since ms             clj-reload's change baseline (when initialized)
    :pending [ns ...]     namespaces changed on disk that no reload has loaded
                          yet (when initialized)}"
  []
  (let [init? @initialized?
        plan  (when init? (scope-plan nil))
        state (when init? (reload-state))]
    (cond-> {:initialized? init?
             :components @registry
             :listener-count (count @listeners)}
      init? (assoc :dirs (vec (:dirs (reload-config)))
                   :since (:old-since plan)
                   :pending (into []
                                  (comp (mapcat #(file-namespaces state %))
                                        (distinct)
                                        (map str))
                                  (:want plan))))))

(defn reset-all!
  "Reset all registrations and the per-file baseline. Use in tests."
  []
  (clojure.core/reset! registry {})
  (clojure.core/reset! listeners {})
  (clojure.core/reset! initialized? false)
  (clojure.core/reset! seen {})
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

(declare init-with-watcher!)

(defn extend-init!
  "Extend an initialized registry: union `dirs`, `no-reload` and `no-unload`
   into clj-reload's config WITHOUT resetting the change baseline or the
   per-file view — a change declined before this call is still pending after
   it. No-op when nothing is new. Restarts the file watcher, when one is
   running, over the union.

   Returns {:dirs [...] :added [...]}."
  [{:keys [dirs no-reload no-unload]}]
  (let [cfg        (reload-config)
        cur-dirs   (vec (:dirs cfg))
        added      (vec (remove (set cur-dirs) dirs))
        no-reload' (into (set (:no-reload cfg)) no-reload)
        no-unload' (into (set (:no-unload cfg)) no-unload)]
    (if (and (empty? added)
             (= no-reload' (set (:no-reload cfg)))
             (= no-unload' (set (:no-unload cfg))))
      {:dirs cur-dirs :added []}
      (let [since (:since (reload-state))
            dirs' (into cur-dirs added)]
        (reload/init {:dirs dirs' :no-reload no-reload' :no-unload no-unload'
                      :files (:files cfg) :reload-hook (:reload-hook cfg)
                      :unload-hook (:unload-hook cfg) :output (:output cfg)})
        (when since
          (swap! (state-atom) assoc :since since))
        (when-let [opts (:opts @watcher-state)]
          (init-with-watcher! (assoc opts :dirs dirs')))
        {:dirs dirs' :added added}))))

(defn ensure-init!
  "`init!` when not yet initialized, else `extend-init!` — the idempotent way
   for a host to declare the dirs it needs without resetting a baseline another
   caller established. Returns {:initialized? true :fresh? bool :dirs :added}."
  [opts]
  (if @initialized?
    (assoc (extend-init! opts) :initialized? true :fresh? false)
    (do (init! opts)
        {:initialized? true :fresh? true
         :dirs (vec (:dirs (reload-config))) :added (vec (:dirs opts))})))

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

   clj-reload is initialized through `ensure-init!`: an already-initialized
   registry is EXTENDED with the dirs, never reset, so a change made between
   init and watch is not silently taken as the baseline.

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
          debounce-ms 100}
     :as opts}]
   ;; Initialize clj-reload — extending, never resetting, an initialized registry
   (ensure-init! (cond-> {:dirs dirs}
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

       ;; Store state for cleanup (dirs for introspection, opts so extend-init!
       ;; can restart the watcher over a wider dir set)
       (reset! watcher-state
               {:watcher watcher
                :debouncer debouncer
                :dirs dirs
                :opts (assoc opts :dirs dirs)
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
