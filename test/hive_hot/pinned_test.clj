(ns hive-hot.pinned-test
  "A pinned namespace whose source changed is refreshed in place: new and
   changed fns land, its protocol and its state keep their identity, and the
   dependents a pass reloads find the vars it gained. Fixtures:
     test/fixtures/pinref   pin (defprotocol, an atom, a fn; pinned :no-reload)
                            dep (requires pin)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-hot.core :as hot]
            [hive-hot.pinned :as pinned]))

(def ^:private root "test/fixtures/pinref")
(def ^:private pin-file (str root "/pin.clj"))
(def ^:private dep-file (str root "/dep.clj"))

(defn- pin-src
  [{:keys [f extra proto] :or {f 1 extra "" proto "(poke [x] \"Poke.\")"}}]
  (str "(ns fixtures.pinref.pin\n"
       "  \"Test fixture: a PINNED namespace that defines a protocol and holds state.\n"
       "   Rewritten by hive-hot.pinned-test.\")\n\n"
       "(defprotocol IPin\n  " proto ")\n\n"
       "(def state (atom 0))\n\n"
       "(defn f [] " f ")\n"
       extra))

(defn- dep-src [body]
  (str "(ns fixtures.pinref.dep\n"
       "  \"Test fixture: depends on the pinned fixtures.pinref.pin. Rewritten by\n"
       "   hive-hot.pinned-test.\"\n"
       "  (:require [fixtures.pinref.pin :as pin]))\n\n"
       "(defn g [] " body ")\n"))

(def ^:private fixture-nses '[fixtures.pinref.dep fixtures.pinref.pin])

(defn- write! [path content]
  (Thread/sleep 20)
  (spit path content)
  (Thread/sleep 20))

(defn- forget! [ns]
  (remove-ns ns)
  (dosync (alter @#'clojure.core/*loaded-libs* disj ns)))

(defn- restore! []
  (spit pin-file (pin-src {}))
  (spit dep-file (dep-src "(pin/f)")))

(use-fixtures :each
  (fn [t]
    (hot/reset-all!)
    (run! forget! fixture-nses)
    (restore!)
    (Thread/sleep 20)
    (try (t)
         (finally
           (hot/reset-all!)
           (restore!)
           (run! forget! fixture-nses)))))

(defn- v [sym] @(resolve sym))

(deftest form-action-classifies-what-a-refresh-may-replace
  (require 'fixtures.pinref.pin)
  (let [act #(:action (pinned/form-action 'fixtures.pinref.pin %))]
    (is (= :type (act '(defprotocol IPin (poke [x])))))
    (is (= :state (act '(def state (atom 1)))))
    (is (= :eval (act '(defn f [] 2))))
    (is (= :eval (act '(def brand-new 1))))
    (is (= :eval (act '(defprotocol INew (n [x]))))
        "a protocol that does not exist yet is defined")
    (is (= :eval (act '(ns fixtures.pinref.pin))))))

(deftest refresh-applies-fns-and-new-vars-and-keeps-protocol-and-state
  (require 'fixtures.pinref.pin)
  (let [proto (v 'fixtures.pinref.pin/IPin)
        state (v 'fixtures.pinref.pin/state)]
    (reset! state 5)
    (write! pin-file (pin-src {:f 2 :extra "\n(defn h [] :new)\n"}))
    (let [r (pinned/refresh! 'fixtures.pinref.pin pin-file)]
      (is (empty? (:errors r)) (pr-str r))
      (is (= '[h] (:new r)))
      (is (= '[IPin] (:types r)))
      (is (= '[state] (:kept r)))
      (is (empty? (:drift r))))
    (testing "fns take the source's value, new vars appear"
      (is (= 2 ((v 'fixtures.pinref.pin/f))))
      (is (= :new ((v 'fixtures.pinref.pin/h)))))
    (testing "the protocol and the state keep their identity"
      (is (identical? proto (v 'fixtures.pinref.pin/IPin)))
      (is (identical? state (v 'fixtures.pinref.pin/state)))
      (is (= 5 @state)))))

(deftest refresh-reports-protocol-drift-and-leaves-the-protocol
  (require 'fixtures.pinref.pin)
  (let [proto (v 'fixtures.pinref.pin/IPin)]
    (write! pin-file (pin-src {:proto "(poke [x] [x y] \"Poke.\")"}))
    (let [r (pinned/refresh! 'fixtures.pinref.pin pin-file)]
      (is (= [{:kind :protocol :name 'IPin
               :was {:poke #{1}} :now {:poke #{1 2}}}]
             (:drift r))))
    (is (identical? proto (v 'fixtures.pinref.pin/IPin)))))

(deftest refresh-of-an-unloaded-namespace-is-an-error-not-a-throw
  (let [r (pinned/refresh! 'fixtures.pinref.nowhere pin-file)]
    (is (= ["namespace is not loaded"] (mapv :message (:errors r))))))

(deftest a-scoped-pass-refreshes-a-pinned-namespace-its-dependents-read
  (hot/init! {:dirs [root] :no-reload '#{fixtures.pinref.pin}})
  (require 'fixtures.pinref.dep)
  (let [proto (v 'fixtures.pinref.pin/IPin)]
    (write! pin-file (pin-src {:extra "\n(defn h [] :gained)\n"}))
    (write! dep-file (dep-src "(pin/h)"))
    (let [r (hot/reload-scoped! [root])]
      (is (:success r) (pr-str (dissoc r :exception)))
      (is (= ["fixtures.pinref.pin"] (mapv :ns (:pinned-refreshed r))))
      (is (not (some #{'fixtures.pinref.pin} (:loaded r))) "the pinned namespace is not reloaded")
      (is (= :gained ((v 'fixtures.pinref.dep/g))))
      (is (identical? proto (v 'fixtures.pinref.pin/IPin))))
    (testing "the refreshed file is seen: the next pass does not refresh it again"
      (is (nil? (:pinned-refreshed (hot/reload-scoped! [root])))))))

(deftest refresh-pinned-false-keeps-the-old-behaviour
  (hot/init! {:dirs [root] :no-reload '#{fixtures.pinref.pin}})
  (require 'fixtures.pinref.dep)
  (write! pin-file (pin-src {:extra "\n(defn h [] :gained)\n"}))
  (write! dep-file (dep-src "(pin/h)"))
  (let [r (hot/reload-scoped! [root] {:refresh-pinned? false})]
    (is (not (:success r)) "the dependent cannot find the var the pinned source gained")
    (is (nil? (:pinned-refreshed r)))))
