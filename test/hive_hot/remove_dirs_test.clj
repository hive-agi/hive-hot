(ns hive-hot.remove-dirs-test
  "remove-dirs!: the inverse of extend-init!. A plugged-out root stops being
   tracked and watched, the change baseline survives, and the dirs the initial
   init declared are never released."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-hot.core :as hot]
            [hive-hot.dirs :as dirs]
            [hive-test.trifecta :as tri]))

(def ^:private root-a "test/fixtures/integration")
(def ^:private root-b "test/fixtures/scoped")
(def ^:private alpha-file (str root-a "/alpha.clj"))

(defn- alpha-src [v]
  (str "(ns fixtures.integration.alpha\n"
       "  \"Test fixture for integration tests. Will be modified during reload tests.\")\n\n"
       "(def value " v ")\n\n"
       "(defn get-value\n  \"Returns the current value.\"\n  []\n  value)\n"))

(defn- write! [path content]
  (Thread/sleep 20)
  (spit path content)
  (Thread/sleep 20))

(defn- reset-fixture [f]
  (hot/stop-watcher!)
  (hot/reset-all!)
  (spit alpha-file (alpha-src 1))
  (Thread/sleep 20)
  (try (f)
       (finally
         (hot/stop-watcher!)
         (hot/reset-all!)
         (spit alpha-file (alpha-src 1)))))

(use-fixtures :each reset-fixture)

(defn- tracked [] (set (:dirs (hot/status))))

;; =============================================================================
;; Boundary
;; =============================================================================

(deftest an-extended-root-is-removed-and-a-core-root-is-kept
  (hot/init! {:dirs [root-a]})
  (hot/extend-init! {:dirs [root-b]})
  (is (= #{root-a root-b} (tracked)))
  (let [r (hot/remove-dirs! {:dirs [root-b]})]
    (is (= [root-b] (:removed r)))
    (is (= [] (:kept r)))
    (is (= [root-a] (:dirs r)))
    (is (= #{root-a} (tracked))))
  (testing "idempotent: the second call removes nothing"
    (is (= {:removed [] :kept [] :absent [root-b] :dirs [root-a]}
           (hot/remove-dirs! {:dirs [root-b]}))))
  (testing "a core dir is reported :kept and stays tracked"
    (let [r (hot/remove-dirs! {:dirs [root-a]})]
      (is (= [] (:removed r)))
      (is (= [root-a] (:kept r)))
      (is (= #{root-a} (tracked))))))

(deftest dirs-are-compared-by-canonical-path-and-answered-as-spelled
  (hot/init! {:dirs [root-a]})
  (hot/extend-init! {:dirs [root-b]})
  (let [abs (.getAbsolutePath (java.io.File. ^String root-b))
        r   (hot/remove-dirs! {:dirs [abs]})]
    (is (= [abs] (:removed r)))
    (is (= #{root-a} (tracked)))))

(deftest removing-a-root-keeps-the-change-baseline
  (hot/init! {:dirs [root-a]})
  (hot/extend-init! {:dirs [root-b]})
  (require 'fixtures.integration.alpha :reload)
  (hot/reload!)
  (write! alpha-file (alpha-src 42))
  (hot/remove-dirs! {:dirs [root-b]})
  (testing "the change made before the removal is still pending, not taken as baseline"
    (is (= ["fixtures.integration.alpha"] (:pending (hot/status))))
    (let [r (hot/reload!)]
      (is (:success r) (pr-str r))
      (is (= 42 @(resolve 'fixtures.integration.alpha/value))))))

(deftest a-running-watcher-stops-watching-a-removed-root
  (hot/init! {:dirs [root-a]})
  (hot/init-with-watcher! {:dirs [root-a root-b]})
  (is (= #{root-a root-b} (set (hot/watching-paths))))
  (hot/remove-dirs! {:dirs [root-b]})
  (is (= [root-a] (hot/watching-paths)))
  (is (= #{root-a} (tracked))))

(deftest an-uninitialized-registry-adopts-its-dirs-as-core-and-removes-nothing
  (let [r (hot/remove-dirs! {:dirs [root-b]})]
    (is (= [] (:removed r)))
    (testing "the adopted dirs are core: none of them can be removed"
      (let [r2 (hot/remove-dirs! {:dirs (:dirs r)})]
        (is (= [] (:removed r2)))
        (is (= (count (distinct (:dirs r))) (count (:kept r2))))))))

;; =============================================================================
;; Pure dir-set arithmetic
;; =============================================================================

(tri/deftrifecta plan-removal-verdict
  hive-hot.dirs/plan-removal
  {:golden-path "test/golden/hive-hot/plan-removal.edn"
   :apply? true
   :cases {:extended   [["/core" "/x"] ["/x"] #{"/core"}]
           :core-kept  [["/core" "/x"] ["/core"] #{"/core"}]
           :absent     [["/core"] ["/y"] #{"/core"}]
           :mixed-dups [["/core" "/x" "/y"] ["/y" "/core" "/z" "/y"] #{"/core"}]
           :nothing    [["/core"] [] #{"/core"}]}
   :mutations
   [;; Releases the host's own source.
    ["ignores-core" (fn [cur req _] (dirs/plan-removal cur req #{}))]
    ;; Removes nothing: plug-out never stops watching.
    ["never-removes" (fn [cur req core]
                       (assoc (dirs/plan-removal cur req core) :removed [] :dirs (vec cur)))]
    ;; Reports a dir it was never tracking as removed.
    ["absent-removed" (fn [cur req core]
                        (let [p (dirs/plan-removal cur req core)]
                          (-> p (update :removed into (:absent p)) (assoc :absent []))))]]})

(def ^:private gen-dir (gen/elements ["/a" "/b" "/c" "/d" "/e"]))

(defspec plan-removal-partitions-the-request-and-never-touches-core 300
  (prop/for-all [current   (gen/fmap (comp vec distinct) (gen/vector gen-dir))
                 requested (gen/vector gen-dir)
                 core      (gen/set gen-dir)]
    (let [{:keys [dirs removed kept absent]} (dirs/plan-removal current requested core)
          answered (concat removed kept absent)
          again    (dirs/plan-removal dirs requested core)]
      (and
       ;; every requested dir is answered exactly once
       (= (count (distinct requested)) (count answered))
       (= (set requested) (set answered))
       ;; core is never removed; only tracked dirs are removed or kept
       (not-any? (set core) removed)
       (every? (set current) (concat removed kept))
       (not-any? (set current) absent)
       (= dirs (vec (remove (set removed) current)))
       ;; idempotent
       (empty? (:removed again))
       (= dirs (:dirs again))))))
