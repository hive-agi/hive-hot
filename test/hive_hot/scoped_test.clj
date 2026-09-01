(ns hive-hot.scoped-test
  "The scoped reload: load the changes under a root, drag in their dependents,
   decline everything else — and keep what was declined PENDING.

   Two fixture roots stand in for two co-tenants' repos sharing one image:
     test/fixtures/integration   alpha (edited), beta (depends on alpha)
     test/fixtures/scoped        gamma (edited, unrelated), delta (depends on alpha)
   plus test/fixtures/shadow, a second file declaring gamma's namespace."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-hot.core :as hot]))

(def ^:private root-a "test/fixtures/integration")
(def ^:private root-b "test/fixtures/scoped")
(def ^:private root-c "test/fixtures/shadow")
(def ^:private alpha-file (str root-a "/alpha.clj"))
(def ^:private gamma-file (str root-b "/gamma.clj"))
(def ^:private delta-file (str root-b "/delta.clj"))

(defn- alpha-src [v]
  (str "(ns fixtures.integration.alpha\n"
       "  \"Test fixture for integration tests. Will be modified during reload tests.\")\n\n"
       "(def value " v ")\n\n"
       "(defn get-value\n  \"Returns the current value.\"\n  []\n  value)\n"))

(defn- gamma-src [v]
  (str "(ns fixtures.scoped.gamma\n"
       "  \"Test fixture in a SECOND source root. Edited by the scoped-reload tests.\")\n\n"
       "(def value " v ")\n"))

(defn- delta-src [tag]
  (str "(ns fixtures.scoped.delta\n"
       "  \"Test fixture in the second root that DEPENDS on the first root's alpha.\"\n"
       "  (:require [fixtures.integration.alpha :as alpha]))\n\n"
       "(def tag " tag ")\n\n"
       "(defn tripled\n  \"Returns alpha's value tripled.\"\n  []\n  (* 3 (alpha/get-value)))\n"))

(defn- write!
  "Write `content` to `path` with the mtime strictly after the baseline.
   mtimes are millisecond-grained; a write inside the same ms as init would be
   invisible to a > comparison, so let the clock tick on both sides."
  [path content]
  (Thread/sleep 20)
  (spit path content)
  (Thread/sleep 20))

(defn- restore! []
  (spit alpha-file (alpha-src 1))
  (spit gamma-file (gamma-src 1))
  (spit delta-file (delta-src :original)))

(defn- reload-fixtures!
  "Load the four fixture namespaces from fresh Namespace objects.

   A test that tracks only ONE root leaves a dependent in the other root
   holding an alias to a namespace object clj-reload has since removed and
   recreated; `require :reload` on that dependent then throws 'Alias already
   exists'. Removing the namespaces first makes every test start clean."
  []
  (doseq [ns '[fixtures.scoped.delta fixtures.scoped.gamma
               fixtures.integration.beta fixtures.integration.alpha]]
    (remove-ns ns)
    (dosync (alter @#'clojure.core/*loaded-libs* disj ns)))
  (require 'fixtures.integration.alpha
           'fixtures.integration.beta
           'fixtures.scoped.gamma
           'fixtures.scoped.delta))

(defn- current [sym] @(resolve sym))

(defn reset-fixture [f]
  (hot/reset-all!)
  (restore!)
  (Thread/sleep 20)
  (f)
  (hot/reset-all!)
  (restore!))

(use-fixtures :each reset-fixture)

(deftest a-scoped-reload-loads-its-root-and-declines-the-other
  (hot/init! {:dirs [root-a root-b]})
  (reload-fixtures!)
  (write! alpha-file (alpha-src 42))
  (write! gamma-file (gamma-src 99))
  (let [r (hot/reload-scoped! [root-a])]
    (is (:success r) (pr-str r))
    (is (true? (:scoped? r)))
    (is (false? (:unchanged? r)))
    (testing "alpha and its in-image dependent beta are loaded"
      (is (contains? (set (:loaded r)) 'fixtures.integration.alpha))
      (is (contains? (set (:loaded r)) 'fixtures.integration.beta))
      (is (= 42 (current 'fixtures.integration.alpha/value)))
      (is (= 84 ((resolve 'fixtures.integration.beta/doubled)))))
    (testing "gamma is DECLINED, named, and its var is provably not rebound"
      (is (= ["fixtures.scoped.gamma"] (:skipped r)))
      (is (not (contains? (set (:loaded r)) 'fixtures.scoped.gamma)))
      (is (= 1 (current 'fixtures.scoped.gamma/value))))
    (testing "an unchanged dependent outside the roots is not reported as dragged"
      (is (empty? (:dragged r))))))

(deftest a-declined-change-stays-pending-for-its-own-root
  (hot/init! {:dirs [root-a root-b]})
  (reload-fixtures!)
  (write! alpha-file (alpha-src 42))
  (write! gamma-file (gamma-src 99))
  (hot/reload-scoped! [root-a])
  (is (= 1 (current 'fixtures.scoped.gamma/value)))
  (testing "status names it as pending"
    (is (= ["fixtures.scoped.gamma"] (:pending (hot/status)))))
  (let [r (hot/reload-scoped! [root-b])]
    (is (:success r) (pr-str r))
    (is (contains? (set (:loaded r)) 'fixtures.scoped.gamma))
    (is (= 99 (current 'fixtures.scoped.gamma/value)))
    (is (empty? (:skipped r)))
    (testing "alpha, already current, is not reloaded again"
      (is (not (contains? (set (:loaded r)) 'fixtures.integration.alpha))))
    (is (empty? (:pending (hot/status))))))

(deftest a-plain-reload-admits-what-a-scoped-one-declined
  (testing "clj-reload's own :since has moved past the declined file; the
            per-file baseline is what still sees it"
    (hot/init! {:dirs [root-a root-b]})
    (reload-fixtures!)
    (write! alpha-file (alpha-src 42))
    (write! gamma-file (gamma-src 99))
    (hot/reload-scoped! [root-a])
    (is (= 1 (current 'fixtures.scoped.gamma/value)))
    (let [r (hot/reload!)]
      (is (:success r) (pr-str r))
      (is (false? (:scoped? r)))
      (is (contains? (set (:loaded r)) 'fixtures.scoped.gamma))
      (is (= 99 (current 'fixtures.scoped.gamma/value))))))

(deftest a-changed-dependent-outside-the-roots-is-dragged-and-named
  (hot/init! {:dirs [root-a root-b]})
  (reload-fixtures!)
  (write! alpha-file (alpha-src 42))
  (write! delta-file (delta-src :edited))
  (let [r (hot/reload-scoped! [root-a])]
    (is (:success r) (pr-str r))
    (testing "delta depends on alpha: the cascade must recompile it, so its
              own change rides along — and is NAMED, not silently included"
      (is (= ["fixtures.scoped.delta"] (:dragged r)))
      (is (contains? (set (:loaded r)) 'fixtures.scoped.delta))
      (is (= :edited (current 'fixtures.scoped.delta/tag)))
      (is (= 126 ((resolve 'fixtures.scoped.delta/tripled)))))
    (is (empty? (:skipped r)))))

(deftest a-scoped-reload-with-nothing-changed-runs-no-pass-and-says-so
  (hot/init! {:dirs [root-a root-b]})
  (reload-fixtures!)
  (let [r (hot/reload-scoped! [root-a])]
    (is (:success r) (pr-str r))
    (is (true? (:unchanged? r)))
    (is (empty? (:loaded r)))
    (is (empty? (:skipped r)))))

(deftest init-since-admits-an-edit-made-before-init
  (reload-fixtures!)
  (write! alpha-file (alpha-src 7))
  (testing "the default baseline is 'now', so an edit made before init is
            silently the baseline and the first reload reports nothing"
    (hot/init! {:dirs [root-a root-b]})
    (let [r (hot/reload!)]
      (is (:success r) (pr-str r))
      (is (true? (:unchanged? r)))
      (is (= 1 (current 'fixtures.integration.alpha/value)))))
  (testing "a :since before the edit makes the same reload load it"
    (hot/init! {:dirs [root-a root-b]
                :since (- (System/currentTimeMillis) 60000)})
    (let [r (hot/reload!)]
      (is (:success r) (pr-str r))
      (is (contains? (set (:loaded r)) 'fixtures.integration.alpha))
      (is (= 7 (current 'fixtures.integration.alpha/value))))))

(deftest extend-init-adds-a-root-without-resetting-the-baseline
  (hot/init! {:dirs [root-a]})
  (reload-fixtures!)
  (write! alpha-file (alpha-src 42))
  (let [{:keys [added dirs]} (hot/extend-init! {:dirs [root-b]})]
    (is (= [root-b] added))
    (is (= [root-a root-b] dirs)))
  (testing "extending again with nothing new is a no-op"
    (is (= [] (:added (hot/extend-init! {:dirs [root-b]})))))
  (write! gamma-file (gamma-src 99))
  (testing "the new root is reloadable, and the older pending change is declined"
    (let [r (hot/reload-scoped! [root-b])]
      (is (:success r) (pr-str r))
      (is (= 99 (current 'fixtures.scoped.gamma/value)))
      (is (= ["fixtures.integration.alpha"] (:skipped r)))))
  (testing "and the change made before the extension is still pending"
    (let [r (hot/reload-scoped! [root-a])]
      (is (:success r) (pr-str r))
      (is (= 42 (current 'fixtures.integration.alpha/value))))))

(deftest ensure-init-initializes-once-and-extends-after
  (let [first (hot/ensure-init! {:dirs [root-a]})
        second (hot/ensure-init! {:dirs [root-a root-b]})
        third (hot/ensure-init! {:dirs [root-b]})]
    (is (true? (:fresh? first)))
    (is (false? (:fresh? second)))
    (is (= [root-b] (:added second)))
    (is (= [] (:added third)))
    (is (= [root-a root-b] (:dirs (hot/status))))))

(deftest a-namespace-shadowed-by-a-second-file-is-reported
  (testing "two files declare fixtures.scoped.gamma; the reload loads both and
            names the namespace, because whichever file loaded last decides the
            var — the 'reloaded but the var did not change' shape"
    (hot/init! {:dirs [root-b root-c]})
    (require 'fixtures.scoped.gamma :reload)
    (write! gamma-file (gamma-src 99))
    (let [r (hot/reload-scoped! [root-b])]
      (is (:success r) (pr-str r))
      (is (contains? (:multi-file r) 'fixtures.scoped.gamma))
      (is (= 2 (count (get (:multi-file r) 'fixtures.scoped.gamma)))))))
