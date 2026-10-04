(ns hive-hot.dirs
  "Pure dir-set arithmetic for the tracked source roots.

   Every function here takes dirs already in ONE spelling (the boundary in
   hive-hot.core canonicalizes them) and answers data; nothing touches the file
   system or clj-reload.

   Claims: {dir #{owner ...}} records which owners (an addon id, or
   `anonymous` for a caller that names none) asked for a dir through
   extend-init!. A dir stays tracked while ANY owner other than the releasing
   one still claims it.")

(def anonymous
  "The owner a caller that names none claims and releases under."
  :hive-hot/anonymous)

(defn owner-of
  "The claim key for a caller-supplied OWNER (nil -> `anonymous`)."
  [owner]
  (if (some? owner) owner anonymous))

(defn claim
  "CLAIMS with OWNER claiming every dir in DIRS."
  [claims owner dirs]
  (let [o (owner-of owner)]
    (reduce (fn [m d] (update m d (fnil conj #{}) o)) (or claims {}) dirs)))

(defn- release
  "CLAIMS with OWNER's claim on every dir in DIRS dropped; a dir nobody claims
   any more leaves the map."
  [claims owner dirs]
  (let [o (owner-of owner)]
    (reduce (fn [m d]
              (let [left (disj (get m d #{}) o)]
                (if (seq left) (assoc m d left) (dissoc m d))))
            (or claims {}) dirs)))

(defn plan-addition
  "The dirs REQUESTED adds to CURRENT, in the caller's spelling.

   CURRENT    [dir ...] the tracked dirs, canonical
   REQUESTED  [[canonical spelled] ...] in request order

   A root already tracked, or requested twice, is not added again (first
   spelling wins); request order is kept. Idempotent: planning REQUESTED
   against CURRENT plus the answer's canonicals adds nothing."
  [current requested]
  (first
   (reduce (fn [[out seen] [c d]]
             (if (seen c) [out seen] [(conj out d) (conj seen c)]))
           [[] (set current)]
           requested)))

(defn plan-removal
  "What OWNER releasing REQUESTED from CURRENT does, when CORE may never be
   removed and CLAIMS records who else still holds a dir.

   CURRENT    [dir ...] the tracked dirs, in order
   REQUESTED  [dir ...] the dirs a caller wants released
   CORE       #{dir ...} the dirs the initial init declared
   CLAIMS     {dir #{owner ...}} (5-arity; see ns doc)
   OWNER      the releasing owner, nil for `anonymous` (5-arity)

   Returns
     {:dirs    [dir ...]  CURRENT without :removed, order kept
      :removed [dir ...]  requested, tracked, not core, no other owner left
      :kept    [dir ...]  requested and tracked, but core or still claimed
      :absent  [dir ...]  requested but not tracked: nothing to do
      :shared  {dir [owner ...]}  for each kept dir another owner still claims
      :claims  {dir #{owner ...}} CLAIMS after OWNER's release}

   The 3-arity (no claims) answers only :dirs :removed :kept :absent.

   Every requested dir lands in exactly one of :removed :kept :absent, once, in
   request order. Idempotent: planning the same request against the answer's
   :dirs and :claims removes nothing."
  ([current requested core]
   (select-keys (plan-removal current requested core {} nil)
                [:dirs :removed :kept :absent]))
  ([current requested core claims owner]
   (let [cur     (set current)
         core    (set core)
         req     (vec (distinct requested))
         claims' (release claims owner (filter cur req))
         others  (fn [d] (get claims' d))
         removed (filterv #(and (cur %) (not (core %)) (empty? (others %))) req)
         kept    (filterv #(and (cur %) (or (core %) (seq (others %)))) req)]
     {:dirs    (into [] (remove (set removed)) current)
      :removed removed
      :kept    kept
      :absent  (filterv (complement cur) req)
      :shared  (into {} (keep (fn [d] (when-let [os (seq (others d))]
                                        [d (vec (sort-by pr-str os))])))
                     kept)
      :claims  (apply dissoc claims' removed)})))
