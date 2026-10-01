(ns hive-hot.dirs
  "Pure dir-set arithmetic for the tracked source roots.

   Every function here takes dirs already in ONE spelling (the boundary in
   hive-hot.core canonicalizes them) and answers data; nothing touches the file
   system or clj-reload.")

(defn plan-removal
  "What removing REQUESTED from CURRENT does, when CORE may never be removed.

   CURRENT    [dir ...] the tracked dirs, in order
   REQUESTED  [dir ...] the dirs a caller wants released
   CORE       #{dir ...} the dirs the initial init declared

   Returns
     {:dirs    [dir ...]  CURRENT without :removed, order kept
      :removed [dir ...]  requested, tracked and not core
      :kept    [dir ...]  requested and core: they stay tracked
      :absent  [dir ...]  requested but not tracked: nothing to do}

   Every requested dir lands in exactly one of :removed :kept :absent, once, in
   request order. Idempotent: planning the same request against the answer's
   :dirs removes nothing."
  [current requested core]
  (let [cur     (set current)
        core    (set core)
        req     (distinct requested)
        removed (filterv #(and (cur %) (not (core %))) req)]
    {:dirs    (into [] (remove (set removed)) current)
     :removed removed
     :kept    (filterv #(and (cur %) (core %)) req)
     :absent  (filterv (complement cur) req)}))
