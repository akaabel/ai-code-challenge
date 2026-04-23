(ns com.robotfund.dashboard
  (:require [com.biffweb :as biff :refer [q]]
            [com.robotfund.alpaca :as alpaca]
            [com.robotfund.ui :as ui]
            [clojure.tools.logging :as log]
            [xtdb.api :as xt])
  (:import (java.time ZoneId)
           (java.time.format DateTimeFormatter)))

(def ^:private et-zone (ZoneId/of "America/New_York"))
(def ^:private ts-fmt  (DateTimeFormatter/ofPattern "MMM d HH:mm:ss"))

(defn- fmt-time [^java.util.Date d]
  (when d (-> d .toInstant (.atZone et-zone) (.format ts-fmt))))

(defn- parse-d [s] (when s (Double/parseDouble (str s))))

(defn- fmt-usd [n]
  (when n (format "%s$%,.0f" (if (neg? n) "-" "") (Math/abs n))))

(defn- fmt-signed [n]
  (when n (format "%s$%,.0f" (if (neg? n) "-" "+") (Math/abs n))))

(defn- fmt-pct [n]
  (when n (format "%.2f%%" (* 100 n))))

(defn- pnl-class [n]
  (when n
    (cond (pos? n) "text-green-600"
          (neg? n) "text-red-600"
          :else    "")))

