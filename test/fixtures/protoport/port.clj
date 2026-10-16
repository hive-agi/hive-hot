(ns fixtures.protoport.port
  "Test fixture: a protocol definer OUTSIDE the reloaded root that depends on
   it - the shape of a pinned SPI port namespace under an addon reload."
  (:require [fixtures.protopin.base :as base]))

(defprotocol IPort
  (ping [p] "Answer a ping."))

(def base-value base/value)
