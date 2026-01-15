(ns hive-hot.core-test
  "Tests for hive-hot core functionality."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-hot.core :as hot]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn reset-fixture [f]
  (hot/reset!)
  (f)
  (hot/reset!))

(use-fixtures :each reset-fixture)

;; =============================================================================
;; Registry Tests
;; =============================================================================

(deftest reg-hot-test
  (testing "registers component with required fields"
    (hot/reg-hot :test-component {:ns 'hive-hot.core})
    (let [comp (hot/get-component :test-component)]
      (is (some? comp))
      (is (= 'hive-hot.core (:ns comp)))
      (is (= :idle (:status comp))))))

(deftest reg-hot-with-callbacks-test
  (testing "registers component with callbacks"
    (let [reload-called (atom false)
          error-called (atom false)]
      (hot/reg-hot :callback-test
                   {:ns 'hive-hot.core
                    :on-reload #(reset! reload-called true)
                    :on-error #(reset! error-called true)})
      (let [comp (hot/get-component :callback-test)]
        (is (fn? (:on-reload comp)))
        (is (fn? (:on-error comp)))))))

(deftest unreg-hot-test
  (testing "unregisters component"
    (hot/reg-hot :to-remove {:ns 'hive-hot.core})
    (is (some? (hot/get-component :to-remove)))
    (hot/unreg-hot :to-remove)
    (is (nil? (hot/get-component :to-remove)))))

(deftest list-components-test
  (testing "lists all registered components"
    (hot/reg-hot :comp-a {:ns 'ns.a})
    (hot/reg-hot :comp-b {:ns 'ns.b})
    (is (= #{:comp-a :comp-b} (set (hot/list-components))))))

;; =============================================================================
;; Listener Tests
;; =============================================================================

(deftest add-listener-test
  (testing "adds and removes listeners"
    (let [events (atom [])]
      (hot/add-listener! :test-listener #(swap! events conj %))
      (is (= 1 (:listener-count (hot/status))))
      (hot/remove-listener! :test-listener)
      (is (= 0 (:listener-count (hot/status)))))))

(deftest listener-receives-events-test
  (testing "listener receives reload events"
    (let [events (atom [])]
      (hot/add-listener! :collector #(swap! events conj (:type %)))
      ;; Init triggers no events, just setup
      (hot/init! {:dirs ["src"]})
      ;; Reload triggers events
      (hot/reload!)
      (is (contains? (set @events) :reload-start))
      (is (or (contains? (set @events) :reload-success)
              (contains? (set @events) :reload-error))))))

;; =============================================================================
;; Status Tests
;; =============================================================================

(deftest status-test
  (testing "returns current status"
    (hot/reg-hot :status-test {:ns 'hive-hot.core})
    (let [status (hot/status)]
      (is (map? status))
      (is (contains? status :initialized?))
      (is (contains? status :components))
      (is (contains? status :listener-count))
      (is (= 1 (count (:components status)))))))

(deftest status-initialized-test
  (testing "tracks initialization state"
    (is (false? (:initialized? (hot/status))))
    (hot/init! {:dirs ["src"]})
    (is (true? (:initialized? (hot/status))))))

;; =============================================================================
;; Init Tests
;; =============================================================================

(deftest init-test
  (testing "initializes clj-reload"
    (is (= :initialized (hot/init! {:dirs ["src"]})))
    (is (true? (:initialized? (hot/status))))))

(deftest init-default-dirs-test
  (testing "uses default dirs when not specified"
    (is (= :initialized (hot/init!)))
    (is (true? (:initialized? (hot/status))))))
