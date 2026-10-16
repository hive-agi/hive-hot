(ns fixtures.proto.ext
  "Test fixture: extends fixtures.proto.shape/IShape to a host class."
  (:require [fixtures.proto.shape :as shape]))

(extend-protocol shape/IShape
  java.lang.Long
  (area [n] (* n n)))
