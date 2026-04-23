(ns com.robotfund.alpaca
  (:require [clj-http.client :as http]))

(def ^:private trading-url    "https://paper-api.alpaca.markets/v2")
(def ^:private data-url       "https://data.alpaca.markets/v2")
(def ^:private data-v1beta1   "https://data.alpaca.markets/v1beta1")

(defn- auth-headers []
  {"APCA-API-KEY-ID"     (System/getenv "ALPACA_KEY_ID")
   "APCA-API-SECRET-KEY" (System/getenv "ALPACA_SECRET")})

(defn- get* [base path params]
  (-> (http/get (str base path)
                {:headers      (auth-headers)
                 :query-params params
                 :as           :json})
      :body))

(defn get-account []
  (get* trading-url "/account" {}))

(defn get-positions []
  (get* trading-url "/positions" {}))

(defn- days-ago-str [n]
  (str (.minusSeconds (java.time.Instant/now) (* n 86400))))

(defn get-bars
  "Fetches OHLCV bars for symbol.
   opts: :timeframe (default \"1Day\"), :limit (default 30), :start, :end (ISO-8601 strings).
   Defaults :start to 45 calendar days ago so limit=30 reliably yields ~30 trading-day bars."
  [symbol {:keys [timeframe limit start end]
           :or   {timeframe "1Day" limit 30}}]
  (get* data-url
        (str "/stocks/" symbol "/bars")
        (cond-> {:timeframe timeframe
                 :limit     limit
                 :start     (or start (days-ago-str 45))}
          end (assoc :end end))))

(defn get-news
  "Fetches recent news articles for ticker from Alpaca.
   opts: :limit (default 10, max 50).
   Returns a vector of article maps with :headline, :summary, :created_at, etc."
  [ticker {:keys [limit] :or {limit 10}}]
  (or (:news (get* data-v1beta1 "/news"
                   {:symbols ticker
                    :limit   limit
                    :sort    "desc"}))
      []))

(comment
  ;; Account overview — cash, equity, buying power
  (get-account)

  ;; Current open positions (empty on a fresh paper account)
  (get-positions)

  ;; Last 30 daily bars for AAPL
  (get-bars "AAPL" {})

  ;; Last 5 hourly bars
  (get-bars "MSFT" {:timeframe "1Hour" :limit 5})

  ;; Bars between two dates
  (get-bars "NVDA" {:timeframe "1Day"
                    :start "2026-04-01"
                    :end   "2026-04-21"}))
