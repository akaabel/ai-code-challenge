(ns com.robotfund.agents.scanner
  (:require [com.robotfund.alpaca :as alpaca]
            [com.robotfund.schema :as schema]
            [xtdb.api :as xt]))

(def watchlist
  ["AAPL" "MSFT" "NVDA" "GOOGL" "AMZN" "META" "TSLA" "JPM" "V" "UNH"])

(def ^:private price-change-threshold 1.5)
(def ^:private volume-spike-ratio     2.0)
(def ^:private min-vol-bars           5)

(defn- candidates-for [ticker bars]
  (when (>= (count bars) 2)
    (let [now        (java.util.Date.)
          last-bar   (last bars)
          prev-bar   (last (butlast bars))
          last-close (double (:c last-bar))
          prev-close (double (:c prev-bar))
          last-vol   (double (:v last-bar))
          hist-bars  (take-last 20 (butlast bars))
          pct-chg    (when (pos? prev-close)
                       (* 100.0 (/ (- last-close prev-close) prev-close)))
          avg-vol    (when (>= (count hist-bars) min-vol-bars)
                       (/ (reduce + (map #(double (:v %)) hist-bars)) (count hist-bars)))
          vol-ratio  (when (and avg-vol (pos? avg-vol))
                       (/ last-vol avg-vol))]
      (cond-> []
        (and pct-chg (> (Math/abs pct-chg) price-change-threshold))
        (conj {:xt/id                      (random-uuid)
               :candidate/ticker            ticker
               :candidate/scanned-at        now
               :candidate/trigger           :price-change
               :candidate/price-change-pct  pct-chg})

        (and vol-ratio (> vol-ratio volume-spike-ratio))
        (conj {:xt/id                  (random-uuid)
               :candidate/ticker        ticker
               :candidate/scanned-at    now
               :candidate/trigger       :volume-spike
               :candidate/volume-ratio  vol-ratio})))))

(defn run-scanner
  "Scans watchlist for price-change (>1.5%) and volume-spike (>2× 20-day avg).
   Writes :candidate entities to XTDB. Returns the total count saved."
  [ctx]
  (let [node (:biff.xtdb/node ctx)]
    (reduce
     (fn [total ticker]
       (try
         (let [resp       (alpaca/get-bars ticker {:limit 30})
               bars       (sort-by :t (:bars resp))
               candidates (candidates-for ticker bars)]
           (if (seq candidates)
             (do
               (doseq [c candidates] (schema/validate! :candidate c))
               (xt/await-tx node (xt/submit-tx node (mapv #(vector ::xt/put %) candidates)))
               (println (str "Scanner: " ticker " → " (count candidates) " candidate(s)"))
               (+ total (count candidates)))
             (do
               (println (str "Scanner: " ticker " → no trigger"))
               total)))
         (catch Exception e
           (println (str "Scanner error [" ticker "]: " (.getMessage e)))
           total)))
     0
     watchlist)))

(comment
  (require '[repl :refer [get-context]]
           '[xtdb.api :as xt])

  (def ctx (get-context))

  ;; Run one full scan cycle
  (run-scanner ctx)

  ;; Query all candidates in XTDB
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull c [*])]
          :where [[c :candidate/ticker]]})

  ;; Inspect raw bars for one ticker
  (com.robotfund.alpaca/get-bars "AAPL" {:limit 5}))
