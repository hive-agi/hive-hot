(ns hive-hot.watcher
  "FileWatcher protocol and WatchService-based implementation.
   
   Provides file system watching for hot-reload coordination.
   Uses Java WatchService for cross-platform file event detection."
  (:import [java.nio.file FileSystems Path Paths StandardWatchEventKinds WatchKey
            Files FileVisitResult SimpleFileVisitor]
           [java.nio.file.attribute BasicFileAttributes]
           [java.util.concurrent TimeUnit]))

;; =============================================================================
;; Protocol
;; =============================================================================

(defprotocol FileWatcher
  "Protocol for watching file system changes."
  (start! [this paths callback]
    "Start watching paths. Callback receives {:file path :type :modify|:create|:delete}")
  (stop! [this]
    "Stop watching and clean up resources.")
  (watching? [this]
    "Returns true if watcher is currently active."))

;; =============================================================================
;; WatchService Implementation
;; =============================================================================

(defn- event-kind->type
  "Convert Java WatchEvent kind to keyword."
  [kind]
  (condp = kind
    StandardWatchEventKinds/ENTRY_MODIFY :modify
    StandardWatchEventKinds/ENTRY_CREATE :create
    StandardWatchEventKinds/ENTRY_DELETE :delete
    :unknown))

(defn- path->Path
  "Convert string or Path to java.nio.file.Path."
  ^Path [p]
  (if (instance? Path p)
    p
    (Paths/get (str p) (into-array String []))))

(defn- register-path!
  "Register a single path with the watch service."
  [^java.nio.file.WatchService service path]
  (let [^Path p (path->Path path)]
    (.register p service
               (into-array [StandardWatchEventKinds/ENTRY_MODIFY
                            StandardWatchEventKinds/ENTRY_CREATE
                            StandardWatchEventKinds/ENTRY_DELETE]))))

(defn- register-recursive!
  "Register a path and all subdirectories with the watch service.

   Java WatchService only watches immediate directory contents, NOT subdirectories.
   This function walks the directory tree and registers each directory.

   CLARITY-Y: Gracefully handles permission errors and missing directories."
  [^java.nio.file.WatchService service path key->path-atom]
  (let [root-path (path->Path path)]
    (when (Files/isDirectory root-path (into-array java.nio.file.LinkOption []))
      (Files/walkFileTree
       root-path
       (proxy [SimpleFileVisitor] []
         (preVisitDirectory [^Path dir ^BasicFileAttributes _attrs]
           (try
             (let [key (register-path! service (str dir))]
               (swap! key->path-atom assoc key (str dir)))
             (catch Exception e
               (println "Could not register directory:" (str dir) "-" (.getMessage e))))
           FileVisitResult/CONTINUE)
         (visitFileFailed [^Path _file ^java.io.IOException _exc]
           ;; Skip files/dirs we can't access
           FileVisitResult/CONTINUE))))))

