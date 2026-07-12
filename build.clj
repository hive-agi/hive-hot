(ns build
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'io.github.hive-agi/hive-hot)
(def version (str/trim (slurp "VERSION")))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(def src-dirs
  "The library's :paths, read straight from deps.edn so the build can't drift
   from the classpath, filtered to the dirs that actually exist on disk:
   \"resources\" is declared in :paths but not (yet) present, and b/copy-dir
   throws on a missing source dir."
  (->> (:paths (edn/read-string (slurp "deps.edn")))
       (filterv #(.isDirectory (io/file %)))))

(def pom-data
  [[:description "Hot-reload for Clojure backends: a component registry with lifecycle callbacks and event listeners layered on tonsky/clj-reload."]
   [:url "https://github.com/hive-agi/hive-hot"]
   [:licenses
    [:license
     [:name "MIT"]
     [:url "https://opensource.org/license/mit"]]]
   [:scm
    [:url "https://github.com/hive-agi/hive-hot"]
    [:connection "scm:git:git://github.com/hive-agi/hive-hot.git"]
    [:developerConnection "scm:git:ssh://git@github.com/hive-agi/hive-hot.git"]
    [:tag (str "v" version)]]
   [:developers
    [:developer
     [:name "Pedro G. Branquinho"]]]])

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar
  "Build the library thin jar + pom for Clojars/Maven consumption."
  [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :pom-data pom-data})
  (b/copy-dir {:src-dirs src-dirs
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file})
  (println (str "Built " jar-file)))

(defn deploy
  "Deploy the library jar to Clojars. Requires CLOJARS_USERNAME + CLOJARS_PASSWORD
   (a Clojars deploy token) in the environment."
  [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact  jar-file
    :pom-file  (b/pom-path {:lib lib :class-dir class-dir})})
  (println (str "Deployed " lib " " version " to Clojars")))
