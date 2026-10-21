(ns hive-hot.plan
  "Portable reload planning: what a set of namespaces requires, the order
   that loads each after its requirements, the require cycles no order can
   place, and the dependents a change drags in.

   Pure data in, plans as data out. No file reading, no classpath, no
   clj-reload and no host interop live here, so the same namespace runs on
   the JVM (hive-hot.core, over clj-reload's parsed state) and on cljrs
   (dirge's embedded addon host, over ns forms it read itself). Readers and
   loaders are the adapters' business.

   Ubiquitous language:
     ns form  - an unevaluated `(ns name ...)` list, as `read-string` gives it
     source   - {:ns name :requires #{name}}, what an ns form declares
     graph    - {name {:requires #{name}}}, sources keyed by namespace
     plan     - {:order [source] :cycle [source]}, what load-order answers")

;; =============================================================================
;; Schemas (plain data; malli is the caller's choice, not a dependency)
;; =============================================================================

(def NsName
  "A namespace name."
  :symbol)

(def Source
  "What an ns form declares: its name and the namespaces it requires."
  [:map
   [:ns :symbol]
   [:requires [:set :symbol]]])

(def NsForm
  "An unevaluated ns form: `(ns name docstring? attr-map? clause*)`, a clause
   being `(:require spec*)`, `(:use spec*)` or any other list."
  [:cat [:= 'ns] :symbol [:* :any]])

(def LoadPlan
  "What load-order answers. :order loads each source after those it
   requires; :cycle holds the sources no order can place."
  [:map {:closed true}
   [:order [:vector Source]]
   [:cycle [:vector Source]]])

;; =============================================================================
;; Collect: ns form -> source
;; =============================================================================

(defn require-targets
  "The namespaces one :require or :use spec names: a symbol, a vector
   headed by one, or a prefix list."
  [spec]
  (cond
    (symbol? spec) [spec]
    (vector? spec) (let [lib (first spec)] (when (symbol? lib) [lib]))
    (seq? spec)    (let [prefix (first spec)]
                     (for [lib  (rest spec)
                           :let [lib (if (vector? lib) (first lib) lib)]
                           :when (symbol? lib)]
                       (symbol (str prefix "." lib))))
    :else          nil))

(defn ns-deps
  "What an ns form declares: {:ns name :requires #{name}}, :requires being
   the namespaces its :require and :use clauses name. nil for any other
   form."
  [form]
  (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
    {:ns       (second form)
     :requires (set (for [clause (drop 2 form)
                          :when  (and (seq? clause)
                                      (contains? #{:require :use} (first clause)))
                          spec   (rest clause)
                          lib    (require-targets spec)]
                      lib))}))

;; =============================================================================
;; Promote: sources -> plan
;; =============================================================================

(defn load-order
  "`sources` ({:ns name :requires #{name}}) ordered so each follows the
   sources it requires, ties in input order: {:order [source] :cycle
   [source]}. :cycle holds, in input order, the sources no order can place:
   those in a require cycle and those requiring one. A require of a name no
   source declares is outside the plan and never blocks."
  [sources]
  (let [known (set (map :ns sources))]
    (loop [order [] pending (vec sources)]
      (let [placed (set (map :ns order))
            ready? (fn [{:keys [ns requires]}]
                     (every? #(or (= % ns) (contains? placed %) (not (contains? known %)))
                             requires))
            ready  (filterv ready? pending)]
        (if (empty? ready)
          {:order order :cycle pending}
          (recur (into order ready) (vec (remove ready? pending))))))))

(defn graph->sources
  "A graph ({name {:requires #{name}}}, the shape clj-reload keeps under
   :namespaces) as sources, sorted by name so plans over it are
   deterministic."
  [graph]
  (->> graph
       (map (fn [[ns {:keys [requires]}]] {:ns ns :requires (set requires)}))
       (sort-by (comp str :ns))
       vec))

(defn dependees
  "The require graph of `sources` inverted: {name #{name that requires it}}.
   Every source has a key; a require of a name no source declares adds no
   edge."
  [sources]
  (let [known (set (map :ns sources))]
    (reduce (fn [m {:keys [ns requires]}]
              (reduce (fn [m to]
                        (if (contains? known to)
                          (update m to (fnil conj #{}) ns)
                          m))
                      (update m ns #(or % #{}))
                      requires))
            {}
            sources)))

(defn dependents-closure
  "Every namespace a change to `starts` reaches through `dependees`
   ({name #{dependent}}), the starts included: #{name}."
  [dependees starts]
  (loop [queue (vec starts) acc #{}]
    (if (empty? queue)
      acc
      (let [[n & more] queue]
        (if (contains? acc n)
          (recur (vec more) acc)
          (recur (into (vec more) (get dependees n)) (conj acc n)))))))
