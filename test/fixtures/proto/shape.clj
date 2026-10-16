(ns fixtures.proto.shape
  "Test fixture: a namespace that DEFINES a protocol. Rewritten by the
   protocol-cascade tests to force a reload that redefines the protocol.")

(defprotocol IShape
  (area [s] "Area of the shape."))

(def version 1)
