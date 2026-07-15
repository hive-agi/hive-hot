(ns hive-hot.schema-test
  "reg-hot-schema coerces+validates a hot-reload component's opts against ONE
   malli schema before hive-hot.core/reg-hot stores them."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-hot.schema :as hs]))

(deftest reg-hot-schema-validates-then-registers
  (testing "valid opts pass the default component-schema and register (returns id)"
    (is (= :srv (hs/reg-hot-schema :srv {:ns 'my.server :on-reload (fn [] :ok)}))))
  (testing "an invalid opts map is refused (schema/invalid) before registration"
    (is (= :schema/invalid
           (try (hs/reg-hot-schema :bad {}) :no-throw
                (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))))
  (testing "a custom inline schema is honored"
    (is (= :schema/invalid
           (try (hs/reg-hot-schema :c [:map [:ns :symbol]] {:ns 42}) :no-throw
                (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))))