(defn- process-events!
  "Process events from a watch key, calling callback for each.

   `only` is {dir #{file-name}} for dirs registered solely because a single
   FILE in them was asked for (see `start!`): events for their other entries
   are dropped, so watching `deps.edn` does not report every sibling of it."
  ([key callback key->path] (process-events! key callback key->path {}))
  ([^WatchKey key callback key->path only]
   (let [dir-path (get key->path key)
         allowed  (get only dir-path)]
     (doseq [event (.pollEvents key)]
       (let [kind (.kind event)
             context (.context event)
             file-path (when (and dir-path context
                                  (or (nil? allowed) (contains? allowed (str context))))
                         (str (.resolve (path->Path dir-path) (str context))))]
         (when (and file-path (not= kind StandardWatchEventKinds/OVERFLOW))
           (try
             (callback {:file file-path
                        :type (event-kind->type kind)})
             (catch Exception e
               (println "Watcher callback error:" (.getMessage e)))))))
     (.reset key))))

(defn- watch-loop
  "Main watch loop - polls for events and dispatches to callback."
  [^java.nio.file.WatchService service state callback key->path]
  (while (:running? @state)
    (try
      (when-let [^WatchKey key (.poll service 100 TimeUnit/MILLISECONDS)]
        (process-events! key callback @key->path (:only @state {})))
      (catch java.nio.file.ClosedWatchServiceException _
        ;; Service closed, exit loop
        nil)
      (catch InterruptedException _
        ;; Thread interrupted, exit loop
        nil)
      (catch Exception e
        (println "Watch loop error:" (.getMessage e))))))

(def ^:private no-follow (into-array java.nio.file.LinkOption []))

(defn- register-paths!
  "Register `paths` with `service`. A directory is registered RECURSIVELY (Java
   WatchService only watches a dir's immediate entries). A regular FILE (e.g.
   deps.edn) registers its parent dir NON-recursively and records the file name
   under `[:only dir]` in `state`, so that dir reports only the named files
   rather than every sibling (see `process-events!`). A dir asked for in its own
   right drops any such filter: it must see every entry. Safe to call on a
   running watcher; an already-registered dir is not registered twice."
  [service state key->path paths]
  (doseq [path paths]
    (let [^Path p (.toAbsolutePath (path->Path path))
          known? (fn [d] (some #{d} (vals @key->path)))]
      (cond
        (Files/isRegularFile p no-follow)
        (when-let [parent (.getParent p)]
          (let [dir (str parent)]
            (when-not (known? dir)
              (try
                (swap! key->path assoc (register-path! service dir) dir)
                (catch Exception e
                  (println "Could not register directory:" dir "-" (.getMessage e)))))
            ;; A dir already watched in full (itself or an ancestor asked for
            ;; as a dir) keeps seeing every entry; otherwise filter to the file.
            (swap! state
                   (fn [s]
                     (if (some #(or (= % dir) (.startsWith ^String dir (str % "/"))) (:full s))
                       s
                       (update-in s [:only dir] (fnil conj #{}) (str (.getFileName p))))))))

        (Files/isDirectory p no-follow)
        (do (register-recursive! service (str p) key->path)
            (swap! state (fn [s]
                           (let [d (str p)
                                 covered (filter #(or (= % d) (.startsWith ^String % (str d "/")))
                                                 (keys (:only s)))]
                             (-> s
                                 (update :full (fnil conj #{}) d)
                                 (update :only #(apply dissoc (or % {}) covered)))))))

        :else nil))))

(defn add-paths!
  "Start watching `paths` (dirs or files) on an ALREADY RUNNING `watcher`
   without restarting it, so no event window is lost. Returns true when the
   watcher was running and the paths were registered, nil otherwise."
  [watcher paths]
  (let [{:keys [service state key->path]} watcher]
    (when (and state (:running? @state))
      (register-paths! service state key->path paths)
      true)))

(defrecord WatchServiceWatcher [service state key->path]
  FileWatcher

  (start! [_this paths callback]
    ;; Only start if not already running - use swap! with check
    (let [started? (atom false)]
      (swap! state
             (fn [s]
               (if (:running? s)
                 s ; Already running, no change
                 (do (reset! started? true)
                     {:running? true}))))
      (when @started?
        (register-paths! service state key->path paths)
        ;; Start watch thread
        (let [thread (Thread.
                      (fn [] (watch-loop service state callback key->path))
                      "hive-hot-watcher")]
          (.setDaemon thread true)
          (.start thread)
          (swap! state assoc :thread thread))
        true)))

  (stop! [_this]
    (when (:running? @state)
      (swap! state assoc :running? false)
      (when-let [^Thread thread (:thread @state)]
        (.interrupt thread))
      (try
        (.close ^java.nio.file.WatchService service)
        (catch Exception _))
      (reset! key->path {})
      true))

  (watching? [_this]
    (:running? @state false)))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-watcher
  "Create a new WatchServiceWatcher instance."
  []
  (->WatchServiceWatcher
   (.newWatchService (FileSystems/getDefault))
   (atom {:running? false})
   (atom {})))
