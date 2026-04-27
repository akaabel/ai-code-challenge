(ns com.robotfund.alpaca
  (:require [cheshire.core :as json]
            [clj-http.client :as http]))

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

(defn- post* [base path body]
  (-> (http/post (str base path)
                 {:headers      (merge (auth-headers) {"content-type" "application/json"})
                  :body         (json/encode body)
                  :as           :json})
      :body))

(defn place-order
  "Places a market day order. side is \"buy\" or \"sell\".
   Returns the Alpaca order map."
  [symbol qty side]
  (post* trading-url "/orders"
         {:symbol        symbol
          :qty           (str qty)
          :side          side
          :type          "market"
          :time_in_force "day"}))

(defn get-order [alpaca-id]
  (get* trading-url (str "/orders/" alpaca-id) {}))

(defn cancel-order [alpaca-id]
  (http/delete (str trading-url "/orders/" alpaca-id)
               {:headers           (auth-headers)
                :throw-exceptions  false}))

(defn get-open-orders
  "Returns all currently open (not yet filled/cancelled) orders."
  []
  (get* trading-url "/orders" {:status "open"}))

(defn get-fill-activities
  "Returns all FILL activity records for the account.
   Each record has :order_id, :symbol, :side, :qty, :price, :transaction_time."
  []
  (get* trading-url "/account/activities/FILL" {}))

(defn get-movers
  "Returns top gainers and losers for US equities.
   opts: :top (default 20, max 25).
   Response: {:gainers [{:symbol :price :change :percent_change}] :losers [...]}"
  [{:keys [top] :or {top 20}}]
  (get* data-v1beta1 "/screener/stocks/movers" {:top top}))

(defn get-most-actives
  "Returns most actively traded US equities by volume.
   opts: :top (default 20, max 25).
   Response: {:most_actives [{:symbol :volume :trade_count}]}"
  [{:keys [top] :or {top 20}}]
  (get* data-v1beta1 "/screener/stocks/most-actives" {:top top}))

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
