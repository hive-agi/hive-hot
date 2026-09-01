(ns fixtures.scoped.gamma
  "A SECOND file declaring fixtures.scoped.gamma, in a root no test requires
   from. clj-reload loads every file it finds for a namespace and the last one
   wins — the shadowing the :multi-file diagnostic exists to name.")

(def value :shadow)
