(ns hive-hot.watch-scope-test
  "The watcher's debounced reload is scoped to the watched dirs that hold the
   changed files: `changed-roots` maps a debounce batch to those roots, which
   `init-with-watcher!` hands to `reload-scoped!` instead of an image-wide
   `reload!`."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [is]]
            [clojure.test.check.generators :as gen]
            [hive-hot.core :as hot]
            [hive-test.trifecta :refer [deftrifecta]]))

(def ^:private root-a "test/fixtures/integration")
(def ^:private root-b "test/fixtures/scoped")
(def ^:private dirs [root-a root-b])

(defn- canon [p] (.getCanonicalPath (io/file p)))

(defn- relative
  "Canonical `paths` relative to the canonical working directory, so the
   golden holds the same rows in every checkout."
  [paths]
  (let [cwd (str (canon ".") java.io.File/separator)]
    (mapv #(if (.startsWith ^String % cwd) (subs % (count cwd)) %) paths)))

(defn changed-roots-relative
  "`hot/changed-roots` with its canonical roots made cwd-relative."
  [ds fs]
  (relative (hot/changed-roots ds fs)))

(deftrifecta changed-roots
  hive-hot.watch-scope-test/changed-roots-relative
  {:apply? true
   :golden-path "test/golden/watch-changed-roots.edn"
   :cases {:one-root [dirs [(str root-a "/alpha.clj")]]
           :both-roots [dirs [(str root-b "/gamma.clj") (str root-a "/alpha.clj")]]
           :same-root-twice [dirs [(str root-a "/alpha.clj") (str root-a "/beta.clj")]]
           :outside [dirs ["/nowhere/else.clj"]]
           :prefix-not-parent [dirs [(str root-a "-other/x.clj")]]
           :empty [dirs []]}
   :gen (gen/fmap (fn [picks] [dirs (mapv #(str % "/f.clj") picks)])
                  (gen/vector (gen/elements dirs) 0 4))
   :pred (fn [r] (and (vector? r) (= r (vec (distinct r))) (<= (count r) 2)))
   :num-tests 40
   :mutations [["every-dir" (fn [ds _] (relative (mapv canon ds)))]
               ["nothing" (fn [_ _] [])]]
   :assert (fn []
             (is (= [root-a] (changed-roots-relative dirs [(str root-a "/alpha.clj")]))
                 "a save under one root scopes the reload to that root alone")
             (is (= [] (changed-roots-relative dirs ["/nowhere/else.clj"])))
             (is (= [] (changed-roots-relative dirs [(str root-a "-other/x.clj")]))
                 "a sibling dir sharing the root's prefix is not under it"))})
