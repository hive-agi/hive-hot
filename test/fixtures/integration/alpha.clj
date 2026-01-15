(ns fixtures.integration.alpha
  "Test fixture for integration tests. Will be modified during reload tests.")

(def value 1)

(defn get-value
  "Returns the current value."
  []
  value)
