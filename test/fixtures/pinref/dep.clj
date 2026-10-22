(ns fixtures.pinref.dep
  "Test fixture: depends on the pinned fixtures.pinref.pin. Rewritten by
   hive-hot.pinned-test."
  (:require [fixtures.pinref.pin :as pin]))

(defn g [] (pin/f))
