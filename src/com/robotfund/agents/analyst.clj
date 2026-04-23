(ns com.robotfund.agents.analyst
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.robotfund.llm :as llm]
            [com.robotfund.schema :as schema]
            [xtdb.api :as xt]))

(defn- unanalysed-pairs
  "Returns [{:candidate ... :news-report ...}] for pairs that have no analysis yet."
  [db]
  (let [done (set (map first (xt/q db '{:find [c]
                                        :where [[_ :analysis/candidate-id c]]})))
        pairs (xt/q db '{:find  [(pull c [*]) (pull r [*])]
                          :where [[c :candidate/ticker]
                                  [r :news-report/candidate-id c]]})]
    (->> pairs
         (remove (fn [[c _]] (done (:xt/id c))))
         (map (fn [[c r]] {:candidate c :news-report r})))))

(defn- analyst-prompt [candidate news-report]
  (let [ticker    (:candidate/ticker candidate)
        trigger   (name (:candidate/trigger candidate))
        pct-chg   (:candidate/price-change-pct candidate)
        vol-ratio (:candidate/volume-ratio candidate)
        sentiment (:news-report/sentiment news-report)
        summary   (:news-report/summary news-report)
        headlines (:news-report/headlines news-report)]
    (str "You are a financial analyst making a short-term trade recommendation.\n\n"
         "Ticker: " ticker "\n"
         "Scanner trigger: " trigger
         (when pct-chg (str " (" (format "%.2f" pct-chg) "% price change)"))
         (when vol-ratio (str " (volume " (format "%.1fx" vol-ratio) " above average)"))
         "\n"
         "News sentiment score: " (format "%.2f" (double sentiment)) " (-1 = bearish, +1 = bullish)\n"
         "News summary: " summary "\n"
         "Headlines:\n"
         (str/join "\n" (map-indexed (fn [i h] (str (inc i) ". " h)) headlines))
         "\n\n"
         "Rate this stock for a short-term trade (today/this week) from 1 to 10, where:\n"
         "1-3 = avoid or sell, 4-6 = neutral/hold, 7-10 = strong buy opportunity.\n\n"
         "Return a JSON object with exactly these three fields:\n"
         "- \"rating\": integer from 1 to 10\n"
         "- \"action\": one of \"buy\", \"sell\", or \"hold\"\n"
         "- \"reasoning\": one or two sentences explaining your recommendation\n\n"
         "Respond with ONLY valid JSON. No markdown, no explanation.")))

(defn- parse-json [text]
  (let [cleaned (-> text
                    str/trim
                    (str/replace #"(?s)```(?:json)?\s*" "")
                    (str/replace #"```" "")
                    str/trim)]
    (json/parse-string cleaned true)))

(defn- coerce-action [raw]
  (case (str/lower-case (str/trim (str raw)))
    "buy"  :buy
    "sell" :sell
    :hold))

(defn- process-pair [ctx {:keys [candidate news-report]}]
  (let [ticker    (:candidate/ticker candidate)
        cand-id   (:xt/id candidate)
        report-id (:xt/id news-report)
        prompt    (analyst-prompt candidate news-report)
        {:keys [text
                llm-call-id]} (llm/complete ctx prompt {})
        {:keys [rating action reasoning]} (parse-json text)
        rating    (int (max 1 (min 10 (long rating))))
        action    (coerce-action action)
        analysis  {:xt/id                   (random-uuid)
                   :analysis/candidate-id   cand-id
                   :analysis/news-report-id report-id
                   :analysis/ticker         ticker
                   :analysis/rating         rating
                   :analysis/reasoning      reasoning
                   :analysis/action         action
                   :analysis/llm-call-id    llm-call-id
                   :analysis/analyzed-at    (java.util.Date.)}]
    (schema/validate! :analysis analysis)
    (xt/await-tx (:biff.xtdb/node ctx)
                 (xt/submit-tx (:biff.xtdb/node ctx) [[::xt/put analysis]]))
    (println (str "Analyst: " ticker " → " (name action) " rating=" rating " — " reasoning))
    analysis))

(defn run-analyst
  "Analyses each candidate that has a news-report but no analysis yet.
   Calls LLM to produce a rating (1–10), action (buy/sell/hold), and reasoning.
   Writes :analysis to XTDB. Returns count of analyses written."
  [ctx]
  (let [db    (xt/db (:biff.xtdb/node ctx))
        pairs (unanalysed-pairs db)]
    (reduce
     (fn [total pair]
       (try
         (process-pair ctx pair)
         (inc total)
         (catch Exception e
           (println (str "Analyst error [" (get-in pair [:candidate :candidate/ticker]) "]: "
                         (.getMessage e)))
           total)))
     0
     pairs)))

(comment
  (require '[repl :refer [get-context]]
           '[xtdb.api :as xt])

  (def ctx (get-context))

  ;; Run one analyst cycle (processes all candidates with news but no analysis)
  (run-analyst ctx)

  ;; Query all analyses
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull a [*])]
          :where [[a :analysis/ticker]]})

  ;; Full chain: candidate → news-report → analysis
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull c [:candidate/ticker :candidate/trigger])
                  (pull r [:news-report/sentiment :news-report/summary])
                  (pull a [:analysis/rating :analysis/action :analysis/reasoning])]
          :where [[c :candidate/ticker]
                  [r :news-report/candidate-id (:xt/id c)]
                  [a :analysis/candidate-id (:xt/id c)]]}))
