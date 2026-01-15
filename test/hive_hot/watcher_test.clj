(ns hive-hot.watcher-test
  "Tests for hive-hot.watcher FileWatcher implementation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-hot.watcher :as watcher]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; =============================================================================
;; Test Utilities
;; =============================================================================

(defn create-temp-dir
  "Create a temporary directory for testing."
  []
  (let [path (Files/createTempDirectory "hive-hot-test"
                                        (into-array FileAttribute []))]
    (.toFile path)))

(defn delete-recursively
  "Delete a file or directory recursively."
  [f]
  (let [file (io/file f)]
    (when (.exists file)
      (when (.isDirectory file)
        (doseq [child (.listFiles file)]
          (delete-recursively child)))
      (.delete file))))

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture
  "Fixture that creates and cleans up a temp directory."
  [f]
  (let [dir (create-temp-dir)]
    (try
      (binding [*test-dir* dir]
        (f))
      (finally
        (delete-recursively dir)))))

(use-fixtures :each temp-dir-fixture)

;; =============================================================================
;; Constructor Tests
;; =============================================================================

(deftest create-watcher-test
  (testing "creates watcher with correct initial state"
    (let [w (watcher/create-watcher)]
      (is (some? w))
      (is (satisfies? watcher/FileWatcher w))
      (watcher/stop! w))))

(deftest watching-false-initially-test
  (testing "watching? returns false before start"
    (let [w (watcher/create-watcher)]
      (is (false? (watcher/watching? w)))
      (watcher/stop! w))))

;; =============================================================================
;; Start/Stop Tests
;; =============================================================================

(deftest start-stop-test
  (testing "can start and stop watcher"
    (let [w (watcher/create-watcher)]
      ;; Start
      (watcher/start! w [(.getAbsolutePath *test-dir*)] identity)
      (is (true? (watcher/watching? w)))
      ;; Stop
      (watcher/stop! w)
      (Thread/sleep 50) ; Allow thread to exit
      (is (false? (watcher/watching? w))))))

(deftest start-multiple-paths-test
  (testing "can watch multiple directories"
    (let [dir2 (create-temp-dir)
          w (watcher/create-watcher)]
      (try
        (watcher/start! w [(.getAbsolutePath *test-dir*)
                           (.getAbsolutePath dir2)]
                        identity)
        (is (true? (watcher/watching? w)))
        (watcher/stop! w)
        (finally
          (delete-recursively dir2))))))

;; =============================================================================
;; Event Detection Tests
;; =============================================================================

(deftest detects-file-create-test
  (testing "create file in watched dir triggers callback with :create"
    (let [events (atom [])
          w (watcher/create-watcher)
          test-file (io/file *test-dir* "new-file.txt")]
      (watcher/start! w [(.getAbsolutePath *test-dir*)]
                      #(swap! events conj %))
      (Thread/sleep 100) ; Allow watcher to start
      (spit test-file "test content")
      (Thread/sleep 500) ; Allow WatchService to detect
      (watcher/stop! w)
      ;; Should have at least one create event (may also have modify)
      (is (some #(= :create (:type %)) @events)
          (str "Expected :create event, got: " @events)))))

(deftest detects-file-modify-test
  (testing "modify file triggers callback with :modify"
    (let [events (atom [])
          w (watcher/create-watcher)
          test-file (io/file *test-dir* "existing.txt")]
      ;; Create file first
      (spit test-file "initial")
      (Thread/sleep 100)
      ;; Start watching
      (watcher/start! w [(.getAbsolutePath *test-dir*)]
                      #(swap! events conj %))
      (Thread/sleep 100)
      ;; Modify the file
      (spit test-file "modified content")
      (Thread/sleep 500)
      (watcher/stop! w)
      (is (some #(= :modify (:type %)) @events)
          (str "Expected :modify event, got: " @events)))))

(deftest detects-file-delete-test
  (testing "delete file triggers callback with :delete"
    (let [events (atom [])
          w (watcher/create-watcher)
          test-file (io/file *test-dir* "to-delete.txt")]
      ;; Create file first
      (spit test-file "to be deleted")
      (Thread/sleep 100)
      ;; Start watching
      (watcher/start! w [(.getAbsolutePath *test-dir*)]
                      #(swap! events conj %))
      (Thread/sleep 100)
      ;; Delete the file
      (.delete test-file)
      (Thread/sleep 500)
      (watcher/stop! w)
      (is (some #(= :delete (:type %)) @events)
          (str "Expected :delete event, got: " @events)))))

;; =============================================================================
;; Safety Tests
;; =============================================================================

(deftest stop-prevents-callbacks-test
  (testing "after stop!, no more callbacks received"
    (let [events (atom [])
          w (watcher/create-watcher)
          test-file (io/file *test-dir* "after-stop.txt")]
      (watcher/start! w [(.getAbsolutePath *test-dir*)]
                      #(swap! events conj %))
      (Thread/sleep 100)
      (watcher/stop! w)
      (Thread/sleep 100)
      ;; Create file after stop
      (let [count-before (count @events)]
        (spit test-file "should not trigger")
        (Thread/sleep 300)
        (is (= count-before (count @events))
            "No new events should be received after stop")))))

(deftest double-start-ignored-test
  (testing "calling start! twice is idempotent"
    (let [w (watcher/create-watcher)]
      (watcher/start! w [(.getAbsolutePath *test-dir*)] identity)
      ;; Second start should be ignored (returns nil/false)
      (watcher/start! w [(.getAbsolutePath *test-dir*)] identity)
      (is (true? (watcher/watching? w)))
      (watcher/stop! w))))

(deftest callback-error-doesnt-crash-test
  (testing "callback exceptions don't crash the watcher"
    (let [good-events (atom [])
          w (watcher/create-watcher)
          call-count (atom 0)
          test-file1 (io/file *test-dir* "file1.txt")
          test-file2 (io/file *test-dir* "file2.txt")]
      (watcher/start! w [(.getAbsolutePath *test-dir*)]
                      (fn [event]
                        (swap! call-count inc)
                        (when (= 1 @call-count)
                          (throw (ex-info "Test exception" {})))
                        (swap! good-events conj event)))
      (Thread/sleep 100)
      (spit test-file1 "first")
      (Thread/sleep 300)
      (spit test-file2 "second")
      (Thread/sleep 300)
      (watcher/stop! w)
      ;; Should have received multiple calls despite first error
      (is (> @call-count 1) "Watcher should continue after callback error"))))
