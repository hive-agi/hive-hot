(ns fixtures.pinref.pin
  "Test fixture: a PINNED namespace that defines a protocol and holds state.
   Rewritten by hive-hot.pinned-test.")

(defprotocol IPin
  (poke [x] "Poke."))

(def state (atom 0))

(defn f [] 1)
