(ns com.robotfund.dashboard
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [com.biffweb :as biff :refer [q]]
            [com.robotfund.alpaca :as alpaca]
            [com.robotfund.agents.risk :as risk]
            [com.robotfund.schedule :as schedule]
            [com.robotfund.schema :as schema]
            [com.robotfund.ui :as ui]
            [rum.core :as rum]
            [xtdb.api :as xt])
  (:import (java.time ZoneId)
           (java.time.format DateTimeFormatter)))

;; --- Formatting helpers ---

(def ^:private et-zone   (ZoneId/of "America/New_York"))
(def ^:private oslo-zone (ZoneId/of "Europe/Oslo"))
(def ^:private ts-fmt    (DateTimeFormatter/ofPattern "MMM d HH:mm:ss"))
(def ^:private dt-fmt    (DateTimeFormatter/ofPattern "MMM d HH:mm"))

(defn- fmt-time [^java.util.Date d]
  (when d (-> d .toInstant (.atZone et-zone) (.format ts-fmt))))

(defn- fmt-et   [^java.util.Date d] (when d (-> d .toInstant (.atZone et-zone)   (.format dt-fmt))))
(defn- fmt-oslo [^java.util.Date d] (when d (-> d .toInstant (.atZone oslo-zone) (.format dt-fmt))))

