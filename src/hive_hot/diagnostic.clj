(ns hive-hot.diagnostic
  "Pure diagnostics for namespace reload outcomes."
  (:require [hive-help.core :as help]))

(defn root-cause
  "The innermost throwable of `t`'s ex-cause chain as data:
   {:message :class :file :line :column}. The location comes from the
   innermost CompilerException-shaped link (ex-data :clojure.error/source,
   /line, /column) - the root itself usually carries none. nil for nil."
  [t]
  (when t
    (let [chain (take-while some? (iterate ex-cause t))
          root  (last chain)
          loc   (->> (reverse chain)
                     (map ex-data)
                     (some #(when (:clojure.error/source %) %)))]
      (cond-> {:message (ex-message root)
               :class   (.getName (class root))}
        loc (assoc :file   (:clojure.error/source loc)
                   :line   (:clojure.error/line loc)
                   :column (:clojure.error/column loc))))))

(defn root-summary
  "One line naming a root cause: message, class, ns and file:line when known."
  [{:keys [message class ns file line column]}]
  (str message " [" class "]"
       (when ns (str " in " ns))
       (when file (str " at " file (when line (str ":" line (when column (str ":" column))))))))

(defn with-root-cause
  "Attach :root-cause (the innermost ex-cause of :exception, plus the failed
   ns) to a reload result and make :error carry it after the wrapper message."
  [{:keys [exception failed] :as result}]
  (if-let [root (root-cause exception)]
    (let [root (cond-> root failed (assoc :ns (str failed)))]
      (assoc result
             :root-cause root
             :error (str (ex-message exception) " Root cause: " (root-summary root))))
    result))

(defn reload-outcome
  "Attach recovery guidance without changing the reload outcome."
  [result]
  (if (or (:failed result) (:exception result))
    (assoc result :diagnostic
           {:code :hot/reload-failed
            :retryable false
            :actions []
            :message (help/join-lines
                      (str "Namespace reload failed"
                           (when-let [failed (:failed result)]
                             (str " at " failed)) ".")
                      (when-let [root (:root-cause result)]
                        (str "Root cause: " (root-summary root)))
                      "Some namespaces may already have reloaded."
                      "HINT: Inspect :failed, :root-cause, :loaded and the exception; fix the source or dependency error, then retry the same scope. Avoid reload-all as a recovery shortcut.")})
    result))
