(ns hive-hot.local-roots-test
  "Local-root source directories are registered with both clj-reload and the watcher."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [hive-hot.core :as hot]
            [hive-test.trifecta :as tri])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temporary-root []
  (.toFile (Files/createTempDirectory "hot-local-root" (into-array FileAttribute []))))

(defn- delete-tree! [f]
  (when (.exists (io/file f))
    (doseq [child (reverse (file-seq (io/file f)))]
      (.delete child))))

(defn- isolated [f]
  (hot/stop-watcher!)
  (hot/reset-all!)
  (try (f)
       (finally (hot/stop-watcher!) (hot/reset-all!))))

(use-fixtures :each isolated)

(tri/deftrifecta local-root-source-dirs-verdict
  hive-hot.core/local-root-source-dirs
  {:golden-path "test/golden/hive-hot/local-root-source-dirs.edn"
   :cases {:none {:libs {}}
           :jar-only {:libs {'jar/lib {:mvn/version "1"}}}}
   :mutations [["invent-dir" (fn [_] ["/not-a-source-root"])]
               ["return-nil" (fn [_] nil)]]})

(deftest local-root-paths-are-relative-to-each-library-and-deduplicated
  (let [a (temporary-root)
        b (temporary-root)]
    (try
      (.mkdir (io/file a "src"))
      (.mkdir (io/file b "src"))
      (let [basis {:libs {'first/lib {:local/root (.getPath a)
                                      :paths ["src" "missing" "src"]}
                          'second/lib {:local/root (.getPath b) :paths ["src"]}
                          'jar/lib {:mvn/version "1" :paths ["src"]}}}
            expected [(.getCanonicalPath (io/file a "src"))
                      (.getCanonicalPath (io/file b "src"))]]
        (is (= expected (hot/local-root-source-dirs basis))))
      (finally (delete-tree! a) (delete-tree! b)))))

(deftest explicit-watcher-dirs-can-opt-out-of-local-root-discovery
  (let [root (temporary-root)]
    (try
      (hot/init-with-watcher! {:dirs [(.getPath root)] :watch-local-roots? false})
      (is (= [(.getPath root)] (hot/watching-paths)))
      (is (= [(.getPath root)] (:dirs (hot/status))))
      (finally (delete-tree! root)))))

(deftest a-discovered-library-root-is-registered-for-watching-and-reload
  (let [root (temporary-root)
        src (io/file root "src")]
    (try
      (.mkdir src)
      (let [basis {:libs {'fixture/lib {:local/root (.getPath root) :paths ["src"]}}}
            dir (first (hot/local-root-source-dirs basis))]
        ;; Exercise the same discovered paths passed into init-with-watcher! without
        ;; changing the process-wide tools.deps basis used by other tests.
        (hot/init-with-watcher! {:dirs [dir] :watch-local-roots? false})
        (is (= [dir] (hot/watching-paths)))
        (is (some #{dir} (:dirs (hot/status))))
        (let [result (hot/reload-scoped! [dir])]
          (is (:success result) (pr-str result))))
      (finally
        (hot/stop-watcher!)
        (delete-tree! root)))))
