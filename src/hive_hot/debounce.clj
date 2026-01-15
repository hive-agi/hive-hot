(ns hive-hot.debounce
  "Coordination-aware debouncing for hot-reload events.
   
   Provides debouncing with claim-awareness:
   - Unclaimed files: time-based debounce (coalesce rapid changes)
   - Claimed files: buffer until claims released
   
   Design: State-based debounce windows, not just time-based."
  (:require [clojure.core.async :as async :refer [go go-loop <! >! timeout chan close!]]))

;; =============================================================================
;; Debouncer Implementation
;; =============================================================================

(defrecord CoordinatingDebouncer [pending ; atom #{files} - buffered claimed files
                                  unclaimed ; atom #{files} - files awaiting debounce  
                                  cooldown-ms ; debounce window in ms
                                  claim-checker ; fn -> #{claimed-files}
                                  callback ; fn called with files to reload
                                  control-ch]) ; control channel for timer coordination

(defn- file-claimed?
  "Check if a file is currently claimed."
  [debouncer file]
  (contains? ((:claim-checker debouncer)) file))

(defn- schedule-debounce!
  "Schedule debounced callback for unclaimed files."
  [debouncer]
  (let [{:keys [unclaimed cooldown-ms callback control-ch]} debouncer]
    (go
      ;; Signal new debounce cycle
      (>! control-ch :reset)
      (<! (timeout cooldown-ms))
      ;; Check if we should fire (no reset received)
      (let [files-to-reload @unclaimed]
        (when (seq files-to-reload)
          (reset! unclaimed #{})
          (try
            (callback files-to-reload)
            (catch Exception e
              (println "Debounce callback error:" (.getMessage e)))))))))

(defn handle-event!
  "Handle a file change event.
   
   If file is claimed -> buffer in pending
   If file is unclaimed -> add to unclaimed set, schedule/reschedule debounce"
  [debouncer {:keys [file type] :as _event}]
  (let [{:keys [pending unclaimed]} debouncer]
    (if (file-claimed? debouncer file)
      ;; Claimed: buffer for later
      (swap! pending conj file)
      ;; Unclaimed: debounce
      (do
        (swap! unclaimed conj file)
        (schedule-debounce! debouncer)))))

(defn on-claims-released!
  "Called when file claims are released. Triggers reload of buffered files."
  [debouncer released-files]
  (let [{:keys [pending callback]} debouncer
        ;; Get intersection of pending and released
        files-to-reload (clojure.set/intersection @pending (set released-files))]
    (when (seq files-to-reload)
      ;; Remove from pending
      (swap! pending #(apply disj % files-to-reload))
      ;; Fire callback
      (try
        (callback files-to-reload)
        (catch Exception e
          (println "On-claims-released callback error:" (.getMessage e)))))))

(defn flush-pending!
  "Force flush all pending files immediately."
  [debouncer]
  (let [{:keys [pending unclaimed callback]} debouncer
        all-files (into @pending @unclaimed)]
    (reset! pending #{})
    (reset! unclaimed #{})
    (when (seq all-files)
      (try
        (callback all-files)
        (catch Exception e
          (println "Flush pending callback error:" (.getMessage e)))))))

(defn pending-files
  "Get the set of files currently buffered (claimed)."
  [debouncer]
  @(:pending debouncer))

(defn unclaimed-files
  "Get the set of files awaiting debounce (unclaimed)."
  [debouncer]
  @(:unclaimed debouncer))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-debouncer
  "Create a new CoordinatingDebouncer.
   
   Arguments:
   - callback: fn called with set of files to reload
   - claim-checker: fn that returns set of currently claimed files
   
   Options:
   - :cooldown-ms - debounce window (default 100ms)"
  [callback claim-checker & {:keys [cooldown-ms] :or {cooldown-ms 100}}]
  (->CoordinatingDebouncer
   (atom #{}) ; pending
   (atom #{}) ; unclaimed
   cooldown-ms
   claim-checker
   callback
   (chan (async/sliding-buffer 1)))) ; control-ch

(defn stop-debouncer!
  "Stop the debouncer and clean up resources."
  [debouncer]
  (close! (:control-ch debouncer))
  (reset! (:pending debouncer) #{})
  (reset! (:unclaimed debouncer) #{}))
