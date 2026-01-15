(ns fixtures.integration.beta
  "Test fixture that depends on alpha. Tests dependency cascade."
  (:require [fixtures.integration.alpha :as alpha]))

(defn doubled
  "Returns alpha's value doubled."
  []
  (* 2 (alpha/get-value)))
