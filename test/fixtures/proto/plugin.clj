(ns fixtures.proto.plugin
  "Test fixture: extends fixtures.proto.shape/IShape WITHOUT naming the
   protocol namespace in its ns form - the plugin-style extension the require
   graph cannot see, so only the live image ties it to the protocol.")

(extend java.lang.String
  @(requiring-resolve 'fixtures.proto.shape/IShape)
  {:area (fn [s] (count s))})
