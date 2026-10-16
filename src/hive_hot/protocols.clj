(ns hive-hot.protocols
  "The protocol interlock for a scoped reload, read off the LIVE image.

   Reloading a namespace that defines a protocol (defprotocol) or an interface
   (definterface) replaces the protocol object / interface class. Every
   implementation built against the OLD one is orphaned: a defrecord compiled
   against the old interface no longer implements the new one, and the
   protocol's fresh :impls map has forgotten every extend. The symptom reads as
   a defect in the implementor - \"No implementation of method: :m of protocol
   #'p/P found for class: x.Rec\" - for a class that plainly implements it.

   clj-reload already reloads the require-graph dependents of a changed
   namespace, but only the ones in *loaded-libs*, and only the ones the require
   graph can see. An implementor that extends a protocol it reached through
   requiring-resolve, a live namespace an earlier failed pass dropped from
   *loaded-libs*, or a dependent a failed pass left unloaded all escape it.
   This namespace names, BEFORE a pass, every namespace that has to be reloaded
   after each protocol definer the pass would reload, so the pass can be
   checked - and completed - against that list afterwards.

   It also decides which definers must NOT be reloaded in place: one outside
   the roots being reloaded (a co-tenant's or a pinned SPI port, which this
   reload has no business replacing), and one whose implementors include a
   namespace clj-reload does not track (a jar, a REPL-made namespace): those
   could never be reloaded, so replacing the protocol would orphan them for
   good. Such a definer is PINNED for the pass and reported.

   Pure reads of the image: no var is dereferenced except a protocol var,
   whose value is the protocol map itself."
  (:require [clojure.string :as str]))

(defn- own-class?
  "Is `c` a class a namespace named `ns-sym` defined (defrecord, deftype,
   definterface all name their class <munged-ns>.<Name>)?"
  [ns-sym ^Class c]
  (let [cn (.getName c)
        pfx (str (munge (name ns-sym)) ".")]
    (and (str/starts-with? cn pfx)
         (not (str/includes? (subs cn (count pfx)) ".")))))

(defn protocols
  "The protocol maps `ns-sym` defines, found through the `:protocol` metadata
   defprotocol stamps on each method var it interns. Empty when the namespace
   is not loaded."
  [ns-sym]
  (if-let [n (find-ns ns-sym)]
    (into []
          (comp (keep (comp :protocol meta))
                (filter var?)
                (filter #(identical? n (.ns ^clojure.lang.Var %)))
                (distinct)
                (map deref)
                (filter map?))
          (vals (ns-interns n)))
    []))

(defn interfaces
  "The interfaces `ns-sym` defined and imported (definterface / gen-interface),
   plus the backing interface of every protocol it defines."
  [ns-sym]
  (if-let [n (find-ns ns-sym)]
    (into (set (keep :on-interface (protocols ns-sym)))
          (filter #(and (class? %) (.isInterface ^Class %) (own-class? ns-sym %)))
          (vals (ns-imports n)))
    #{}))

(defn defines-protocol?
  "Does the loaded namespace `ns-sym` define a protocol or an interface?"
  [ns-sym]
  (boolean (seq (interfaces ns-sym))))

(defn- class-owner
  "The namespace whose code produced class name `cn`: everything before the
   first `$` (fn, eval and reify classes), demunged."
  [^String cn]
  (let [i (.indexOf cn "$")]
    (when (pos? i)
      (symbol (clojure.lang.Compiler/demunge (subs cn 0 i))))))

(defn implementors
  "Live namespaces, other than `ns-sym`, holding an implementation of a
   protocol or interface `ns-sym` defines:

     inline   - a class the namespace defined (defrecord / deftype) that
                implements one of the interfaces
     extended - the namespace whose fns sit in a protocol's :impls
                (extend, extend-type, extend-protocol)

   Namespaces under clojure.* are never counted."
  [ns-sym]
  (let [ifaces (interfaces ns-sym)
        protos (protocols ns-sym)
        inline (when (seq ifaces)
                 (for [n (all-ns)
                       :let [s (ns-name n)]
                       :when (not= s ns-sym)
                       c (vals (ns-imports n))
                       :when (and (class? c) (own-class? s c)
                                  (some #(and (not= % c) (.isAssignableFrom ^Class % ^Class c))
                                        ifaces))]
                   s))
        extended (for [p protos
                       [_ mmap] (:impls p)
                       :when (map? mmap)
                       f (vals mmap)
                       :when (fn? f)
                       :let [owner (class-owner (.getName (class f)))]
                       :when (and owner (find-ns owner))]
                   owner)]
    (into (sorted-set)
          (comp (remove #{ns-sym})
                (remove #(str/starts-with? (str %) "clojure.")))
          (concat inline extended))))

(defn analyze
  "The protocol interlock for one pass.

   `candidates` are the namespaces the pass may reload (its cascade). Each LIVE
   one that defines a protocol or interface is a definer, with the namespaces
   that must be reloaded after it: its implementors (see `implementors`) plus
   its live direct require-graph dependents (`deps`, clj-reload's dependees
   map {ns #{dependent}}), whose compiled call sites hold the old method vars.

   A definer is PINNED, with a reason, when
     :no-reload              the host already pins it (config :no-reload)
     :outside-roots          `in-roots?` says it lives outside the reloaded roots
     :untracked-implementors an implementor is not `tracked?`, so could never be
                             reloaded after it

   Returns {:definers {ns #{must-follow ...}}       - definers the pass reloads
            :pinned   {ns {:reason kw :implementors [ns ...]}}}"
  [{:keys [candidates deps tracked? in-roots? no-reload]
    :or {tracked? (constantly true) in-roots? (constantly true) no-reload #{}}}]
  (reduce
    (fn [acc d]
      (if-not (and (find-ns d) (defines-protocol? d))
        acc
        (let [impls   (implementors d)
              follow  (into impls (filter find-ns) (get deps d))
              foreign (into [] (remove tracked?) impls)]
          (cond
            (contains? (set no-reload) d)
            (assoc-in acc [:pinned d] {:reason :no-reload :implementors (vec impls)})

            (not (in-roots? d))
            (assoc-in acc [:pinned d] {:reason :outside-roots :implementors (vec impls)})

            (seq foreign)
            (assoc-in acc [:pinned d] {:reason :untracked-implementors
                                       :implementors (vec foreign)})

            :else
            (assoc-in acc [:definers d] (into (sorted-set) (filter tracked?) follow))))))
    {:definers {} :pinned {}}
    (sort candidates)))

(defn stale-followers
  "Given the definers an `analyze` call returned and the ordered `loaded`
   namespaces of a pass, every namespace that had to be reloaded AFTER a
   definer the pass loaded, but was not: never loaded, or loaded before it.
   Answers {definer #{ns ...}} with empty entries dropped."
  [definers loaded]
  (let [idx (into {} (map-indexed (fn [i ns] [ns i])) loaded)]
    (into {}
          (keep (fn [[d follow]]
                  (when-let [di (idx d)]
                    (let [stale (into (sorted-set)
                                      (filter #(let [i (idx %)] (or (nil? i) (< i di))))
                                      follow)]
                      (when (seq stale) [d stale])))))
          definers)))

(defn pinned-report
  "Wire-safe projection of the pinned definers."
  [pinned]
  (mapv (fn [[d {:keys [reason implementors]}]]
          {:ns (str d) :reason reason :implementors (mapv str implementors)})
        (sort-by key pinned)))
