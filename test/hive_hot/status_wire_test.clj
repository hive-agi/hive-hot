(ns hive-hot.status-wire-test
  "`status` is read across a serialization boundary: a tool surface renders it as
   JSON. The registry it reports holds the LIVE :on-reload / :on-error closures
   a caller registered, and handing those out turned every status call into a
   serialization failure whose only name was the closure's class, accusing
   whoever registered the callback instead of the report carrying it.

   These tests pin the report as data. The assertion is structural rather than a
   JSON round trip on purpose: it names every leaf that is not wire-safe, and it
   holds for any serializer rather than the one that happens to be on this
   classpath."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.walk :as walk]
            [hive-hot.core :as hot]))

;; SPDX-License-Identifier: MIT

(defn- wire-safe?
  "Values a JSON writer can render without knowing about Clojure hosts."
  [x]
  (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x) (coll? x)))

(defn- unsafe-leaves
  "Every leaf in FORM that no serializer can be expected to render, each as
   [class-name printed-value] so a failure names the culprit."
  [form]
  (let [found (atom [])]
    (walk/postwalk (fn [x]
                     (when-not (wire-safe? x)
                       (swap! found conj [(.getName (class x)) (pr-str x)]))
                     x)
                   form)
    @found))

(defn- reset-world! []
  (hot/unreg-hot :wire-probe))

(use-fixtures :each (fn [t] (reset-world!) (t) (reset-world!)))

(deftest a-registered-callback-does-not-reach-the-report
  (hot/reg-hot :wire-probe {:ns 'hive-hot.core
                            :on-reload (fn [] :reloaded)
                            :on-error (fn [_ex] :failed)})
  (let [report (hot/status)]
    (is (empty? (unsafe-leaves report))
        "a function (or symbol, or File) anywhere in the report makes the whole
         status call throw at the serializer, not just that one field")
    (testing "what a reader can act on is kept"
      (let [row (get-in report [:components :wire-probe])]
        (is (= "hive-hot.core" (:ns row)))
        (is (true? (:on-reload? row)) "whether a callback is installed survives")
        (is (true? (:on-error? row)))
        (is (= :idle (:status row)))))))

(deftest a-component-with-no-callbacks-still-reports-them-as-installed
  (testing "reg-hot defaults both callbacks, so the flags describe the registry
            as it really is rather than what the caller passed"
    (hot/reg-hot :wire-probe {:ns 'hive-hot.core})
    (let [row (get-in (hot/status) [:components :wire-probe])]
      (is (true? (:on-reload? row)))
      (is (true? (:on-error? row))))))

(deftest the-initialized-report-is-wire-safe-too
  (hot/reg-hot :wire-probe {:ns 'hive-hot.core :on-reload (fn [] nil)})
  (hot/init! {:dirs ["src"]})
  (let [report (hot/status)]
    (is (true? (:initialized? report)))
    (is (empty? (unsafe-leaves report))
        ":dirs comes from clj-reload's config, which is free to hand back File
         objects; the report must not depend on that choice")
    (is (every? string? (:dirs report)))))
