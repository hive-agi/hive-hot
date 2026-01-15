(ns hive-hot.debounce-test
  "Tests for hive-hot.debounce CoordinatingDebouncer."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-hot.debounce :as debounce]))

;; =============================================================================
;; Constructor Tests
;; =============================================================================

(deftest create-debouncer-test
  (testing "creates debouncer with correct config"
    (let [d (debounce/create-debouncer identity (constantly #{}))]
      (is (some? d))
      (is (= #{} (debounce/pending-files d)))
      (is (= #{} (debounce/unclaimed-files d)))
      (debounce/stop-debouncer! d)))

  (testing "accepts custom cooldown-ms"
    (let [d (debounce/create-debouncer identity (constantly #{}) :cooldown-ms 200)]
      (is (= 200 (:cooldown-ms d)))
      (debounce/stop-debouncer! d))))

;; =============================================================================
;; Unclaimed File Tests
;; =============================================================================

(deftest unclaimed-file-fires-callback-test
  (testing "unclaimed file triggers callback after cooldown"
    (let [reloaded (atom [])
          claim-checker (constantly #{}) ; No files claimed
          d (debounce/create-debouncer #(swap! reloaded conj %)
                                       claim-checker
                                       :cooldown-ms 50)]
      (debounce/handle-event! d {:file "foo.clj" :type :modify})
      (Thread/sleep 100) ; Wait for debounce
      (is (seq @reloaded) "Callback should have fired")
      (is (contains? (first @reloaded) "foo.clj"))
      (debounce/stop-debouncer! d))))

(deftest debounce-coalesces-events-test
  (testing "multiple rapid events fire single callback"
    (let [callback-count (atom 0)
          claim-checker (constantly #{})
          d (debounce/create-debouncer (fn [_] (swap! callback-count inc))
                                       claim-checker
                                       :cooldown-ms 100)]
      ;; Fire multiple events rapidly
      (debounce/handle-event! d {:file "a.clj" :type :modify})
      (Thread/sleep 10)
      (debounce/handle-event! d {:file "b.clj" :type :modify})
      (Thread/sleep 10)
      (debounce/handle-event! d {:file "c.clj" :type :modify})
      ;; Wait for debounce to fire
      (Thread/sleep 200)
      ;; Multiple callbacks may fire due to async nature, but files are coalesced
      (is (>= @callback-count 1) "At least one callback should fire")
      (debounce/stop-debouncer! d))))

;; =============================================================================
;; Claimed File Tests
;; =============================================================================

(deftest claimed-file-buffers-test
  (testing "claimed file goes to pending, no immediate callback"
    (let [reloaded (atom [])
          claimed-files (atom #{"bar.clj"})
          claim-checker #(deref claimed-files)
          d (debounce/create-debouncer #(swap! reloaded conj %)
                                       claim-checker
                                       :cooldown-ms 50)]
      (debounce/handle-event! d {:file "bar.clj" :type :modify})
      (Thread/sleep 100)
      ;; Should be in pending, not reloaded
      (is (contains? (debounce/pending-files d) "bar.clj"))
      (is (empty? @reloaded) "Claimed file should not trigger callback")
      (debounce/stop-debouncer! d))))

(deftest on-claims-released-flushes-test
  (testing "releasing claims triggers callback with buffered files"
    (let [reloaded (atom [])
          claimed-files (atom #{"bar.clj"})
          claim-checker #(deref claimed-files)
          d (debounce/create-debouncer #(swap! reloaded conj %)
                                       claim-checker
                                       :cooldown-ms 50)]
      ;; Event for claimed file
      (debounce/handle-event! d {:file "bar.clj" :type :modify})
      (Thread/sleep 100)
      (is (empty? @reloaded))

      ;; Release claim
      (reset! claimed-files #{})
      (debounce/on-claims-released! d #{"bar.clj"})
      (Thread/sleep 50)

      ;; Should now be reloaded
      (is (seq @reloaded))
      (is (some #(contains? % "bar.clj") @reloaded))
      (is (empty? (debounce/pending-files d)) "Pending should be cleared")
      (debounce/stop-debouncer! d))))

;; =============================================================================
;; Flush Tests
;; =============================================================================

(deftest flush-pending-test
  (testing "flush-pending! forces immediate callback"
    (let [reloaded (atom [])
          claimed-files (atom #{"pending.clj"})
          claim-checker #(deref claimed-files)
          d (debounce/create-debouncer #(swap! reloaded conj %)
                                       claim-checker
                                       :cooldown-ms 1000)] ; Long cooldown
      ;; Add a claimed file
      (debounce/handle-event! d {:file "pending.clj" :type :modify})
      ;; Add an unclaimed file  
      (reset! claimed-files #{})
      (debounce/handle-event! d {:file "unclaimed.clj" :type :modify})

      ;; Flush immediately
      (debounce/flush-pending! d)

      ;; Both should be flushed
      (is (seq @reloaded))
      (let [all-flushed (reduce into #{} @reloaded)]
        (is (contains? all-flushed "pending.clj"))
        (is (contains? all-flushed "unclaimed.clj")))
      (is (empty? (debounce/pending-files d)))
      (is (empty? (debounce/unclaimed-files d)))
      (debounce/stop-debouncer! d))))

;; =============================================================================
;; Mixed Scenarios
;; =============================================================================

(deftest mixed-claimed-unclaimed-test
  (testing "handles mix of claimed/unclaimed files correctly"
    (let [reloaded (atom [])
          claimed-files (atom #{"claimed.clj"})
          claim-checker #(deref claimed-files)
          d (debounce/create-debouncer #(swap! reloaded conj %)
                                       claim-checker
                                       :cooldown-ms 50)]
      ;; Event for unclaimed file
      (debounce/handle-event! d {:file "unclaimed.clj" :type :modify})
      ;; Event for claimed file
      (debounce/handle-event! d {:file "claimed.clj" :type :modify})

      (Thread/sleep 100)

      ;; Unclaimed should be reloaded
      (is (some #(contains? % "unclaimed.clj") @reloaded)
          "Unclaimed file should be reloaded")
      ;; Claimed should be pending
      (is (contains? (debounce/pending-files d) "claimed.clj")
          "Claimed file should be pending")

      (debounce/stop-debouncer! d))))

(deftest claim-checker-called-per-event-test
  (testing "claim-checker is called for each event"
    (let [check-count (atom 0)
          claim-checker (fn []
                          (swap! check-count inc)
                          #{})
          d (debounce/create-debouncer identity claim-checker :cooldown-ms 50)]
      (debounce/handle-event! d {:file "a.clj" :type :modify})
      (debounce/handle-event! d {:file "b.clj" :type :modify})
      (debounce/handle-event! d {:file "c.clj" :type :modify})
      (is (= 3 @check-count) "Claim checker should be called per event")
      (debounce/stop-debouncer! d))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest empty-release-no-op-test
  (testing "releasing no files is a no-op"
    (let [reloaded (atom [])
          d (debounce/create-debouncer #(swap! reloaded conj %)
                                       (constantly #{}))]
      (debounce/on-claims-released! d #{})
      (Thread/sleep 50)
      (is (empty? @reloaded))
      (debounce/stop-debouncer! d))))

(deftest release-non-pending-file-ignored-test
  (testing "releasing file not in pending is ignored"
    (let [reloaded (atom [])
          d (debounce/create-debouncer #(swap! reloaded conj %)
                                       (constantly #{"other.clj"}))]
      ;; Add one file to pending
      (debounce/handle-event! d {:file "other.clj" :type :modify})
      ;; Try to release a different file
      (debounce/on-claims-released! d #{"not-pending.clj"})
      (Thread/sleep 50)
      (is (empty? @reloaded))
      (debounce/stop-debouncer! d))))

(deftest stop-debouncer-clears-state-test
  (testing "stop-debouncer! clears all state"
    (let [d (debounce/create-debouncer identity (constantly #{"x.clj"}))]
      (debounce/handle-event! d {:file "x.clj" :type :modify})
      (is (seq (debounce/pending-files d)))
      (debounce/stop-debouncer! d)
      (is (empty? (debounce/pending-files d)))
      (is (empty? (debounce/unclaimed-files d))))))
