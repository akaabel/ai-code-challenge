(ns com.robotfund.agents.risk
  (:require [com.robotfund.alpaca :as alpaca]
            [com.robotfund.schema :as schema]
            [xtdb.api :as xt]))

(def sector-map
  {"AAPL"  :technology
   "MSFT"  :technology
   "NVDA"  :technology
   "GOOGL" :technology
   "AMZN"  :technology
   "META"  :technology
   "TSLA"  :technology
   "JPM"   :financials
   "V"     :financials
   "UNH"   :healthcare})

(def ^:private max-position-pct 0.10)
(def ^:private max-sector-pct   0.30)
(def ^:private min-buy-rating   7)
(def ^:private max-sell-rating  3)

(def default-settings
  {:settings/max-trades-enabled true
   :settings/max-trades-per-day 5})

(defn load-settings [db]
  (or (xt/entity db :robotfund/settings) default-settings))

(defn- start-of-today-et []
  (-> (java.time.ZonedDateTime/now (java.time.ZoneId/of "America/New_York"))
      (.truncatedTo java.time.temporal.ChronoUnit/DAYS)
      .toInstant
      java.util.Date/from))

(defn- unproposed-analyses [db]
  (let [done (set (map first (xt/q db '{:find [a]
                                        :where [[_ :trade-proposal/analysis-id a]]})))
        all  (map first (xt/q db '{:find [(pull a [*])]
                                   :where [[a :analysis/ticker]]}))]
    (remove #(done (:xt/id %)) all)))

(defn- todays-approved-count [db]
  (let [since     (start-of-today-et)
        proposals (map first (xt/q db '{:find [(pull p [:trade-proposal/decision :trade-proposal/proposed-at])]
                                        :where [[p :trade-proposal/decision]]}))]
    (count (filter (fn [p]
                     (and (#{:approved :resized} (:trade-proposal/decision p))
                          (.after (:trade-proposal/proposed-at p) since)))
                   proposals))))

(defn- position-for [positions ticker]
  (first (filter #(= (:symbol %) ticker) positions)))

(defn- parse-d [v]
  (when v (Double/parseDouble (str v))))

(defn- latest-price [ticker]
  (let [resp (alpaca/get-bars ticker {:limit 2 :timeframe "1Day"})
        bars (sort-by :t (:bars resp))]
    (double (:c (last bars)))))

(defn- sector-exposure [positions sector]
  (->> positions
       (filter #(= sector (get sector-map (:symbol %))))
       (map #(or (parse-d (:market_value %)) 0.0))
       (reduce + 0.0)))

(defn- pending-buy-value
  "Estimated cost of open (unfilled) buy orders for ticker.
   Uses qty * price because market orders have no limit_price."
  [open-orders ticker price]
  (->> open-orders
       (filter #(and (= (:symbol %) ticker) (= (:side %) "buy")))
       (map #(* (Long/parseLong (str (or (:qty %) 0))) price))
       (reduce + 0.0)))

(defn- evaluate [analysis account positions open-orders trades-today settings]
  (let [ticker      (:analysis/ticker analysis)
        rating      (:analysis/rating analysis)
        action      (:analysis/action analysis)
        equity      (parse-d (:equity account))
        cash        (parse-d (:cash account))
        pos         (position-for positions ticker)
        max-daily   (:settings/max-trades-per-day settings)
        daily-limit (:settings/max-trades-enabled settings)]
    (cond
      (= action :hold)
      {:decision :rejected :reason "action is hold — no trade required"}

      (and (= action :buy) (< rating min-buy-rating))
      {:decision :rejected :reason (str "rating " rating " below buy threshold " min-buy-rating)}

      (and (= action :buy) daily-limit (>= trades-today max-daily))
      {:decision :rejected :reason (str "daily trade limit of " max-daily " reached")}

      ;; Hard cash floor — never go into margin
      (and (= action :buy) (<= cash 0.0))
      {:decision :rejected :reason (str "cash ≤ $0 (current: $" (format "%.0f" cash) ") — no funds for new buys")}

      (= action :buy)
      (let [price         (latest-price ticker)
            ;; Include unfilled open orders so we don't double-commit capital
            filled-value  (or (parse-d (:market_value pos)) 0.0)
            pending-value (pending-buy-value open-orders ticker price)
            current-value (+ filled-value pending-value)
            max-value     (* equity max-position-pct)
            remaining     (- max-value current-value)
            raw-qty       (long (Math/floor (/ remaining price)))
            sector        (get sector-map ticker :other)
            sec-room      (- (* equity max-sector-pct) (sector-exposure positions sector))
            sec-qty       (long (Math/floor (/ sec-room price)))
            ;; Never spend more than available cash
            cash-qty      (long (Math/floor (/ (max 0.0 cash) price)))
            final-qty     (min raw-qty sec-qty cash-qty)]
        (cond
          (<= remaining 0.0)
          {:decision :rejected
           :reason (str "position at maximum " (int (* 100 max-position-pct)) "% of equity"
                        (when (pos? pending-value)
                          (str " (incl. $" (format "%.0f" pending-value) " pending)")))}

          (<= final-qty 0)
          {:decision :rejected
           :reason (if (zero? cash-qty)
                     (str "cash $" (format "%.0f" cash) " < one share at $" (format "%.2f" price))
                     (str "sector " (name sector) " at or near " (int (* 100 max-sector-pct)) "% cap"))}

          (< final-qty raw-qty)
          {:decision :resized :quantity (int final-qty)
           :reason (str "resized " raw-qty "→" final-qty " shares; "
                        (cond
                          (= final-qty cash-qty) (str "cash limit ($" (format "%.0f" cash) ")")
                          (= final-qty sec-qty)  "sector exposure cap"
                          :else                  "multiple caps"))}

          :else
          {:decision :approved :quantity (int final-qty)
           :reason (str "approved " final-qty " shares at ~$" (format "%.2f" price))}))

      (and (= action :sell) (> rating max-sell-rating))
      {:decision :rejected :reason (str "rating " rating " above sell threshold " max-sell-rating)}

      (and (= action :sell) (nil? pos))
      {:decision :rejected :reason "no position to sell"}

      (= action :sell)
      (let [qty (long (parse-d (:qty_available pos)))]
        (if (<= qty 0)
          {:decision :rejected :reason "no available shares to sell"}
          {:decision :approved :quantity (int qty)
           :reason (str "sell " qty " shares — rating " rating " ≤ " max-sell-rating)}))

      :else
      {:decision :rejected :reason "unrecognised action"})))

(defn- process-analysis [ctx analysis account positions open-orders trades-today-atom settings]
  (let [ticker   (:analysis/ticker analysis)
        action   (:analysis/action analysis)
        ruling   (evaluate analysis account positions open-orders @trades-today-atom settings)
        proposal (cond-> {:xt/id                     (random-uuid)
                          :trade-proposal/analysis-id (:xt/id analysis)
                          :trade-proposal/ticker      ticker
                          :trade-proposal/action      action
                          :trade-proposal/decision    (:decision ruling)
                          :trade-proposal/reason      (:reason ruling)
                          :trade-proposal/proposed-at (java.util.Date.)}
                   (:quantity ruling) (assoc :trade-proposal/quantity (:quantity ruling)))]
    (schema/validate! :trade-proposal proposal)
    (xt/await-tx (:biff.xtdb/node ctx)
                 (xt/submit-tx (:biff.xtdb/node ctx) [[::xt/put proposal]]))
    (when (#{:approved :resized} (:decision ruling))
      (swap! trades-today-atom inc))
    (println (str "Risk: " ticker " " (name action)
                  " → " (name (:decision ruling))
                  " — " (:reason ruling)))
    proposal))

(defn run-risk
  "Evaluates each unproposed :analysis against hard Clojure rules (no LLM).
   Rules: min buy rating 7, max sell rating 3, max 10% per position,
   max 30% per sector, configurable max trades/day, never short.
   Writes :trade-proposal to XTDB. Returns count of proposals written."
  [ctx]
  (let [node         (:biff.xtdb/node ctx)
        db           (xt/db node)
        settings     (load-settings db)
        analyses     (unproposed-analyses db)
        account      (alpaca/get-account)
        positions    (alpaca/get-positions)
        open-orders  (alpaca/get-open-orders)
        trades-today (atom (todays-approved-count db))]
    (println (str "Risk: cash=$" (format "%.0f" (Double/parseDouble (str (:cash account))))
                  "  max-trades-per-day "
                  (if (:settings/max-trades-enabled settings)
                    (str "ENABLED (" (:settings/max-trades-per-day settings) ")")
                    "DISABLED")))
    (reduce
     (fn [total analysis]
       (try
         (process-analysis ctx analysis account positions open-orders trades-today settings)
         (inc total)
         (catch Exception e
           (println (str "Risk error [" (:analysis/ticker analysis) "]: " (.getMessage e)))
           total)))
     0
     analyses)))

(comment
  (require '[repl :refer [get-context]])

  (def ctx (get-context))

  ;; Run one risk cycle
  (run-risk ctx)

  ;; All proposals
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull p [:trade-proposal/ticker :trade-proposal/action
                           :trade-proposal/quantity :trade-proposal/decision
                           :trade-proposal/reason])]
          :where [[p :trade-proposal/ticker]]})

  ;; Approved proposals only
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull p [:trade-proposal/ticker :trade-proposal/action
                           :trade-proposal/quantity :trade-proposal/reason])]
          :where [[p :trade-proposal/decision :approved]]}))