(defn- next-agent-fire
  "Next market-hours fire time: last-ran + 15 min (or now if that's in the past)."
  [last-ran]
  (let [now   (java.util.Date.)
        start (if last-ran
                (let [c (biff/add-seconds last-ran (* 15 60))]
                  (if (.before c now) now c))
                now)]
    (first (filter schedule/market-hours?
                   (iterate #(biff/add-seconds % (* 15 60)) start)))))

(defn- time-until [^java.util.Date d]
  (let [delta (- (.getTime d) (System/currentTimeMillis))
        mins  (int (Math/floor (/ delta 60000)))]
    (cond
      (neg? delta) "overdue"
      (< mins 1)   "< 1 min"
      (< mins 60)  (str mins " min")
      :else        (str (int (/ mins 60)) "h " (mod mins 60) "m"))))

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

;; --- Layout ---

(defn- dash-page [ctx & body]
  (ui/base ctx
   [:div.p-4.mx-auto.max-w-screen-lg body]))

(defn- nav [active]
  [:nav.flex.items-center.justify-between.mb-6.pb-3.border-b.border-gray-200
   [:h1.text-xl.font-bold "Robot Fund"]
   [:div.flex.gap-4
    [:a {:href  "/"
         :class (if (= active :portfolio) "font-semibold text-blue-600" "text-gray-600 hover:text-blue-500")}
     "Portfolio"]
    [:a {:href  "/trades"
         :class (if (= active :trades) "font-semibold text-blue-600" "text-gray-600 hover:text-blue-500")}
     "Trades"]
    [:a {:href  "/timeline"
         :class (if (= active :timeline) "font-semibold text-blue-600" "text-gray-600 hover:text-blue-500")}
     "Timeline"]
    [:a {:href  "/query"
         :class (if (= active :query) "font-semibold text-blue-600" "text-gray-600 hover:text-blue-500")}
     "Query"]]])

;; --- Portfolio ---

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

;; --- Agent schedule widget ---

(defn- schedule-row [agent-name last-ran]
  (let [now       (java.util.Date.)
        next-fire (next-agent-fire last-ran)
        market?   (schedule/market-hours? now)
        overdue?  (and market? (.before next-fire now))
        dot       (cond overdue? "bg-red-500"
                        market?  "bg-green-500"
                        :else    "bg-gray-400")]
    [:tr.border-b.border-gray-100
     [:td.py-2.pr-3
      [:span.inline-block.w-2.h-2.rounded-full {:class dot}]]
     [:td.py-2.pr-6.text-sm.font-medium agent-name]
     [:td.py-2.pr-8.text-xs.text-gray-500
      (if last-ran
        [:<>
         [:div (fmt-oslo last-ran) " CET"]
         [:div.text-gray-400 (fmt-et last-ran) " ET"]]
        [:span.text-gray-300 "never"])]
     [:td.py-2.text-xs.text-gray-500
      [:<>
       [:div (fmt-oslo next-fire) " CET"]
       [:div.text-gray-400 (fmt-et next-fire) " ET"]
       [:div.font-medium.mt-0.5
        {:class (if overdue? "text-red-500" "text-blue-400")}
        (time-until next-fire)]]]]))

(defn- agent-last-ran [db agent-name]
  (:agent-run/ran-at (xt/entity db (keyword "agent-run" agent-name))))

(defn schedule-rows [{:keys [biff/db biff.xtdb/node] :as _ctx}]
  (let [db (or db (xt/db node))
        agents [["Scanner"  (agent-last-ran db "scanner")]
                ["News"     (agent-last-ran db "news")]
                ["Analyst"  (agent-last-ran db "analyst")]
                ["Risk"     (agent-last-ran db "risk")]
                ["Executor" (agent-last-ran db "executor")]]]
    {:status  200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body    (rum/render-static-markup
               [:<> (map (fn [[n t]] (schedule-row n t)) agents)])}))

(defn- schedule-widget [db]
  (let [agents [["Scanner"  (agent-last-ran db "scanner")]
                ["News"     (agent-last-ran db "news")]
                ["Analyst"  (agent-last-ran db "analyst")]
                ["Risk"     (agent-last-ran db "risk")]
                ["Executor" (agent-last-ran db "executor")]]]
    [:div.bg-white.rounded.border.border-gray-200.p-4
     [:div.flex.items-center.justify-between.mb-3
      [:div.text-xs.text-gray-500.uppercase.tracking-wide "Agent Schedule"]
      [:span.text-xs.text-gray-400 "Refreshes every 10s"]]
     [:table.w-full
      [:thead
       [:tr.text-left.text-xs.text-gray-400.uppercase.border-b.border-gray-200
        [:th.py-1.pr-3]
        [:th.py-1.pr-6 "Agent"]
        [:th.py-1.pr-8 "Last Ran"]
        [:th.py-1 "Next Fire"]]]
      [:tbody
       {:id "schedule-rows"
        :hx-get "/schedule/rows"
        :hx-trigger "load, every 10s"
        :hx-swap "innerHTML"}
       (map (fn [[n t]] (schedule-row n t)) agents)]]]))

;; --- Settings widget ---

(defn- settings-widget [settings]
  (let [enabled (:settings/max-trades-enabled settings)
        max-n   (:settings/max-trades-per-day settings)]
    [:div#risk-settings.bg-white.rounded.border.border-gray-200.p-4
     [:div.text-xs.text-gray-500.uppercase.tracking-wide.mb-3 "Risk Settings"]
     (biff/form
      {:action  "/settings/max-trades"
       :hx-post "/settings/max-trades"
       :hx-target "#risk-settings"
       :hx-swap "outerHTML"}
      [:div.flex.items-center.gap-4.flex-wrap
       [:label.flex.items-center.gap-2.text-sm.text-gray-700.cursor-pointer
        [:input.rounded.border-gray-300
         (cond-> {:type "checkbox" :name "enabled" :value "true"}
           enabled (assoc :checked true))]
        "Limit max trades per day"]
       [:div.flex.items-center.gap-2
        [:span.text-sm.text-gray-500 "Max:"]
        [:input.w-20.rounded.border.border-gray-300.px-2.py-1.text-sm.text-right
         {:type "number" :name "max-trades" :min "1" :max "100" :value (str max-n)}]]
       [:button.bg-blue-500.text-white.text-sm.px-3.py-1.rounded
        {:type "submit"} "Save"]])]))

(defn save-max-trades [{:keys [biff.xtdb/node params] :as _ctx}]
  (let [enabled  (= "true" (:enabled params))
        max-n    (max 1 (try (Integer/parseInt (str (:max-trades params)))
                             (catch Exception _ 5)))
        settings (merge risk/default-settings
                        {:xt/id                       :robotfund/settings
                         :settings/max-trades-enabled enabled
                         :settings/max-trades-per-day max-n})]
    (schema/validate! :settings settings)
    (xt/await-tx node (xt/submit-tx node [[::xt/put settings]]))
    {:status  200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body    (rum/render-static-markup (settings-widget settings))}))

;; --- Portfolio ---

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
        settings  (risk/load-settings db)
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
         (stat-card "Equity"       (fmt-usd equity)                            "")
         (stat-card "Cash"         (fmt-usd cash)                              "")
         (stat-card "Today's P&L"  (fmt-signed pnl)             (pnl-class pnl))
         (stat-card "Buying Power" (fmt-usd (parse-d (:buying_power account))) "")]
        [:div.mb-6
         [:h2.text-sm.font-semibold.text-gray-700.mb-2
          (str "Open Positions (" (count positions) ")")]
         (positions-table positions)]
        [:div.mb-6 (schedule-widget db)]
        [:div.mb-6 (settings-widget settings)]]))))

