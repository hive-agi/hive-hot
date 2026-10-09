(ns hive-hot.plan-test
  "hive-hot.plan: portable reload planning over ns forms as data. Golden
   cases pin the verbatim behaviour dirge's embedded host relies on; the
   properties check every dep edge is respected, cycles are reported rather
   than looped, and the order is a pure function of the input."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-hot.plan :as plan]
            [hive-test.trifecta :as tri]
            [malli.core :as m]
            [malli.generator :as mg]))

;; =============================================================================
;; Generators
;; =============================================================================

(def ^:private names '[a b c d e f g h])

(def ^:private gen-name (gen/elements names))

(def ^:private gen-external (gen/elements '[clojure.string ext.lib]))

(def gen-sources
  "Distinct-named sources whose requires point at each other, at
   themselves, or outside the set."
  (gen/bind (gen/fmap (comp vec distinct) (gen/vector gen-name 0 8))
            (fn [nses]
              (gen/fmap (fn [reqs]
                          (mapv (fn [n r] {:ns n :requires (set r)}) nses reqs))
                        (apply gen/tuple
                               (map (fn [_] (gen/vector (gen/one-of [gen-name gen-external]) 0 3))
                                    nses))))))

(def gen-acyclic-sources
  "Sources whose requires only point at names EARLIER in a hidden order,
   then shuffled: always fully placeable."
  (gen/bind (gen/fmap (comp vec distinct) (gen/vector gen-name 0 8))
            (fn [nses]
              (gen/bind
                (apply gen/tuple
                       (map-indexed (fn [i _]
                                      (if (zero? i)
                                        (gen/return [])
                                        (gen/vector (gen/elements (subvec nses 0 i)) 0 3)))
                                    nses))
                (fn [reqs]
                  (gen/shuffle (mapv (fn [n r] {:ns n :requires (set r)}) nses reqs)))))))

(defn- ns-form
  "An ns form declaring `source`, its requires spread over :require/:use
   in the spellings ns-deps reads."
  [{:keys [ns requires]}]
  (let [[a b] (split-at (quot (count requires) 2) (sort-by str requires))]
    (list* 'ns ns "doc"
           (cond-> []
             (seq a) (conj (list* :require (map-indexed (fn [i r] (if (even? i) r [r :as 'x])) a)))
             (seq b) (conj (list* :use b))))))

;; =============================================================================
;; Schema-derived contract
;; =============================================================================

(deftest schemas-describe-the-data
  (testing "every generated source and every plan conforms"
    (doseq [srcs (gen/sample gen-sources 50)]
      (is (every? #(m/validate plan/Source %) srcs))
      (is (m/validate plan/LoadPlan (plan/load-order srcs)))))
  (testing "ns forms built from sources are NsForms and round-trip"
    (doseq [srcs (gen/sample gen-sources 30) s srcs]
      (is (m/validate plan/NsForm (ns-form s)))
      (is (= s (plan/ns-deps (ns-form s))))))
  (testing "malli-generated sources plan to a valid LoadPlan"
    (doseq [srcs (mg/sample [:vector plan/Source] {:size 6 :seed 7})]
      (is (m/validate plan/LoadPlan (plan/load-order srcs))))))

;; =============================================================================
;; ns-deps
;; =============================================================================

(tri/deftrifecta ns-deps-reads-require-and-use
  hive-hot.plan/ns-deps
  {:golden-path "test/golden/hive-hot/plan-ns-deps.edn"
   :cases {:plain    '(ns a.b "doc" (:require x.y [p.q :as q] (pre fix [two :as t])) (:use u))
           :no-deps  '(ns a.b)
           :import   '(ns a.b (:import (java.io File)) (:refer-clojure :exclude [map]))
           :not-ns   '(def x 1)
           :bad-name '(ns "a.b")
           :not-form 42}
   :gen (gen/one-of [(gen/fmap ns-form (gen/fmap first (gen/not-empty gen-sources)))
                     (gen/return '(defn f []))
                     gen/any-equatable])
   :pred #(or (nil? %) (m/validate plan/Source %))
   :mutations [["ignores-use" (fn [form]
                                (when-let [d (plan/ns-deps form)]
                                  (update d :requires
                                          (fn [_] (set (for [c (drop 2 form)
                                                             :when (and (seq? c) (= :require (first c)))
                                                             s (rest c)
                                                             l (plan/require-targets s)]
                                                         l))))))]
               ["no-prefix-lists" (fn [form]
                                    (when-let [d (plan/ns-deps form)]
                                      (update d :requires #(set (remove (fn [s] (re-find #"\." (name s))) %)))))]]})

;; =============================================================================
;; load-order
;; =============================================================================

(def ^:private chain '[{:ns c :requires #{b}} {:ns b :requires #{a}} {:ns a :requires #{}}])

(tri/deftrifecta load-order-plans
  hive-hot.plan/load-order
  {:golden-path "test/golden/hive-hot/plan-load-order.edn"
   :cases {:chain    chain
           :ties     '[{:ns z :requires #{}} {:ns y :requires #{}} {:ns x :requires #{y}}]
           :self     '[{:ns a :requires #{a}}]
           :external '[{:ns a :requires #{clojure.string}}]
           :cycle    '[{:ns a :requires #{b}} {:ns b :requires #{a}}
                       {:ns c :requires #{a}} {:ns d :requires #{}}]
           :empty    []}
   :gen gen-sources
   :pred #(m/validate plan/LoadPlan %)
   :mutations [["input-order" (fn [srcs] {:order (vec srcs) :cycle []})]
               ["drops-cycle" (fn [srcs] (assoc (plan/load-order srcs) :cycle []))]
               ["sorted-ties" (fn [srcs] (update (plan/load-order srcs) :order
                                                  #(vec (sort-by (comp str :ns) %))))]]})

(defn- position [order] (into {} (map-indexed (fn [i s] [(:ns s) i])) order))

(defspec load-order-respects-every-dep-edge 300
  (prop/for-all [srcs gen-sources]
    (let [{:keys [order]} (plan/load-order srcs)
          pos (position order)]
      (every? (fn [{:keys [ns requires]}]
                (every? (fn [r] (or (= r ns) (not (contains? pos r)) (< (pos r) (pos ns))))
                        requires))
              order))))

(defspec load-order-partitions-its-input 300
  (prop/for-all [srcs gen-sources]
    (let [{:keys [order cycle]} (plan/load-order srcs)]
      (and (= (frequencies srcs) (frequencies (concat order cycle)))
           ;; :cycle keeps input order
           (= cycle (filterv (set cycle) srcs))))))

(defspec cycles-reported-not-looped 300
  (prop/for-all [srcs gen-sources]
    (let [{:keys [order cycle]} (plan/load-order srcs)
          placed (set (map :ns order))
          known  (set (map :ns srcs))]
      ;; every source left in :cycle is blocked by another unplaced known source
      (every? (fn [{:keys [ns requires]}]
                (some #(and (not= % ns) (contains? known %) (not (contains? placed %))) requires))
              cycle))))

(defspec acyclic-input-places-everything 300
  (prop/for-all [srcs gen-acyclic-sources]
    (let [{:keys [order cycle]} (plan/load-order srcs)]
      (and (empty? cycle) (= (count order) (count srcs))))))

(defspec load-order-is-deterministic 200
  (prop/for-all [srcs gen-sources]
    (= (plan/load-order srcs) (plan/load-order (vec srcs)) (plan/load-order (seq srcs)))))

(deftest a-pure-cycle-terminates
  (let [ring (mapv (fn [i] {:ns (symbol (str "n" i)) :requires #{(symbol (str "n" (mod (inc i) 50)))}})
                   (range 50))]
    (is (= {:order [] :cycle ring} (plan/load-order ring)))))

;; =============================================================================
;; dependees / dependents-closure (the JVM scope-plan cascade)
;; =============================================================================

(deftest dependents-closure-matches-clj-reload
  (let [graph '{a {:requires #{}} b {:requires #{a}} c {:requires #{b ext}} d {:requires #{}}}
        deps  (plan/dependees (plan/graph->sources graph))]
    (is (= '{a #{b} b #{c} c #{} d #{}} deps))
    (is (= '#{a b c} (plan/dependents-closure deps '#{a})))
    (is (= '#{d} (plan/dependents-closure deps '[d])))
    (is (= #{} (plan/dependents-closure deps [])))))

(defspec dependents-closure-is-upward-closed 200
  (prop/for-all [srcs gen-sources starts (gen/vector gen-name 0 3)]
    (let [known (set (map :ns srcs))
          deps  (plan/dependees srcs)
          cl    (plan/dependents-closure deps starts)]
      (and (every? cl starts)
           ;; a source requiring a KNOWN reached namespace is reached too
           (every? (fn [{:keys [ns requires]}]
                     (or (not-any? #(and (cl %) (known %)) requires) (contains? cl ns)))
                   srcs)))))
