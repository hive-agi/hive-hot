(ns hive-hot.forced-test
  "A forced scoped reload repairs an image that diverged from disk without any
   file changing, and a reload of a changed namespace rebuilds the top-level
   defs of its dependents.

   test/fixtures/forced holds verbs and a registry whose `handlers` def captures
   verbs/greet by value at load time."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-hot.core :as hot]))

(def ^:private root "test/fixtures/forced")
(def ^:private other-root "test/fixtures/scoped")
(def ^:private verbs-file (str root "/verbs.clj"))

(defn- verbs-src [v]
  (str "(ns fixtures.forced.verbs\n"
       "  \"Test fixture: the verbs a registry captures at load time.\")\n\n"
       "(defn greet\n  \"Returns the greeting this source file holds.\"\n  []\n  " v ")\n"))

(defn- restore! [] (spit verbs-file (verbs-src :disk)))

(defn- fresh-load! []
  (doseq [ns '[fixtures.forced.registry fixtures.forced.verbs fixtures.scoped.gamma]]
    (remove-ns ns)
    (dosync (alter @#'clojure.core/*loaded-libs* disj ns)))
  (require 'fixtures.forced.verbs 'fixtures.forced.registry 'fixtures.scoped.gamma))

(defn- diverge!
  "Load fixtures.forced.verbs from a file OUTSIDE every tracked root, the way a
   load-file from another checkout does. Nothing tracked changes on disk."
  []
  (let [f (doto (io/file (System/getProperty "java.io.tmpdir")
                         (str "hive-hot-forced-" (System/nanoTime) ".clj"))
            (.deleteOnExit))]
    (spit f (verbs-src :diverged))
    (load-file (str f))))

(defn- greet [] ((resolve 'fixtures.forced.verbs/greet)))
(defn- call [] ((resolve 'fixtures.forced.registry/call) :greet))

(defn reset-fixture [f]
  (hot/reset-all!)
  (restore!)
  (Thread/sleep 20)
  (f)
  (hot/reset-all!)
  (restore!))

(use-fixtures :each reset-fixture)

(deftest an-unforced-reload-cannot-see-in-memory-divergence
  (hot/init! {:dirs [root]})
  (fresh-load!)
  (diverge!)
  (is (= :diverged (greet)))
  (let [r (hot/reload-scoped! [root])]
    (is (:success r) (pr-str r))
    (is (true? (:unchanged? r)))
    (is (false? (:forced? r)))
    (is (= :diverged (greet)) "mtime scan sees nothing; the image stays diverged")))

(deftest a-forced-reload-re-reads-the-root-and-rebuilds-dependents
  (hot/init! {:dirs [root]})
  (fresh-load!)
  (diverge!)
  (let [r (hot/reload-scoped! [root] {:force? true})]
    (is (:success r) (pr-str r))
    (is (true? (:forced? r)))
    (is (false? (:unchanged? r)))
    (is (contains? (set (:loaded r)) 'fixtures.forced.verbs))
    (is (contains? (set (:loaded r)) 'fixtures.forced.registry))
    (is (= :disk (greet)))
    (testing "the registry def captures the reloaded verb, not the diverged one"
      (is (= :disk (call)))
      (is (identical? @(resolve 'fixtures.forced.verbs/greet)
                      (:greet @(resolve 'fixtures.forced.registry/handlers)))))))

(deftest a-forced-reload-stays-inside-its-roots
  (hot/init! {:dirs [root other-root]})
  (fresh-load!)
  (let [gamma-before (find-ns 'fixtures.scoped.gamma)
        plan         (hot/scope-plan [root] {:force? true})
        r            (hot/reload-scoped! [root] {:force? true})]
    (is (:success r) (pr-str r))
    (is (true? (:forced? plan)))
    (is (seq (:want plan)))
    (is (every? #(.contains (.getPath ^java.io.File %) "fixtures/forced") (:want plan)))
    (is (not (contains? (set (:loaded r)) 'fixtures.scoped.gamma)))
    (is (identical? gamma-before (find-ns 'fixtures.scoped.gamma)))))

(deftest a-changed-namespace-reloads-the-dependents-that-captured-it
  (testing "no force needed when the file changed: the cascade re-evaluates
            the registry, so its captured def holds the new verb"
    (hot/init! {:dirs [root]})
    (fresh-load!)
    (Thread/sleep 20)
    (spit verbs-file (verbs-src :edited))
    (Thread/sleep 20)
    (let [r (hot/reload-scoped! [root])]
      (is (:success r) (pr-str r))
      (is (contains? (set (:loaded r)) 'fixtures.forced.registry))
      (is (= :edited (greet)))
      (is (= :edited (call))))))
