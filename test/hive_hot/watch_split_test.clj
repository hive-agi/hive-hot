(ns hive-hot.watch-split-test
  "WHAT EMITS vs WHAT RELOADS. The watcher may report :file/changed for more
   paths than clj-reload tracks (`:watch-dirs`, `add-watch-dirs!`), and doing so
   must never widen the reload set. Default: emit set = reload set."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-hot.core :as hot]
            [hive-hot.watcher :as watcher])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private root-a "test/fixtures/integration")

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "hot-watch-split" (into-array FileAttribute []))))

(defn- delete-tree! [f]
  (doseq [c (reverse (file-seq (io/file f)))] (.delete ^java.io.File c)))

(defn- canon [p] (.getCanonicalPath (io/file p)))

(use-fixtures :each (fn [f]
                      (hot/stop-watcher!) (hot/reset-all!)
                      (try (f) (finally (hot/stop-watcher!) (hot/reset-all!)))))

(deftest watch-set-defaults-to-the-reload-dirs
  (is (= [root-a] (hot/watch-set [root-a] nil)) "no extras: exactly the reload dirs")
  (is (= [root-a] (hot/watch-set [root-a] [(canon root-a)])) "an extra already reloaded is not doubled"))

(deftest default-watcher-emits-exactly-what-it-reloads
  (hot/init-with-watcher! {:dirs [root-a] :watch-local-roots? false})
  (let [st (hot/watcher-status)]
    (is (= [root-a] (:dirs st)))
    (is (= [root-a] (:watch-dirs st)) "unchanged behaviour: one dir set")))

(deftest extra-watch-dirs-never-reach-the-reload-set
  (let [extra (tmp-dir)
        deps  (io/file extra "deps.edn")]
    (try
      (spit deps "{}")
      (hot/init-with-watcher! {:dirs [root-a] :watch-local-roots? false
                               :watch-dirs [(.getPath extra)]})
      (is (= [root-a] (:dirs (hot/status))) "clj-reload tracks the reload dir only")
      (is (some #{(.getPath extra)} (:watch-dirs (hot/watcher-status))))
      (testing "add-watch-dirs! on a running watcher: live, reload set unchanged"
        (let [more (tmp-dir)]
          (try
            (let [r (hot/add-watch-dirs! {:dirs [(.getPath more) (.getPath deps)] :owner :carto})]
              (is (true? (:live? r)))
              (is (= #{(canon more) (canon deps)} (set (:added r))))
              (is (= [root-a] (:dirs (hot/status))) "still only the reload dir")
              (is (some #{(canon more)} (:watch-dirs (hot/watcher-status)))))
            (testing "remove-watch-dirs! releases the claim, reload set untouched"
              (let [r (hot/remove-watch-dirs! {:dirs [(.getPath more)] :owner :carto})]
                (is (= [(canon more)] (:removed r)))
                (is (= [root-a] (:dirs (hot/status))))))
            (finally (delete-tree! more)))))
      (finally (delete-tree! extra)))))

(deftest a-watched-file-reports-itself-and-not-its-siblings
  (let [dir  (tmp-dir)
        deps (io/file dir "deps.edn")
        sib  (io/file dir "other.txt")
        seen (atom [])
        w    (watcher/create-watcher)]
    (try
      (spit deps "{}")
      (watcher/start! w [(.getPath deps)] #(swap! seen conj (:file %)))
      (Thread/sleep 100)
      (spit sib "x")
      (spit deps "{:a 1}")
      (Thread/sleep 600)
      (is (some #(.endsWith ^String % "deps.edn") @seen) (pr-str @seen))
      (is (not-any? #(.endsWith ^String % "other.txt") @seen)
          "watching one file must not report every sibling in its dir")
      (finally (watcher/stop! w) (delete-tree! dir)))))
