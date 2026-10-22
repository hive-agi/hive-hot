(ns hive-hot.pinned
  "Bring a pinned namespace up to date with its source, in place.

   A pinned namespace (config :no-reload, or a protocol definer the interlock
   pins) is never unloaded, so a pass leaves it behind its changed file and
   every dependent that reads a var it gained fails to load. `refresh!`
   evaluates the file's top-level forms into the LIVE namespace instead,
   except the forms that would replace a type or reset state:

     :type   defprotocol definterface defrecord deftype gen-interface whose
             protocol var or class already exists: skipped, and compared with
             the live definition (:drift when they differ)
     :state  a def whose live value holds state (a reference, a delay, a
             resource): skipped
     :eval   everything else, the ns form included

   The namespace object, its protocols, classes and stateful vars keep their
   identity; fns, multimethod methods, extends and new vars take the source's
   value."
  (:require [clojure.java.io :as io])
  (:import [clojure.lang IDeref LineNumberingPushbackReader]
           [java.util.concurrent ExecutorService]
[java.lang AutoCloseable]))

(def type-heads
  "Heads of the forms that define a protocol, an interface or a class."
  #{"defprotocol" "definterface" "defrecord" "deftype" "gen-interface"})

(defn- head [form]
  (when (and (seq? form) (symbol? (first form)))
    (name (first form))))

(defn- defined-sym [form]
  (let [s (second form)] (when (symbol? s) s)))

(defn stateful?
  "Does `v` hold state that re-evaluating its def would replace?"
  [v]
  (or (instance? IDeref v)
      (instance? AutoCloseable v)
      (instance? ExecutorService v)
      (instance? Thread v)))

(defn- form-sigs
  "{:method #{arity ...}} of a defprotocol form."
  [form]
  (into (sorted-map)
        (keep (fn [f]
                (when (and (seq? f) (symbol? (first f)))
                  [(keyword (name (first f))) (into (sorted-set) (map count) (filter vector? (rest f)))])))
        (drop 2 form)))

(defn- live-sigs [p]
  (into (sorted-map)
        (map (fn [[k {:keys [arglists]}]] [k (into (sorted-set) (map count) arglists)]))
        (:sigs p)))

(defn- live-fields [^Class c]
  (try (mapv name (.invoke (.getMethod c "getBasis" (make-array Class 0)) nil (object-array 0)))
       (catch Throwable _not-a-record nil)))

(defn- drift
  "How the type `form` defines differs from the `live` one, or nil."
  [h form live]
  (case h
    "defprotocol" (when (and (var? live) (map? @live))
                    (let [was (live-sigs @live) now (form-sigs form)]
                      (when (not= was now) {:kind :protocol :was was :now now})))
    ("defrecord" "deftype") (when (class? live)
                              (let [was (live-fields live)
                                    now (let [fs (nth form 2 nil)] (when (vector? fs) (mapv name fs)))]
                                (when (and was now (not= was now)) {:kind :fields :was was :now now})))
    nil))

(defn form-action
  "What `refresh!` does with one top-level `form` read in `ns-sym`:
   {:action :eval | :type | :state, :name sym-or-nil, :drift map-or-nil}."
  [ns-sym form]
  (let [h    (head form)
        sym  (when h (defined-sym form))
        live (when sym (try (ns-resolve ns-sym sym) (catch Throwable _unresolvable nil)))]
    (cond
      (and (type-heads h) (or live (nil? sym)))
      {:action :type :name sym :drift (drift h form live)}

      (and (= "def" h) (var? live)
           (= (the-ns ns-sym) (.ns ^clojure.lang.Var live))
           (bound? live) (stateful? @live))
      {:action :state :name sym}

      :else
      {:action :eval :name sym})))

(defn- root-message [^Throwable t]
  (let [c (last (take-while some? (iterate #(.getCause ^Throwable %) t)))]
    (or (.getMessage ^Throwable c) (.getName (class c)))))

(defn- empty-report [ns-sym file]
  {:ns ns-sym :file (str file) :evaluated 0 :new [] :types [] :kept [] :drift [] :errors []})

(defn refresh!
  "Evaluate `file`'s top-level forms into the loaded namespace `ns-sym`,
   skipping :type and :state forms (see the ns doc). Never throws. Returns
     {:ns sym :file str :evaluated n :new [sym ...] :types [sym ...]
      :kept [sym ...] :drift [{:name :kind :was :now} ...]
      :errors [{:name :line :message} ...]}"
  [ns-sym file]
  (let [report (empty-report ns-sym file)]
    (if-not (find-ns ns-sym)
      (update report :errors conj {:name nil :line nil :message "namespace is not loaded"})
      (let [before (set (keys (ns-interns ns-sym)))
            out    (try
                     (with-open [rdr (LineNumberingPushbackReader. (io/reader (io/file file)))]
                       (binding [*ns* (the-ns ns-sym)
                                 *file* (str file)
                                 *warn-on-reflection* *warn-on-reflection*
                                 *unchecked-math* *unchecked-math*]
                         (loop [acc report]
                           (let [form (read {:eof ::eof :read-cond :allow :features #{:clj}} rdr)]
                             (if (= ::eof form)
                               acc
                               (let [{:keys [action name drift]} (form-action ns-sym form)]
                                 (recur
                                   (case action
                                     :type  (cond-> (update acc :types conj name)
                                              drift (update :drift conj (assoc drift :name name)))
                                     :state (update acc :kept conj name)
                                     :eval  (try (eval form)
                                                 (update acc :evaluated inc)
                                                 (catch Throwable t
                                                   (update acc :errors conj
                                                           {:name name :line (:line (meta form))
                                                            :message (root-message t)})))))))))))
                     (catch Throwable t
                       (update report :errors conj {:name nil :line nil :message (root-message t)})))]
        (assoc out :new (vec (sort (remove before (keys (ns-interns ns-sym))))))))))

(defn refresh-all!
  "`refresh!` every [ns file] of `targets`, in order. Returns the reports."
  [targets]
  (mapv (fn [[ns-sym file]] (refresh! ns-sym file)) targets))

(defn targets
  "{ns file} for every loaded namespace of a file a pass admits (`plan`'s
   :want and :dragged) that the pass will not reload: in `no-reload` or one
   of `plan`'s :pinned protocol definers. `nses-of` maps a file to the
   namespaces it defines. `user` is never a target."
  [{:keys [want dragged pinned]} no-reload nses-of]
  (let [pinned? (-> (set no-reload) (into (keys pinned)) (disj 'user))]
    (into (sorted-map)
          (for [f  (concat want dragged)
                ns (nses-of f)
                :when (and (pinned? ns) (find-ns ns))]
            [ns f]))))

(defn around!
  "Refresh `targets` before calling `pass!`, then refresh again after it the
   ones that failed (a form may read a var the pass itself loads).
   Returns [pass-result reports], one final report per target."
  [targets pass!]
  (let [before (refresh-all! targets)
        result (pass!)
        retry  (into (sorted-map)
                     (keep (fn [{:keys [ns errors]}] (when (seq errors) [ns (get targets ns)])))
                     before)
        by-ns  (fn [reports] (into {} (map (juxt :ns identity)) reports))
        final  (merge (by-ns before) (by-ns (when (seq retry) (refresh-all! retry))))]
    [result (mapv final (keys targets))]))

(defn wire-report
  "Printable projection of `refresh!` reports."
  [reports]
  (mapv (fn [r]
          (-> r
              (update :ns str)
              (update :new #(mapv str %))
              (update :types #(mapv str %))
              (update :kept #(mapv str %))
              (update :drift #(mapv (fn [d] (update d :name str)) %))
              (update :errors #(mapv (fn [e] (update e :name (fn [n] (some-> n str)))) %))))
        reports))
