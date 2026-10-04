(ns hive-hot.schema-test
  "reg-hot-schema coerces+validates a hot-reload component's opts against ONE
   malli schema before hive-hot.core/reg-hot stores them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-hot.schema :as hs]
            [hive-hot.core :as hot]
            [hive-spi.schema.gen]))

(use-fixtures :each
  (fn [t]
    (try (t)
         (finally
           (run! hot/unreg-hot [:srv :no-cb :nil-cb :var-cb :bad :bad-ns :junk-cb :c])))))

(deftest reg-hot-schema-validates-then-registers
  (testing "valid opts pass the default component-schema and register (returns id)"
    (let [cb (fn [] :ok)]
      (is (= :srv (hs/reg-hot-schema :srv {:ns 'my.server :on-reload cb})))
      (is (= cb (:on-reload (hot/get-component :srv))))))
  (testing "callbacks reg-hot tolerates stay accepted: absent, nil, a Var"
    (is (= :no-cb (hs/reg-hot-schema :no-cb {:ns 'my.server})))
    (is (= :nil-cb (hs/reg-hot-schema :nil-cb {:ns 'my.server :on-reload nil :on-error nil})))
    (is (= :var-cb (hs/reg-hot-schema :var-cb {:ns 'my.server :on-reload #'identity}))))
  (testing "an invalid opts map is refused (schema/invalid) and nothing is registered"
    (doseq [[id opts] {:bad     {}
                       :bad-ns  {:ns 42}
                       :junk-cb {:ns 'my.server :on-reload 42}}]
      (is (= :schema/invalid
             (try (hs/reg-hot-schema id opts) :no-throw
                  (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))
          (str id))
      (is (nil? (hot/get-component id)) (str id))))
  (testing "a custom inline schema is honored"
    (is (= :schema/invalid
           (try (hs/reg-hot-schema :c [:map [:ns :symbol]] {:ns 42}) :no-throw
                (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))))
