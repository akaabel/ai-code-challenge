(ns com.robotfund.agents.scanner
  (:require [com.robotfund.alpaca :as alpaca]
            [com.robotfund.schema :as schema]
            [xtdb.api :as xt]))

(def ^:private price-change-threshold 1.5)
(def ^:private volume-spike-ratio     2.0)
(def ^:private min-vol-bars           5)
(def ^:private min-price              5.0)
(def ^:private max-tickers            50)

;; Used when the screener endpoints are unavailable (e.g. outside market hours)
(def ^:private fallback-watchlist
  ["AAPL" "MSFT" "NVDA" "GOOGL" "AMZN" "META" "TSLA" "JPM" "V" "UNH"])

(defn- discover-tickers
  "Fetches top movers and most-active tickers from Alpaca screener.
   Filters out sub-$5 stocks (penny stocks / warrants).
   Falls back to a static watchlist if the screener is unavailable."
  []
  (try
    (let [movers   (alpaca/get-movers {:top 20})
          actives  (alpaca/get-most-actives {:top 20})
          gainers  (->> (:gainers movers)
                        (filter #(>= (double (:price %)) min-price))
                        (map :symbol))
          losers   (->> (:losers movers)
                        (filter #(>= (double (:price %)) min-price))
                        (map :symbol))
          most-act (map :symbol (:most_actives actives))]
      (take max-tickers (distinct (concat gainers losers most-act))))
    (catch Exception e
      (println (str "Scanner: screener unavailable (" (.getMessage e) ") — using fallback watchlist"))
      fallback-watchlist)))

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
      (when (>= last-close min-price)
        (let [price-trigger? (and pct-chg (> (Math/abs pct-chg) price-change-threshold))
              vol-trigger?   (and vol-ratio (> vol-ratio volume-spike-ratio))]
          (when (or price-trigger? vol-trigger?)
            [(cond-> {:xt/id                (random-uuid)
                      :candidate/ticker      ticker
                      :candidate/scanned-at  now
                      :candidate/trigger     (if price-trigger? :price-change :volume-spike)}
               price-trigger? (assoc :candidate/price-change-pct pct-chg)
               vol-trigger?   (assoc :candidate/volume-ratio vol-ratio))]))))))

(defn run-scanner
  "Discovers tickers dynamically via Alpaca's movers + most-actives screener endpoints,
   then checks each for price-change (>1.5%) and volume-spike (>2× 20-day avg) triggers.
   Writes at most one :candidate per ticker per cycle. Returns total count saved."
  [ctx]
  (let [node    (:biff.xtdb/node ctx)
        tickers (discover-tickers)]
    (println (str "Scanner: checking " (count tickers) " tickers from screener"))
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
               (let [c (first candidates)]
                 (println (str "Scanner: " ticker " → candidate"
                               (when (:candidate/price-change-pct c)
                                 (format " price=%+.2f%%" (:candidate/price-change-pct c)))
                               (when (:candidate/volume-ratio c)
                                 (format " vol=%.1f×" (:candidate/volume-ratio c))))))
               (inc total))
             total))
       (catch Exception e
         (println (str "Scanner error [" ticker "]: " (.getMessage e)))
         total)))
     0
     tickers)))

(comment
  (require '[repl :refer [get-context]]
           '[xtdb.api :as xt])

  (def ctx (get-context))

  ;; Preview which tickers the screener surfaces today
  (com.robotfund.agents.scanner/discover-tickers)

  ;; Run one full scan cycle
  (run-scanner ctx)

  ;; Query all candidates in XTDB
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull c [*])]
          :where [[c :candidate/ticker]]}))