(defn- truncate [s n]
  (let [s (or s "")]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- dash-page [ctx & body]
  (ui/base ctx [:div.p-4.mx-auto.max-w-screen-lg body]))

(defn- nav [active]
  [:nav.flex.items-center.justify-between.mb-6.pb-3.border-b.border-gray-200
   [:h1.text-xl.font-bold "Robot Fund"]
   [:div.flex.gap-4
    [:a {:href  "/"
         :class (if (= active :portfolio)
                  "font-semibold text-blue-600"
                  "text-gray-600 hover:text-blue-500")}
     "Portfolio"]
    [:a {:href  "/timeline"
         :class (if (= active :timeline)
                  "font-semibold text-blue-600"
                  "text-gray-600 hover:text-blue-500")}
     "Timeline"]]])

(defn- stat-card [label value value-class]
  [:div.bg-white.rounded.border.border-gray-200.p-4
   [:div.text-xs.text-gray-500.uppercase.tracking-wide.mb-1 label]
   [:div.text-2xl.font-bold {:class (or value-class "")} (or value "—")]])

(defn- positions-table [positions]
  (if (empty? positions)
    [:p.text-sm.text-gray-500.py-4 "No open positions."]
    [:table.w-full.text-sm
     [:thead
      [:tr.text-left.text-xs.text-gray-500.uppercase.border-b.border-gray-200
       [:th.py-2.pr-6 "Ticker"]
       [:th.py-2.pr-6.text-right "Qty"]
       [:th.py-2.pr-6.text-right "Market Value"]
       [:th.py-2.pr-6.text-right "Unrealized P&L"]
       [:th.py-2.text-right "P&L %"]]]
     [:tbody
      (for [p      positions
            :let [pl   (parse-d (:unrealized_pl p))
                  plpc (parse-d (:unrealized_plpc p))
                  cls  (pnl-class pl)]]
        [:tr.border-b.border-gray-100
         [:td.py-2.pr-6.font-mono.font-semibold (:symbol p)]
         [:td.py-2.pr-6.text-right (:qty p)]
         [:td.py-2.pr-6.text-right (fmt-usd (parse-d (:market_value p)))]
         [:td.py-2.pr-6.text-right {:class cls} (fmt-signed pl)]
         [:td.py-2.text-right {:class cls} (fmt-pct plpc)]])]]))

(defn portfolio-page [{:keys [biff/db biff.xtdb/node] :as ctx}]
  (let [db        (or db (xt/db node))
        account   (try (alpaca/get-account)
                       (catch Exception e
                         (log/warn "Dashboard: Alpaca account error:" (.getMessage e))
                         nil))
        positions (try (alpaca/get-positions)
                       (catch Exception e
                         (log/warn "Dashboard: Alpaca positions error:" (.getMessage e))
                         []))
        last-scan (ffirst (xt/q db '{:find [(max t)] :where [[_ :candidate/scanned-at t]]}))
        equity    (parse-d (:equity account))
        cash      (parse-d (:cash account))
        last-eq   (parse-d (:last_equity account))
        pnl       (when (and equity last-eq) (- equity last-eq))]
    (dash-page ctx
     (nav :portfolio)
     (if (nil? account)
       [:div.p-3.mb-4.bg-red-50.text-red-600.rounded.text-sm
        "Could not fetch account data from Alpaca. Check logs."]
       [:<>
        [:div.grid.grid-cols-2.gap-3.mb-6
         (stat-card "Equity"       (fmt-usd equity)                              "")
         (stat-card "Cash"         (fmt-usd cash)                                "")
         (stat-card "Today's P&L"  (fmt-signed pnl)                (pnl-class pnl))
         (stat-card "Buying Power" (fmt-usd (parse-d (:buying_power account)))   "")]
        [:div.mb-6
         [:h2.text-sm.font-semibold.text-gray-700.mb-2
          (str "Open Positions (" (count positions) ")")]
         (positions-table positions)]
        [:div.text-xs.text-gray-400
         "Last scan: " (or (fmt-time last-scan) "—")]]))))

;; --- Timeline ---

(defn- event-badge [type]
  (case type
    :candidate [:span.inline-block.px-2.rounded.text-xs.font-medium.bg-blue-100.text-blue-700   "SCAN"]
    :news      [:span.inline-block.px-2.rounded.text-xs.font-medium.bg-purple-100.text-purple-700 "NEWS"]
    :analysis  [:span.inline-block.px-2.rounded.text-xs.font-medium.bg-yellow-100.text-yellow-800 "ANALYST"]
    :proposal  [:span.inline-block.px-2.rounded.text-xs.font-medium.bg-orange-100.text-orange-700 "RISK"]
    :order     [:span.inline-block.px-2.rounded.text-xs.font-medium.bg-green-100.text-green-700   "ORDER"]
    :fill      [:span.inline-block.px-2.rounded.text-xs.font-medium.bg-teal-100.text-teal-700     "FILL"]
    nil))

(defn- event-ticker [e]
  (or (:candidate/ticker e) (:news-report/ticker e) (:analysis/ticker e)
      (:trade-proposal/ticker e) (:order/ticker e) (:fill/ticker e)))

(defn- event-detail [e]
  (case (:event/type e)
    :candidate (if (= :price-change (:candidate/trigger e))
                 (format "price %+.1f%%" (* 100 (or (:candidate/price-change-pct e) 0.0)))
                 (format "volume %.1f×"  (or (:candidate/volume-ratio e) 0.0)))
    :news      (str (format "sentiment %+.2f" (double (or (:news-report/sentiment e) 0)))
                    " — " (truncate (:news-report/summary e) 80))
    :analysis  (str "rating " (:analysis/rating e) "/10 → " (name (or (:analysis/action e) :hold)))
    :proposal  (str (name (or (:trade-proposal/decision e) :rejected))
                    (when (= :approved (:trade-proposal/decision e))
                      (str " " (:trade-proposal/quantity e) " sh"))
                    " — " (truncate (:trade-proposal/reason e) 60))
    :order     (str (name (or (:order/side e) :buy)) " "
                    (:order/quantity e) " sh ["
                    (name (or (:order/status e) :pending)) "]")
    :fill      (str (:fill/quantity e) " sh @ $"
                    (format "%.2f" (double (or (:fill/price e) 0.0))))
    ""))

(defn- timeline-events [db]
  (->> (concat
         (map #(assoc % :event/type :candidate :event/time (:candidate/scanned-at %))
              (map first (q db '{:find [(pull e [*])] :where [[e :candidate/ticker]]})))
         (map #(assoc % :event/type :news :event/time (:news-report/reported-at %))
              (map first (q db '{:find [(pull e [*])] :where [[e :news-report/ticker]]})))
         (map #(assoc % :event/type :analysis :event/time (:analysis/analyzed-at %))
              (map first (q db '{:find [(pull e [*])] :where [[e :analysis/ticker]]})))
         (map #(assoc % :event/type :proposal :event/time (:trade-proposal/proposed-at %))
              (map first (q db '{:find [(pull e [*])] :where [[e :trade-proposal/ticker]]})))
         (map #(assoc % :event/type :order :event/time (:order/placed-at %))
              (map first (q db '{:find [(pull e [*])] :where [[e :order/ticker]]})))
         (map #(assoc % :event/type :fill :event/time (:fill/filled-at %))
              (map first (q db '{:find [(pull e [*])] :where [[e :fill/ticker]]}))))
       (remove #(nil? (:event/time %)))
       (sort-by :event/time #(compare %2 %1))
       (take 100)))

(defn timeline-page [{:keys [biff/db biff.xtdb/node] :as ctx}]
  (let [db     (or db (xt/db node))
        events (timeline-events db)]
    (dash-page ctx
     (nav :timeline)
     [:div.flex.items-center.justify-between.mb-3
      [:h2.text-sm.font-semibold.text-gray-700 "Agent Activity"]
      [:span.text-xs.text-gray-400 "Last 100 events · newest first · refresh to update"]]
     (if (empty? events)
       [:p.text-sm.text-gray-500.py-4
        "No agent activity yet. Run the pipeline to see events here."]
       [:table.w-full.text-sm
        [:thead
         [:tr.text-left.text-xs.text-gray-500.uppercase.border-b.border-gray-200
          [:th.py-2.pr-6 "Time (ET)"]
          [:th.py-2.pr-6 "Agent"]
          [:th.py-2.pr-6 "Ticker"]
          [:th.py-2 "Detail"]]]
        [:tbody
         (for [e events]
           [:tr.border-b.border-gray-100
            [:td.py-2.pr-6.text-xs.font-mono.text-gray-400 (fmt-time (:event/time e))]
            [:td.py-2.pr-6 (event-badge (:event/type e))]
            [:td.py-2.pr-6.font-mono.font-semibold (event-ticker e)]
            [:td.py-2.text-gray-600.text-xs (event-detail e)]])]]))))

(def module
  {:routes [["/"         {:get portfolio-page}]
            ["/timeline" {:get timeline-page}]]})
