(ns fixtures.proto.square
  "Test fixture: a defrecord implementing fixtures.proto.shape/IShape."
  (:require [fixtures.proto.shape :as shape]))

(defrecord Square [side]
  shape/IShape
  (area [_] (* side side)))
