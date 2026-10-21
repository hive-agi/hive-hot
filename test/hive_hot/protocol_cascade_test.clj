(ns hive-hot.protocol-cascade-test
  "The protocol interlock of a scoped reload.

   Reloading a namespace that defines a protocol orphans every implementation
   built against the old protocol object - the 'No implementation of method
   ... found for class X' a live addon reload left behind for hive-agent's
   ILLMProvider. Fixtures:
     test/fixtures/proto      shape (defprotocol IShape), square (defrecord),
                              ext (extend-protocol), plugin (extend reached via
                              requiring-resolve - invisible to the require graph)
     test/fixtures/protopin   base (plain, edited)
     test/fixtures/protoport  port (defprotocol, depends on base, OUTSIDE the
                              reloaded root)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-hot.core :as hot]
            [hive-hot.protocols :as protocols]))

(def ^:private proto-root "test/fixtures/proto")
(def ^:private pin-root "test/fixtures/protopin")
(def ^:private port-root "test/fixtures/protoport")
(def ^:private shape-file (str proto-root "/shape.clj"))
(def ^:private base-file (str pin-root "/base.clj"))

(defn- shape-src [v]
  (str "(ns fixtures.proto.shape\n"
       "  \"Test fixture: a namespace that DEFINES a protocol. Rewritten by the\n"
       "   protocol-cascade tests to force a reload that redefines the protocol.\")\n\n"
       "(defprotocol IShape\n  (area [s] \"Area of the shape.\"))\n\n"
       "(def version " v ")\n"))

(defn- base-src [v]
  (str "(ns fixtures.protopin.base\n"
       "  \"Test fixture: a plain namespace in the RELOADED root of the protocol-pin\n"
       "   test. Edited by that test.\")\n\n"
       "(def value " v ")\n"))

(def ^:private fixture-nses
  '[fixtures.proto.plugin fixtures.proto.ext fixtures.proto.square fixtures.proto.shape
    fixtures.protoport.port fixtures.protopin.base hivehot.repl-impl])

(defn- write! [path content]
  (Thread/sleep 20)
  (spit path content)
  (Thread/sleep 20))

(defn- forget! [ns]
  (remove-ns ns)
  (dosync (alter @#'clojure.core/*loaded-libs* disj ns)))

(defn- load-fresh! [& nses]
  (run! forget! fixture-nses)
  (apply require nses))

(defn- restore! []
  (spit shape-file (shape-src 1))
  (spit base-file (base-src 1)))

(use-fixtures :each
  (fn [f]
    (hot/reset-all!)
    (restore!)
    (Thread/sleep 20)
    (try (f)
         (finally
           (hot/reset-all!)
           (restore!)
           (run! forget! fixture-nses)))))

(defn- v [sym] @(resolve sym))

(deftest reloading-a-protocol-reloads-every-implementor
  (hot/init! {:dirs [proto-root]})
  (load-fresh! 'fixtures.proto.shape 'fixtures.proto.square
               'fixtures.proto.ext 'fixtures.proto.plugin)
  ;; The live failure shape: an implementor the pass's own cascade misses
  ;; (absent from *loaded-libs*, though its namespace is live).
  (dosync (alter @#'clojure.core/*loaded-libs* disj 'fixtures.proto.square))
  (let [old-proto (v 'fixtures.proto.shape/IShape)]
    (write! shape-file (shape-src 2))
    (let [plan (hot/scope-plan [proto-root])]
      (testing "the plan names the definer and every implementor that must follow it"
        (is (= '#{fixtures.proto.square fixtures.proto.ext fixtures.proto.plugin}
               (get (:definers plan) 'fixtures.proto.shape)))
        (is (empty? (:pinned plan)))))
    (let [r      (hot/reload-scoped! [proto-root])
          loaded (vec (:loaded r))
          idx    #(.indexOf loaded %)]
      (is (:success r) (pr-str (dissoc r :exception)))
      (testing "the protocol was redefined"
        (is (= 2 (v 'fixtures.proto.shape/version)))
        (is (not (identical? old-proto (v 'fixtures.proto.shape/IShape)))))
      (testing "every implementor was reloaded AFTER the definer"
        (doseq [ns '[fixtures.proto.square fixtures.proto.ext fixtures.proto.plugin]]
          (is (< (idx 'fixtures.proto.shape) (idx ns)) (str ns " in " loaded))))
      (testing "the follow-up pass is reported"
        (is (seq (:protocol-cascade r)))
        (is (nil? (:protocol-stale r))))
      (testing "fresh instances satisfy the NEW protocol"
        (let [sq      ((resolve 'fixtures.proto.square/->Square) 3)
              area    (resolve 'fixtures.proto.shape/area)
              ishape  (v 'fixtures.proto.shape/IShape)]
          (is (satisfies? ishape sq))
          (is (= 9 (area sq)))
          (is (= 16 (area 4)) "extend-protocol re-applied")
          (is (= 3 (area "abc")) "graph-invisible extend re-applied"))))))

(deftest a-protocol-definer-outside-the-roots-is-pinned
  (hot/init! {:dirs [pin-root port-root]})
  (load-fresh! 'fixtures.protopin.base 'fixtures.protoport.port)
  (let [old-proto (v 'fixtures.protoport.port/IPort)]
    (write! base-file (base-src 2))
    (let [r (hot/reload-scoped! [pin-root])]
      (is (:success r) (pr-str (dissoc r :exception)))
      (is (= 2 (v 'fixtures.protopin.base/value)))
      (testing "the definer is not reloaded in place, and says why"
        (is (not (contains? (set (:loaded r)) 'fixtures.protoport.port)))
        (is (identical? old-proto (v 'fixtures.protoport.port/IPort)))
        (is (= [{:ns "fixtures.protoport.port" :reason :outside-roots :implementors []}]
               (:pinned-protocols r)))))))

(deftest a-protocol-with-an-untracked-implementor-is-pinned
  (hot/init! {:dirs [proto-root]})
  (load-fresh! 'fixtures.proto.shape 'fixtures.proto.square)
  ;; A REPL-made namespace extends the protocol: no reload can reach it.
  (binding [*ns* (create-ns 'hivehot.repl-impl)]
    (refer-clojure)
    (eval '(extend java.lang.Double
             @(resolve 'fixtures.proto.shape/IShape)
             {:area (fn [d] (* d d))})))
  (let [old-proto (v 'fixtures.proto.shape/IShape)]
    (write! shape-file (shape-src 2))
    (let [r (hot/reload-scoped! [proto-root])]
      (is (:success r) (pr-str (dissoc r :exception)))
      (is (identical? old-proto (v 'fixtures.proto.shape/IShape)))
      (testing "pinned, the definer's non-type forms are refreshed in place"
        (is (= 2 (v 'fixtures.proto.shape/version)))
        (is (= ["fixtures.proto.shape"] (mapv :ns (:pinned-refreshed r))))
        (is (= ["IShape"] (mapcat :types (:pinned-refreshed r)))))
      (is (= [{:ns "fixtures.proto.shape" :reason :untracked-implementors
               :implementors ["hivehot.repl-impl"]}]
             (:pinned-protocols r)))
      (is (= 4.0 ((resolve 'fixtures.proto.shape/area) 2.0))))))

(deftest stale-followers-names-implementors-missing-or-loaded-too-early
  (let [defs '{p #{a b c}}]
    (is (= {} (protocols/stale-followers defs '[p a b c])))
    (is (= '{p #{a c}} (protocols/stale-followers defs '[a p b])))
    (is (= {} (protocols/stale-followers defs '[a b])) "definer not loaded")))
