(ns hive-hot.diagnostic-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [hive-hot.core :as hot]
            [hive-hot.diagnostic :as diagnostic]))

(deftest reload-diagnostic-preserves-partial-progress
  (let [failure {:success false :failed 'fixture.bad
                 :loaded ['fixture.good] :exception (Exception. "bad source")}
        result (diagnostic/reload-outcome failure)]
    (is (= failure (dissoc result :diagnostic)))
    (is (= :hot/reload-failed (get-in result [:diagnostic :code])))
    (is (false? (get-in result [:diagnostic :retryable])))
    (is (.contains (get-in result [:diagnostic :message]) "may already have reloaded")))
  (let [success {:success true :loaded ['fixture.good]}]
    (is (identical? success (diagnostic/reload-outcome success)))))

(deftest real-reload-failure-carries-guidance
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "hive-hot-diagnostic-" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file dir "broken.clj")]
    (try
      (spit file "(ns hive.hot.diagnostic.fixture)\n(def value (throw (ex-info \"fixture load failure\" {})))\n")
      (hot/init! {:dirs [(.getAbsolutePath dir)]})
      (let [result (hot/reload! {:only :all :throw false})]
        (is (false? (:success result)))
        (is (= :hot/reload-failed (get-in result [:diagnostic :code])))
        (is (false? (get-in result [:diagnostic :retryable]))))
      (finally
        (hot/reset-all!)
        (.delete file)
        (.delete dir)))))
