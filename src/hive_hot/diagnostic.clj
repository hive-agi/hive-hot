(ns hive-hot.diagnostic
  "Pure diagnostics for namespace reload outcomes."
  (:require [hive-help.core :as help]))

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
                      "Some namespaces may already have reloaded."
                      "HINT: Inspect :failed, :loaded and the exception; fix the source or dependency error, then retry the same scope. Avoid reload-all as a recovery shortcut.")})
    result))
