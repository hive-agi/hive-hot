(ns hive-hot.foreign-failure-test
  "A namespace that does not compile under ANOTHER root must not kill a scoped
   reload — and must stay pending for its own root's reload.

   Two temp source roots stand in for two addons sharing one image:
     good   <p>.good.core     edited, compiles
     bad    <p>.bad.rules     edited so its ns form fails to macroexpand
   Every test builds fresh roots with unique namespace names under a temp dir,
   so nothing here touches the repo or another test's image state."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-hot.core :as hot])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir ^java.io.File [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- write!
  "Write with the mtime strictly after any earlier baseline (ms-grained)."
  [^java.io.File f content]
  (Thread/sleep 20)
  (io/make-parents f)
  (spit f content)
  (Thread/sleep 20))

(defn- fixture
  "Two temp roots, one namespace each, both loaded into the image."
  []
  (let [p      (str "ffprobe" (System/nanoTime))
        good   (temp-dir "hive-hot-good")
        bad    (temp-dir "hive-hot-bad")
        good-f (io/file good p "good" "core.clj")
        bad-f  (io/file bad p "bad" "rules.clj")
        g-ns   (symbol (str p ".good.core"))
        b-ns   (symbol (str p ".bad.rules"))
        g-src  (fn [v] (str "(ns " g-ns ")\n(def value " v ")\n"))
        b-src  (fn [v] (str "(ns " b-ns ")\n(def value " v ")\n"))]
    (write! good-f (g-src 1))
    (write! bad-f (b-src 1))
    (hot/init! {:dirs [(str good) (str bad)] :since 0})
    (let [r (hot/reload! {:only :all})]
      (assert (:success r) (pr-str r)))
    {:good (str good) :bad (str bad) :good-f good-f :bad-f bad-f
     :g-ns g-ns :b-ns b-ns :g-src g-src :b-src b-src
     ;; parses, but `ns` fails spec at macroexpansion — the shape seen in
     ;; hive-ingestor.source.parser-rules ("Syntax error macroexpanding")
     :broken (str "(ns " b-ns "\n  (:requir [clojure.string]))\n(def value 2)\n")}))

(defn- value [ns-sym] (some-> (ns-resolve ns-sym 'value) deref))

(use-fixtures :each (fn [f] (hot/reset-all!) (try (f) (finally (hot/reset-all!)))))

(deftest a-scoped-reload-declines-an-unrelated-namespace-that-does-not-compile
  (let [{:keys [good bad-f good-f g-ns b-ns g-src broken]} (fixture)]
    (write! bad-f broken)
    (write! good-f (g-src 2))
    (let [r (hot/reload-scoped! [good])]
      (is (:success r) (pr-str (dissoc r :exception)))
      (is (= 2 (value g-ns)))
      (is (= [(str b-ns)] (:skipped r)))
      (is (= 1 (value b-ns)) "the broken namespace was neither unloaded nor loaded"))))

(deftest work-a-failed-pass-left-under-another-root-does-not-kill-a-scoped-reload
  (let [{:keys [good bad bad-f good-f g-ns b-ns g-src b-src broken]} (fixture)]
    (testing "an unscoped pass (the watcher's) dies on the broken namespace and
              leaves it queued in clj-reload"
      (write! bad-f broken)
      (let [r (hot/reload!)]
        (is (false? (:success r)))
        (is (= b-ns (:failed r)))))
    (write! good-f (g-src 2))
    (testing "the good root's scoped reload loads its change and does not replay
              the other root's queued failure"
      (let [r (hot/reload-scoped! [good])]
        (is (:success r) (pr-str (dissoc r :exception)))
        (is (nil? (:failed r)))
        (is (contains? (set (:loaded r)) g-ns))
        (is (not (contains? (set (:loaded r)) b-ns)))
        (is (= 2 (value g-ns)))
        (is (= [(str b-ns)] (:withheld r)) "the declined queued work is named")))
    (testing "a scoped reload with nothing changed under its root runs no pass
              for another root's queued work"
      (let [r (hot/reload-scoped! [good])]
        (is (:success r) (pr-str (dissoc r :exception)))
        (is (empty? (:loaded r)))
        (is (false? (:pending? r)))))
    (testing "the queued work is still there for its own root: still failing
              while broken, then loaded once fixed"
      (let [r (hot/reload-scoped! [bad])]
        (is (false? (:success r)))
        (is (= b-ns (:failed r))))
      (write! bad-f (b-src 3))
      (let [r (hot/reload-scoped! [bad])]
        (is (:success r) (pr-str (dissoc r :exception)))
        (is (contains? (set (:loaded r)) b-ns))
        (is (= 3 (value b-ns)))))))
