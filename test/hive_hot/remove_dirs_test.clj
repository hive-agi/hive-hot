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
            [hive-hot.schema :as hs]
            [hive-test.trifecta :as tri]
            [malli.core :as m]))

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

(defn- report? [r] (m/validate hs/RemoveDirsReport r))

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
    (is (= {:removed [] :kept [] :absent [root-b] :dirs [root-a] :shared {}}
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
;; Ownership: never unload what another owner still claims
;; =============================================================================

(deftest a-dir-another-owner-claims-stays-tracked
  (hot/init! {:dirs [root-a]})
  (hot/extend-init! {:dirs [root-b] :owner :addon/one})
  (hot/extend-init! {:dirs [root-b] :owner :addon/two})
  (let [r (hot/remove-dirs! {:dirs [root-b] :owner :addon/one})]
    (is (report? r) (pr-str r))
    (is (= [] (:removed r)))
    (is (= [root-b] (:kept r)))
    (is (= {root-b [:addon/two]} (:shared r)))
    (is (= #{root-a root-b} (tracked))))
  (testing "releasing again as the same owner changes nothing"
    (is (= [root-b] (:kept (hot/remove-dirs! {:dirs [root-b] :owner :addon/one})))))
  (testing "the last owner's release removes it"
    (let [r (hot/remove-dirs! {:dirs [root-b] :owner :addon/two})]
      (is (= [root-b] (:removed r)))
      (is (= {} (:shared r)))
      (is (= #{root-a} (tracked))))))

(deftest every-report-conforms-to-the-declared-contract
  (hot/init! {:dirs [root-a]})
  (hot/extend-init! {:dirs [root-b]})
  (doseq [req [[root-b] [root-b] [root-a] ["no/such/dir"] []]]
    (let [r (hot/remove-dirs! {:dirs req})]
      (is (report? r) (pr-str r)))))

(deftest extend-init-report-conforms
  (hot/init! {:dirs [root-a]})
  (is (m/validate hs/ExtendInitReport (hot/extend-init! {:dirs [root-b]}))))

;; =============================================================================
;; Round trip: extend-init! then remove-dirs! restores the tracked set
;; =============================================================================

(defonce ^:private tmp-roots
  (let [base (.toFile (java.nio.file.Files/createTempDirectory
                       "hive-hot-roots" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit base)
    (mapv (fn [i] (let [d (java.io.File. base (str "r" i))]
                    (.mkdirs d) (.deleteOnExit d) (.getPath d)))
          (range 5))))

(def ^:private gen-root (gen/elements tmp-roots))
(def ^:private gen-owner (gen/elements [nil :addon/one :addon/two]))

(defspec extend-then-remove-restores-the-core-set 40
  (prop/for-all [core  (gen/fmap (comp vec distinct) (gen/not-empty (gen/vector gen-root)))
                 added (gen/vector gen-root)
                 owner gen-owner]
    (hot/reset-all!)
    (hot/init! {:dirs core})
    (hot/extend-init! {:dirs added :owner owner})
    (let [r  (hot/remove-dirs! {:dirs added :owner owner})
          r2 (hot/remove-dirs! {:dirs added :owner owner})
          core-set (set core)]
      (and (report? r) (report? r2)
           (= core-set (tracked))
           (= (set (remove core-set added)) (set (:removed r)))
           (every? core-set (:kept r))
           (empty? (:removed r2))
           (= (:dirs r) (:dirs r2))))))

(defspec another-owners-claim-survives-a-release 40
  (prop/for-all [core  (gen/fmap (comp vec distinct) (gen/not-empty (gen/vector gen-root)))
                 mine  (gen/vector gen-root)
                 yours (gen/vector gen-root)]
    (hot/reset-all!)
    (hot/init! {:dirs core})
    (hot/extend-init! {:dirs mine :owner :addon/one})
    (hot/extend-init! {:dirs yours :owner :addon/two})
    (let [r (hot/remove-dirs! {:dirs mine :owner :addon/one})]
      (and (report? r)
           (= (into (set core) yours) (tracked))
           (not-any? (set yours) (:removed r))
           (every? (fn [[_ os]] (= [:addon/two] os)) (:shared r))))))

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

(def ^:private gen-claims
  (gen/map gen-dir (gen/set (gen/elements [:o1 :o2 dirs/anonymous]) {:min-elements 1})))

(defspec plan-removal-never-drops-a-dir-another-owner-claims 300
  (prop/for-all [current   (gen/fmap (comp vec distinct) (gen/vector gen-dir))
                 requested (gen/vector gen-dir)
                 core      (gen/set gen-dir)
                 claims    gen-claims
                 owner     (gen/elements [nil :o1 :o2])]
    (let [o (dirs/owner-of owner)
          {:keys [removed kept absent shared] :as p}
          (dirs/plan-removal current requested core claims owner)
          others (fn [d] (disj (get claims d #{}) o))
          again  (dirs/plan-removal (:dirs p) requested core (:claims p) owner)]
      (and
       (= (set requested) (set (concat removed kept absent)))
       (= (count (distinct requested)) (count (concat removed kept absent)))
       (every? #(empty? (others %)) removed)
       (not-any? (set core) removed)
       (every? #(or (core %) (seq (others %))) kept)
       (every? (fn [[d os]] (= (set os) (others d))) shared)
       ;; the releasing owner holds no claim on a requested tracked dir after
       (not-any? #(contains? (get (:claims p) % #{}) o) (filter (set current) requested))
       ;; removed dirs leave the claim map
       (not-any? #(contains? (:claims p) %) removed)
       (empty? (:removed again))))))

(defspec plan-addition-adds-only-new-roots-once-in-request-order 300
  (prop/for-all [current   (gen/fmap (comp vec distinct) (gen/vector gen-dir))
                 requested (gen/vector gen-dir)]
    (let [pairs (map (juxt identity identity) requested)
          added (dirs/plan-addition current pairs)]
      (and (= added (vec (distinct (remove (set current) requested))))
           (empty? (dirs/plan-addition (into current added) pairs))))))

(defspec claims-then-releases-in-any-order-restore-core-and-empty-claims 200
  (prop/for-all [core   (gen/fmap (comp vec distinct) (gen/vector gen-dir))
                 grants (gen/vector (gen/tuple (gen/elements [nil :o1 :o2])
                                               (gen/vector gen-dir))
                                    0 6)
                 seed   gen/nat]
    (let [step-add (fn [[cur claims] [owner ds]]
                     [(into cur (dirs/plan-addition cur (map (juxt identity identity) ds)))
                      (dirs/claim claims owner ds)])
          [cur claims] (reduce step-add [core {}] grants)
          releases (->> grants (sort-by (fn [g] (hash [seed g]))))
          [cur' claims']
          (reduce (fn [[cur claims] [owner ds]]
                    (let [p (dirs/plan-removal cur ds (set core) claims owner)]
                      ;; never drop a dir some other owner still holds
                      (assert (not-any? (fn [d] (seq (disj (get claims d #{})
                                                           (dirs/owner-of owner))))
                                        (:removed p)))
                      [(:dirs p) (:claims p)]))
                  [cur claims] releases)]
      (and (= core cur')
           (empty? claims')))))

(deftest extend-init-compares-by-canonical-path
  (hot/init! {:dirs [root-a]})
  (let [r (hot/extend-init! {:dirs [(str "./" root-a) root-b (str root-b "/")]})]
    (is (= [root-b] (:added r)))
    (is (= [root-a root-b] (:dirs r)))))