;; --- Timeline ---

(defn- event-badge [type]
  (case type
    :candidate [:span.inline-block.px-2.rounded.text-xs.font-medium.bg-blue-100.text-blue-700    "SCAN"]
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
    :candidate (case (:candidate/trigger e)
                 :price-change (format "price %+.1f%%" (* 100 (or (:candidate/price-change-pct e) 0.0)))
                 :volume-spike (format "volume %.1f×"  (or (:candidate/volume-ratio e) 0.0))
                 :held         "held position — reviewed for sell"
                 "unknown trigger")
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

(defn- event-row [e]
  (let [trade-link (when (#{:proposal :order} (:event/type e))
                     (let [pid (case (:event/type e)
                                 :proposal (:xt/id e)
                                 :order    (:order/proposal-id e))]
                       [:a.ml-2.text-blue-500 {:href (str "/trade/" pid)} "view →"]))]
    [:tr.border-b.border-gray-100
     [:td.py-2.pr-6.text-xs.font-mono.text-gray-400 (fmt-time (:event/time e))]
     [:td.py-2.pr-6 (event-badge (:event/type e))]
     [:td.py-2.pr-6.font-mono.font-semibold (event-ticker e)]
     [:td.py-2.text-gray-600.text-xs (event-detail e) trade-link]]))

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

(defn timeline-rows [{:keys [biff/db biff.xtdb/node] :as _ctx}]
  (let [db     (or db (xt/db node))
        events (timeline-events db)]
    {:status  200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body    (rum/render-static-markup
               (if (empty? events)
                 [:tr [:td.py-4.text-sm.text-gray-500.text-center {:col-span "4"}
                       "No agent activity yet."]]
                 [:<> (map event-row events)]))}))

(defn timeline-page [{:keys [biff/db biff.xtdb/node] :as ctx}]
  (let [db     (or db (xt/db node))
        events (timeline-events db)]
    (dash-page ctx
     (nav :timeline)
     [:div.flex.items-center.justify-between.mb-3
      [:h2.text-sm.font-semibold.text-gray-700 "Agent Activity"]
      [:span.text-xs.text-gray-400 "Refreshes every 3s · last 100 events · newest first"]]
     [:table.w-full.text-sm
      [:thead
       [:tr.text-left.text-xs.text-gray-500.uppercase.border-b.border-gray-200
        [:th.py-2.pr-6 "Time (ET)"]
        [:th.py-2.pr-6 "Agent"]
        [:th.py-2.pr-6 "Ticker"]
        [:th.py-2 "Detail"]]]
      [:tbody#timeline-events
       {:hx-get "/timeline/rows" :hx-trigger "load, every 3s" :hx-swap "innerHTML"}
       (if (empty? events)
         [:tr [:td.py-4.text-sm.text-gray-500.text-center {:col-span "4"}
               "No agent activity yet. Run the pipeline to see events here."]]
         (map event-row events))]])))

;; --- Trade drilldown ---

(defn- chain-card [& body]
  [:div.rounded.border.border-gray-200.p-4.mb-3 body])

(defn- llm-details [llm]
  (when llm
    [:details.mt-3
     [:summary.text-xs.text-gray-500.cursor-pointer
      (str (:llm-call/model llm)
           " · " (:llm-call/input-tokens llm) "→" (:llm-call/output-tokens llm) " tokens"
           " · " (:llm-call/latency-ms llm) "ms — click to expand")]
     [:div.mt-2.rounded.bg-gray-50.p-3.text-xs.font-mono
      [:div.text-gray-400.uppercase.mb-1 "Prompt"]
      [:pre.whitespace-pre-wrap.break-words (:llm-call/prompt llm)]
      [:div.text-gray-400.uppercase.mt-3.mb-1 "Response"]
      [:pre.whitespace-pre-wrap.break-words (:llm-call/response llm)]]]))

(defn- candidate-card [c]
  (chain-card
   [:div.flex.items-center.gap-2.mb-1
    (event-badge :candidate)
    [:span.font-semibold (str "1. Scanner — " (:candidate/ticker c))]]
   [:div.text-sm.text-gray-700
    (case (:candidate/trigger c)
      :price-change (str "Price change: " (format "%+.1f%%" (* 100 (or (:candidate/price-change-pct c) 0.0))))
      :volume-spike (str "Volume spike: " (format "%.1f×" (or (:candidate/volume-ratio c) 0.0)))
      :held         "Held position — reviewed for sell"
      "Unknown trigger")
    [:span.text-gray-400.ml-2 (fmt-time (:candidate/scanned-at c))]]))

(defn- news-card [n llm]
  (chain-card
   [:div.flex.items-center.gap-2.mb-1
    (event-badge :news)
    [:span.font-semibold (str "2. News Agent — " (:news-report/ticker n))]]
   [:div.text-sm.text-gray-700
    (str "Sentiment: " (format "%+.2f" (double (or (:news-report/sentiment n) 0.0))))
    [:span.text-gray-400.ml-2 (fmt-time (:news-report/reported-at n))]]
   [:div.text-sm.text-gray-600.mt-1 (truncate (:news-report/summary n) 200)]
   (when (seq (:news-report/headlines n))
     [:ul.mt-2.text-xs.text-gray-500
      (for [h (take 3 (:news-report/headlines n))]
        [:li "• " h])])
   (llm-details llm)))

(defn- analysis-card [a llm]
  (chain-card
   [:div.flex.items-center.gap-2.mb-1
    (event-badge :analysis)
    [:span.font-semibold (str "3. Analyst — " (:analysis/ticker a))]]
   [:div.text-sm.text-gray-700
    (str "Rating: " (:analysis/rating a) "/10 · Action: " (name (or (:analysis/action a) :hold)))
    [:span.text-gray-400.ml-2 (fmt-time (:analysis/analyzed-at a))]]
   [:div.text-sm.text-gray-600.mt-1 (truncate (:analysis/reasoning a) 300)]
   (llm-details llm)))

(defn- proposal-card [p]
  (let [d (:trade-proposal/decision p)]
    (chain-card
     [:div.flex.items-center.gap-2.mb-1
      (event-badge :proposal)
      [:span.font-semibold (str "4. Risk Manager — " (:trade-proposal/ticker p))]]
     [:div.text-sm
      [:span {:class (case d
                       :approved "text-green-600 font-semibold"
                       :rejected "text-red-600 font-semibold"
                       "text-yellow-600 font-semibold")}
       (string/upper-case (name (or d :unknown)))]
      (when (:trade-proposal/quantity p) (str " · " (:trade-proposal/quantity p) " shares"))
      (str " · " (name (or (:trade-proposal/action p) :hold)))
      [:span.text-gray-400.ml-2 (fmt-time (:trade-proposal/proposed-at p))]]
     [:div.text-sm.text-gray-600.mt-1 (:trade-proposal/reason p)])))

(defn- order-card [o]
  (chain-card
   [:div.flex.items-center.gap-2.mb-1
    (event-badge :order)
    [:span.font-semibold (str "5. Executor — " (:order/ticker o))]]
   [:div.text-sm.text-gray-700
    (str (name (or (:order/side o) :buy)) " " (:order/quantity o) " shares"
         " · " (name (or (:order/status o) :pending)))
    [:span.text-gray-400.ml-2 (fmt-time (:order/placed-at o))]]))

(defn- fill-card [f]
  (chain-card
   [:div.flex.items-center.gap-2.mb-1
    (event-badge :fill)
    [:span.font-semibold (str "6. Fill — " (:fill/ticker f))]]
   [:div.text-sm.text-gray-700
    (str (:fill/quantity f) " shares @ $" (format "%.2f" (double (or (:fill/price f) 0.0))))
    [:span.text-gray-400.ml-2 (fmt-time (:fill/filled-at f))]]))

(defn trade-page [{:keys [biff/db biff.xtdb/node path-params] :as ctx}]
  (let [db          (or db (xt/db node))
        proposal-id (try (java.util.UUID/fromString (:id path-params))
                         (catch Exception _ nil))
        proposal    (when proposal-id (xt/entity db proposal-id))]
    (if (nil? proposal)
      {:status 404 :headers {"content-type" "text/plain"} :body "Trade not found"}
      (let [analysis  (xt/entity db (:trade-proposal/analysis-id proposal))
            news      (when analysis (xt/entity db (:analysis/news-report-id analysis)))
            candidate (when analysis (xt/entity db (:analysis/candidate-id analysis)))
            news-llm  (when news     (xt/entity db (:news-report/llm-call-id news)))
            anal-llm  (when analysis (xt/entity db (:analysis/llm-call-id analysis)))
            order     (ffirst (q db '{:find [(pull o [*])]
                                      :in [pid]
                                      :where [[o :order/proposal-id pid]]}
                                 proposal-id))
            fill      (when order
                        (ffirst (q db '{:find [(pull f [*])]
                                        :in [oid]
                                        :where [[f :fill/order-id oid]]}
                                   (:xt/id order))))]
        (dash-page ctx
         (nav nil)
         [:div.mb-4
          [:a.text-sm.text-blue-500 {:href "/timeline"} "← Back to Timeline"]
          [:span.text-sm.text-gray-400.ml-3
           "Trade chain for " [:span.font-mono.font-semibold (:trade-proposal/ticker proposal)]]]
         (when candidate  (candidate-card candidate))
         (when news       (news-card news news-llm))
         (when analysis   (analysis-card analysis anal-llm))
         (proposal-card proposal)
         (if order
           [:<>
            (order-card order)
            (when fill (fill-card fill))]
           [:div.rounded.border.border-dashed.border-gray-300.p-4.text-sm.text-gray-400
            "No order placed (proposal rejected or executor not yet run)"]))))))

;; --- Trades page ---

(defn- fills-with-side [db]
  (->> (q db '{:find [(pull f [*])] :where [[f :fill/ticker]]})
       (map first)
       (map (fn [fill]
              (let [order (xt/entity db (:fill/order-id fill))]
                (assoc fill :fill/side (:order/side order)))))
       (sort-by :fill/filled-at #(compare %2 %1))))

(defn- avg-cost-by-ticker
  "Average cost per share for each ticker, computed from all buy fills."
  [fills]
  (->> fills
       (filter #(= :buy (:fill/side %)))
       (group-by :fill/ticker)
       (into {} (map (fn [[ticker buys]]
                       (let [total-qty  (reduce + 0 (map :fill/quantity buys))
                             total-cost (reduce + 0.0 (map #(* (:fill/quantity %) (double (:fill/price %))) buys))]
                         [ticker (if (pos? total-qty) (/ total-cost total-qty) 0.0)]))))))

(defn- trade-row [fill avg-cost]
  (let [side    (:fill/side fill)
        qty     (:fill/quantity fill)
        price   (double (:fill/price fill))
        value   (* qty price)
        cost    (get avg-cost (:fill/ticker fill) price)
        pnl     (when (= side :sell) (* qty (- price (double cost))))
        pnl-cls (when pnl (pnl-class pnl))]
    [:tr.border-b.border-gray-100
     [:td.py-2.pr-6.text-xs.font-mono.text-gray-400 (fmt-time (:fill/filled-at fill))]
     [:td.py-2.pr-6.font-mono.font-semibold (:fill/ticker fill)]
     [:td.py-2.pr-6
      [:span.inline-block.px-2.rounded.text-xs.font-medium
       {:class (if (= side :buy) "bg-green-100 text-green-700" "bg-red-100 text-red-700")}
       (string/upper-case (name side))]]
     [:td.py-2.pr-6.text-right (str qty)]
     [:td.py-2.pr-6.text-right (format "$%.2f" price)]
     [:td.py-2.pr-6.text-right (fmt-usd value)]
     [:td.py-2.text-right {:class (or pnl-cls "text-gray-400")}
      (if pnl (fmt-signed pnl) "—")]]))

(defn trades-rows [{:keys [biff/db biff.xtdb/node] :as _ctx}]
  (let [db    (or db (xt/db node))
        fills (fills-with-side db)
        costs (avg-cost-by-ticker fills)]
    {:status  200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body    (rum/render-static-markup
               (if (empty? fills)
                 [:tr [:td.py-4.text-sm.text-gray-500.text-center {:col-span "7"}
                       "No trades executed yet."]]
                 [:<> (map #(trade-row % costs) fills)]))}))

(defn trades-page [{:keys [biff/db biff.xtdb/node] :as ctx}]
  (let [db        (or db (xt/db node))
        positions (try (alpaca/get-positions)
                       (catch Exception e
                         (log/warn "Trades: Alpaca positions error:" (.getMessage e))
                         []))
        fills     (fills-with-side db)
        costs     (avg-cost-by-ticker fills)]
    (dash-page ctx
     (nav :trades)
     [:div.mb-8
      [:h2.text-sm.font-semibold.text-gray-700.mb-2
       (str "Open Positions (" (count positions) ")")]
      (positions-table positions)]
     [:div.flex.items-center.justify-between.mb-3
      [:h2.text-sm.font-semibold.text-gray-700 "Trade History"]
      [:span.text-xs.text-gray-400 "Refreshes every 5s · P&L on sells uses average cost basis"]]
     [:table.w-full.text-sm
      [:thead
       [:tr.text-left.text-xs.text-gray-500.uppercase.border-b.border-gray-200
        [:th.py-2.pr-6 "Time (ET)"]
        [:th.py-2.pr-6 "Ticker"]
        [:th.py-2.pr-6 "Side"]
        [:th.py-2.pr-6.text-right "Qty"]
        [:th.py-2.pr-6.text-right "Price"]
        [:th.py-2.pr-6.text-right "Value"]
        [:th.py-2.text-right "P&L"]]]
      [:tbody#trades-rows
       {:hx-get "/trades/rows" :hx-trigger "load, every 5s" :hx-swap "innerHTML"}
       (if (empty? fills)
         [:tr [:td.py-4.text-sm.text-gray-500.text-center {:col-span "7"}
               "No trades executed yet."]]
         [:<> (map #(trade-row % costs) fills)])]])))

;; --- Query page ---

(def ^:private query-examples
  [["Candidate tickers (simple)"
    "{:find [ticker]\n :where [[e :candidate/ticker ticker]]}"]
   ["All candidates"
    "{:find [(pull e [:xt/id :candidate/ticker :candidate/trigger\n              :candidate/price-change-pct :candidate/volume-ratio\n              :candidate/scanned-at])]\n :where [[e :candidate/ticker]]}"]
   ["Analyses — rating ≥ 7"
    "{:find [(pull e [:xt/id :analysis/ticker :analysis/rating\n              :analysis/action :analysis/reasoning\n              :analysis/analyzed-at])]\n :where [[e :analysis/rating r] [(>= r 7)]]}"]
   ["Approved proposals"
    "{:find [(pull p [:xt/id :trade-proposal/ticker :trade-proposal/action\n              :trade-proposal/quantity :trade-proposal/decision\n              :trade-proposal/reason :trade-proposal/proposed-at])]\n :where [[p :trade-proposal/decision :approved]]}"]
   ["Resized proposals"
    "{:find [(pull p [:xt/id :trade-proposal/ticker :trade-proposal/quantity\n              :trade-proposal/reason :trade-proposal/proposed-at])]\n :where [[p :trade-proposal/decision :resized]]}"]
   ["All orders"
    "{:find [(pull o [:xt/id :order/ticker :order/side :order/quantity\n              :order/status :order/placed-at])]\n :where [[o :order/ticker]]}"]
   ["All fills"
    "{:find [(pull f [:xt/id :fill/ticker :fill/quantity :fill/price :fill/filled-at])]\n :where [[f :fill/ticker]]}"]
   ["All LLM calls"
    "{:find [(pull l [:xt/id :llm-call/model :llm-call/input-tokens\n              :llm-call/output-tokens :llm-call/latency-ms\n              :llm-call/called-at])]\n :where [[l :llm-call/model]]}"]
   ["News — negative sentiment"
    "{:find [(pull n [:xt/id :news-report/ticker :news-report/sentiment\n              :news-report/summary :news-report/reported-at])]\n :where [[n :news-report/sentiment s] [(< s 0)]]}"]
   ["Pipeline join: ticker + action + decision"
    "{:find [ticker action decision]\n :where [[c :candidate/ticker ticker]\n         [a :analysis/candidate-id (:xt/id c)]\n         [a :analysis/action action]\n         [p :trade-proposal/analysis-id (:xt/id a)]\n         [p :trade-proposal/decision decision]]}"]
   ["Settings document"
    "{:find [(pull s [:xt/id :settings/max-trades-enabled :settings/max-trades-per-day])]\n :where [[s :settings/max-trades-per-day]]}"]])

(defn run-query [{:keys [biff/db biff.xtdb/node params] :as _ctx}]
  (let [db     (or db (xt/db node))
        raw    (str (:query params))
        result (try
                 (let [parsed (edn/read-string raw)
                       rows   (vec (q db parsed))
                       sw     (java.io.StringWriter.)]
                   (pprint/pprint rows sw)
                   {:ok true :text (.toString sw) :count (count rows)})
                 (catch Exception e
                   (log/error e "Query page: error executing query")
                   {:ok false :text (str (or (.getMessage e) (.getSimpleName (class e)))
                                         "\n\nQuery was:\n" raw)}))]
    {:status  200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body    (rum/render-static-markup
               (if (:ok result)
                 [:div#query-result
                  [:div.text-xs.text-gray-400.mb-1 (str (:count result) " row(s)")]
                  [:pre.overflow-auto.text-xs.font-mono.bg-gray-50.rounded.border.border-gray-200.p-3
                   {:style {:max-height "60vh"}} (:text result)]]
                 [:div#query-result
                  [:div.p-3.bg-red-50.text-red-700.rounded.text-xs.font-mono (:text result)]]))}))

(defn query-page [{:keys [biff/db biff.xtdb/node] :as ctx}]
  (let [_db (or db (xt/db node))]
    (dash-page ctx
     (nav :query)
     [:div.mb-2
      [:h2.text-sm.font-semibold.text-gray-700 "XTDB Query Playground"]]
     [:div.mb-4
      [:label.text-xs.text-gray-500.block.mb-1 "Example queries"]
      [:select#query-select.w-full.rounded.border.border-gray-300.px-3.py-2.text-sm
       {:_ "on change set #query-input.value to my value then set my value to ''"}
       [:option {:value ""} "— pick an example —"]
       (for [[label q-str] query-examples]
         [:option {:value q-str} label])]]
     (biff/form
      {:action  "/query/run"
       :hx-post "/query/run"
       :hx-target "#query-result"
       :hx-swap "outerHTML"}
      [:div.mb-3
       [:label.text-xs.text-gray-500.block.mb-1 "Datalog query (EDN)"]
       [:textarea#query-input.w-full.rounded.border.border-gray-300.px-3.py-2.text-sm.font-mono
        {:name "query" :rows "6"
         :placeholder "{:find [(pull e [*])] :where [[e :candidate/ticker]]}"}]]
      [:button.bg-blue-500.text-white.text-sm.px-4.py-1.5.rounded
       {:type "submit"} "Run"])
     [:div#query-result])))

(def module
  {:routes [["/"                    {:get  portfolio-page}]
            ["/trades"              {:get  trades-page}]
            ["/trades/rows"         {:get  trades-rows}]
            ["/timeline"            {:get  timeline-page}]
            ["/timeline/rows"       {:get  timeline-rows}]
            ["/trade/:id"           {:get  trade-page}]
            ["/schedule/rows"        {:get  schedule-rows}]
            ["/settings/max-trades" {:post save-max-trades}]
            ["/query"               {:get  query-page}]
            ["/query/run"           {:post run-query}]]})
