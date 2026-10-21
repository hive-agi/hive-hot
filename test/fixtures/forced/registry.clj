(ns fixtures.forced.registry
  "Test fixture: a top-level def that captures verb VALUES at load time, so only
   re-evaluating this namespace picks up a new verb."
  (:require [fixtures.forced.verbs :as verbs]))

(def handlers {:greet verbs/greet})

(defn call [k] ((get handlers k)))
