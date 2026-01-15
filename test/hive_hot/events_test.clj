(ns hive-hot.events-test
  "Tests for hive-hot.events event emission."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-hot.events :as events]
            [hive.events :as ev]
            [hive.events.router :as router]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:private captured-events (atom []))

(defn capture-fixture
  "Fixture that captures dispatched events for testing."
  [f]
  (reset! captured-events [])
  (let [db (atom {})]
    (router/init! db)
    ;; Register handlers that capture events
    (ev/reg-event-fx :file/changed
                     (fn [_ event]
                       (swap! captured-events conj event)
                       {}))
    (ev/reg-event-fx :hot/reload-start
                     (fn [_ event]
                       (swap! captured-events conj event)
                       {}))
    (ev/reg-event-fx :hot/reload-success
                     (fn [_ event]
                       (swap! captured-events conj event)
                       {}))
    (ev/reg-event-fx :hot/reload-error
                     (fn [_ event]
                       (swap! captured-events conj event)
                       {}))
    (f)
    (router/clear-event)))

(use-fixtures :each capture-fixture)

;; =============================================================================
;; Event Emission Tests
;; =============================================================================

(deftest emit-file-changed-test
  (testing "emits :file/changed event with correct structure"
    (events/emit-file-changed! "src/foo.clj" :modify)
    ;; dispatch-sync for immediate testing
    (Thread/sleep 50) ; Allow async dispatch
    (let [event (first @captured-events)]
      (is (= :file/changed (first event)))
      (is (= "src/foo.clj" (:file (second event))))
      (is (= :modify (:type (second event))))
      (is (number? (:timestamp (second event))))))

  (testing "supports all change types"
    (reset! captured-events [])
    (events/emit-file-changed! "src/a.clj" :create)
    (events/emit-file-changed! "src/b.clj" :delete)
    (Thread/sleep 50)
    (is (= 2 (count @captured-events)))))

(deftest emit-file-changed-validation-test
  (testing "validates file is a string"
    (is (thrown? AssertionError (events/emit-file-changed! nil :modify)))
    (is (thrown? AssertionError (events/emit-file-changed! 123 :modify))))

  (testing "validates change-type is valid"
    (is (thrown? AssertionError (events/emit-file-changed! "foo.clj" :invalid)))
    (is (thrown? AssertionError (events/emit-file-changed! "foo.clj" nil)))))

(deftest emit-reload-start-test
  (testing "emits :hot/reload-start event"
    (events/emit-reload-start!)
    (Thread/sleep 50)
    (let [event (first @captured-events)]
      (is (= :hot/reload-start (first event)))
      (is (map? (second event)))
      (is (number? (:timestamp (second event)))))))

(deftest emit-reload-success-test
  (testing "emits :hot/reload-success with all fields"
    (events/emit-reload-success! ['my.ns 'other.ns] ['my.ns] 42)
    (Thread/sleep 50)
    (let [event (first @captured-events)
          data (second event)]
      (is (= :hot/reload-success (first event)))
      (is (= ['my.ns 'other.ns] (:loaded data)))
      (is (= ['my.ns] (:unloaded data)))
      (is (= 42 (:ms data)))
      (is (number? (:timestamp data)))))

  (testing "handles empty sequences"
    (reset! captured-events [])
    (events/emit-reload-success! [] [] 0)
    (Thread/sleep 50)
    (let [data (second (first @captured-events))]
      (is (= [] (:loaded data)))
      (is (= [] (:unloaded data))))))

(deftest emit-reload-error-test
  (testing "emits :hot/reload-error with string error"
    (events/emit-reload-error! 'broken.ns "Syntax error")
    (Thread/sleep 50)
    (let [event (first @captured-events)
          data (second event)]
      (is (= :hot/reload-error (first event)))
      (is (= 'broken.ns (:failed data)))
      (is (= "Syntax error" (:error data)))))

  (testing "converts exception to message"
    (reset! captured-events [])
    (events/emit-reload-error! 'broken.ns (Exception. "Test exception"))
    (Thread/sleep 50)
    (let [data (second (first @captured-events))]
      (is (= "Test exception" (:error data))))))

;; =============================================================================
;; Claim Checker Tests
;; =============================================================================

(deftest make-claim-checker-test
  (testing "creates function that returns claimed file set"
    (let [mock-claims [{:file "src/a.clj" :agent "ling-1"}
                       {:file "src/b.clj" :agent "ling-2"}]
          checker (events/make-claim-checker (constantly mock-claims))]
      (is (fn? checker))
      (is (= #{"src/a.clj" "src/b.clj"} (checker)))))

  (testing "handles empty claims"
    (let [checker (events/make-claim-checker (constantly []))]
      (is (= #{} (checker)))))

  (testing "updates when query-fn returns new data"
    (let [claims (atom [{:file "src/a.clj"}])
          checker (events/make-claim-checker (fn [] @claims))]
      (is (= #{"src/a.clj"} (checker)))
      (reset! claims [{:file "src/b.clj"} {:file "src/c.clj"}])
      (is (= #{"src/b.clj" "src/c.clj"} (checker))))))
