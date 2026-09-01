(ns fixtures.scoped.delta
  "Test fixture in the second root that DEPENDS on the first root's alpha."
  (:require [fixtures.integration.alpha :as alpha]))

(def tag :original)

(defn tripled
  "Returns alpha's value tripled."
  []
  (* 3 (alpha/get-value)))
