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

(deftrifecta changed-roots
  hot/changed-roots
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
   :mutations [["every-dir" (fn [ds _] (mapv canon ds))]
               ["nothing" (fn [_ _] [])]]
   :assert (fn []
             (is (= [(canon root-a)] (hot/changed-roots dirs [(str root-a "/alpha.clj")]))
                 "a save under one root scopes the reload to that root alone")
             (is (= [] (hot/changed-roots dirs ["/nowhere/else.clj"])))
             (is (= [] (hot/changed-roots dirs [(str root-a "-other/x.clj")]))
                 "a sibling dir sharing the root's prefix is not under it"))})
