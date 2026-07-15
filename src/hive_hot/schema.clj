(ns hive-hot.schema
  "Schema-driven hot-reload registration — the hive-hot leg of the malli macro
   layer. `reg-hot-schema` coerces + validates a component's opts against ONE
   malli schema before hive-hot.core/reg-hot stores them: declare the component's
   opts schema once and its guard is wired, replacing the ad-hoc :pre check with a
   real value-object contract.

   hive-hot does not depend on hive-spi/malli; derive/compile-op is resolved
   LAZILY via a guarded requiring-resolve. When it is unresolvable (hive-spi
   absent) opts pass through and reg-hot's own :pre still guards :ns —
   registration never fails on a bare classpath."
  (:require [hive-hot.core :as hot]))

(def component-schema
  "malli value-object for a hot-reload component's opts (the shape reg-hot
   expects): a required :ns symbol and optional :on-reload / :on-error fns."
  [:map
   [:ns :symbol]
   [:on-reload {:optional true} [:fn fn?]]
   [:on-error {:optional true} [:fn fn?]]])

(defn- resolve-compile-op
  "hive-spi.schema.derive/compile-op, or nil when hive-spi is absent
   (requiring-resolve THROWS on an absent ns, so it is guarded)."
  []
  (try (requiring-resolve 'hive-spi.schema.derive/compile-op)
       (catch Throwable _ nil)))

(defn reg-hot-schema
  "Register a hot-reload component whose `opts` are coerced + validated against a
   malli `schema` (a registered schema-key or an inline malli form; defaults to
   `component-schema`) before hive-hot.core/reg-hot stores them. An invalid opts
   map throws {:error :schema/invalid ...} before registration. When hive-spi is
   absent, opts pass through unchecked (reg-hot's :pre still guards). Returns the
   component-id."
  ([component-id opts]
   (reg-hot-schema component-id component-schema opts))
  ([component-id schema opts]
   (let [compile-op (resolve-compile-op)
         opts'      (if compile-op
                      (let [{:keys [coerce]} (compile-op schema)] (coerce opts))
                      opts)]
     (hot/reg-hot component-id opts'))))
