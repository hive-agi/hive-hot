(ns hive-hot.stale-registrations-test
  "The one failure a reload report could not otherwise show.

   When a registration is guarded so it runs once, a reload loads the new code
   and the registry keeps the old closure. :loaded lists the namespace, the vars
   carry the new arglists, nothing fails, and the image runs code that is no
   longer on disk. `stale-registrations` is what puts that in the report.

   `with-redefs` stands in for the reload: the var's root binding changes while
   a previously registered function value stays behind.

   Isolation: the effect registry is process-global, so the fixture snapshots it
   and puts it back."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-hot.core :as core]
            [hive.events.fx :as fx]))

(defn handler
  "Stand-in for a registered effect handler. Its identity is the whole point."
  [v]
  {:before v})

(defn- restore-fx-registry
  [f]
  (let [snapshot (fx/registry-snapshot)]
    (try
      (f)
      (finally (fx/restore-registry! snapshot)))))

(use-fixtures :each restore-fx-registry)

(def ^:private scan #'core/stale-registrations)

(def ^:private this-ns 'hive-hot.stale-registrations-test)

(deftest a-pass-that-reloaded-nothing-reports-nothing
  (is (nil? (scan nil)))
  (is (nil? (scan []))))

(deftest a-registration-that-matches-its-var-is-not-reported
  (fx/reg-fx ::probe handler)
  (is (nil? (scan [this-ns]))
      "the common case must stay silent, or the report is noise"))

(deftest a-registration-the-pass-left-behind-is-reported
  (fx/reg-fx ::probe handler)
  (with-redefs [handler (fn [v] {:after v})]
    (is (= [{:registry :fx
             :id       ::probe
             :owner    'hive-hot.stale-registrations-test/handler}]
           (scan [this-ns]))
        "the row names the registry, the key and the var to re-register")))

(deftest the-report-is-restricted-to-what-this-pass-reloaded
  (testing "a stale entry owned by a namespace the pass did not touch was
            already stale before it ran, and is not this pass's news"
    (fx/reg-fx ::probe handler)
    (with-redefs [handler (fn [v] {:after v})]
      (is (nil? (scan ['some.namespace.this.pass.did.not.load]))))))

(deftest the-rows-stay-printable
  (testing "no var objects, so the result survives serialization to a tool caller"
    (fx/reg-fx ::probe handler)
    (with-redefs [handler (fn [v] {:after v})]
      (is (= #{:registry :id :owner}
             (set (keys (first (scan [this-ns])))))))))

(deftest enriching-the-report-can-never-fail-the-reload
  (testing "a hive-events without the scan, or one that throws, leaves the
            report unenriched rather than breaking the pass"
    (with-redefs [requiring-resolve (fn [_] (throw (ex-info "no such ns" {})))]
      (is (nil? (scan [this-ns]))))))
