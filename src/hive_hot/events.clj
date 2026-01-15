(ns hive-hot.events
  "Event emission for hive-hot integration with hive-events.

   Event schemas:
   - [:file/changed {:file \"src/foo.clj\" :type :modify|:create|:delete}]
   - [:hot/reload-start {}]
   - [:hot/reload-success {:loaded [ns...] :unloaded [ns...] :ms N}]
   - [:hot/reload-error {:failed ns :error \"message\"}]

   Events are dispatched via hive.events/dispatch for system-wide
   observability and coordination."
  (:require [hive.events :as ev]))

;; =============================================================================
;; Event Emission
;; =============================================================================

(defn emit!
  "Emit an event via hive-events dispatch.

   Automatically adds :timestamp to event data.

   Example:
   ```clojure
   (emit! [:hot/reload-start {}])
   ```"
  [[event-type data]]
  (ev/dispatch [event-type (assoc data :timestamp (System/currentTimeMillis))]))

(defn emit-file-changed!
  "Emit a :file/changed event.

   Args:
   - file: Path to the changed file (string)
   - change-type: One of :modify, :create, :delete

   Example:
   ```clojure
   (emit-file-changed! \"src/my/ns.clj\" :modify)
   ```"
  [file change-type]
  {:pre [(string? file) (#{:modify :create :delete} change-type)]}
  (emit! [:file/changed {:file file :type change-type}]))

(defn emit-reload-start!
  "Emit a :hot/reload-start event.

   Called at the beginning of a reload operation."
  []
  (emit! [:hot/reload-start {}]))

(defn emit-reload-success!
  "Emit a :hot/reload-success event.

   Args:
   - loaded: Sequence of namespace symbols that were loaded
   - unloaded: Sequence of namespace symbols that were unloaded
   - ms: Elapsed time in milliseconds

   Example:
   ```clojure
   (emit-reload-success! ['my.ns 'my.other] ['my.ns] 42)
   ```"
  [loaded unloaded ms]
  (emit! [:hot/reload-success {:loaded (vec loaded)
                               :unloaded (vec unloaded)
                               :ms ms}]))

(defn emit-reload-error!
  "Emit a :hot/reload-error event.

   Args:
   - failed-ns: The namespace symbol that failed to load
   - error: Error message or exception

   Example:
   ```clojure
   (emit-reload-error! 'my.broken.ns \"Syntax error on line 42\")
   ```"
  [failed-ns error]
  (emit! [:hot/reload-error {:failed failed-ns
                             :error (if (instance? Throwable error)
                                      (.getMessage ^Throwable error)
                                      (str error))}]))

;; =============================================================================
;; Claim Checker Factory
;; =============================================================================

(defn make-claim-checker
  "Create a claim-checker function that queries the logic module.

   The returned function takes no arguments and returns a set of
   currently claimed file paths.

   Args:
   - query-fn: A function that returns a sequence of claim maps
               with :file keys (e.g., hive-mcp's logic/get-all-claims)

   Example:
   ```clojure
   ;; In hive-mcp, inject the actual query function:
   (def claim-checker
     (make-claim-checker logic/get-all-claims))

   ;; The debouncer uses it like:
   (let [claimed-files (claim-checker)]
     (if (contains? claimed-files \"src/foo.clj\")
       :buffer
       :proceed))
   ```"
  [query-fn]
  (fn []
    (set (map :file (query-fn)))))
