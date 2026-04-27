(ns com.robotfund.agents.news
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.robotfund.alpaca :as alpaca]
            [com.robotfund.llm :as llm]
            [com.robotfund.schema :as schema]
            [xtdb.api :as xt]))

(defn- unprocessed-candidates
  "Returns candidates that have no news-report yet."
  [db]
  (let [done (set (map first (xt/q db '{:find [c]
                                        :where [[_ :news-report/candidate-id c]]})))
        all  (map first (xt/q db '{:find [(pull c [*])]
                                   :where [[c :candidate/ticker]]}))]
    (remove #(done (:xt/id %)) all)))

(defn- sentiment-prompt [ticker articles]
  (let [items (map-indexed
               (fn [i {:keys [headline summary]}]
                 (str (inc i) ". " headline
                      (when (seq summary) (str " — " summary))))
               articles)]
    (str "You are a financial news analyst. Analyse recent news for " ticker ".\n\n"
         "News:\n" (str/join "\n" items) "\n\n"
         "Return a JSON object with exactly these two fields:\n"
         "- \"sentiment\": float from -1.0 (very bearish) to 1.0 (very bullish)\n"
         "- \"summary\": one sentence describing the overall sentiment and key themes\n\n"
         "Respond with ONLY valid JSON. No markdown, no explanation.")))

(defn- parse-json [text]
  (let [cleaned (-> text
                    str/trim
                    (str/replace #"(?s)```(?:json)?\s*" "")
                    (str/replace #"```" "")
                    str/trim)]
    (json/parse-string cleaned true)))

(defn- process-candidate [ctx candidate]
  (let [ticker   (:candidate/ticker candidate)
        cand-id  (:xt/id candidate)
        articles (alpaca/get-news ticker {:limit 10})]
    (when (seq articles)
      (let [prompt              (sentiment-prompt ticker articles)
            {:keys [text
                    llm-call-id]} (llm/complete ctx prompt {})
            {:keys [sentiment
                    summary]}   (parse-json text)
            sentiment           (double (max -1.0 (min 1.0 sentiment)))
            report              {:xt/id                    (random-uuid)
                                 :news-report/candidate-id  cand-id
                                 :news-report/ticker        ticker
                                 :news-report/sentiment     sentiment
                                 :news-report/summary       summary
                                 :news-report/headlines     (mapv :headline articles)
                                 :news-report/llm-call-id   llm-call-id
                                 :news-report/reported-at   (java.util.Date.)}]
        (schema/validate! :news-report report)
        (xt/await-tx (:biff.xtdb/node ctx)
                     (xt/submit-tx (:biff.xtdb/node ctx) [[::xt/put report]]))
        (println (str "News: " ticker
                      " → sentiment=" (format "%.2f" sentiment)
                      " — " summary))
        report))))

(defn run-news-agent
  "For each unprocessed candidate, fetches Alpaca news, scores sentiment via LLM,
   writes :news-report to XTDB. Returns count of reports written."
  [ctx]
  (let [db         (xt/db (:biff.xtdb/node ctx))
        candidates (unprocessed-candidates db)]
    (reduce
     (fn [total candidate]
       (try
         (if (process-candidate ctx candidate)
           (inc total)
           (do (println (str "News: " (:candidate/ticker candidate) " → no articles, skipped"))
               total))
         (catch Exception e
           (println (str "News error [" (:candidate/ticker candidate) "]: " (.getMessage e)))
           total)))
     0
     candidates)))

(comment
  (require '[repl :refer [get-context]])

  (def ctx (get-context))

  ;; Run one news cycle (processes all candidates without a report)
  (run-news-agent ctx)

  ;; Query all news reports
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull r [*])]
          :where [[r :news-report/ticker]]})

  ;; Full chain for one ticker: candidate → news-report → llm-call
  (let [_db (:biff/db ctx)]
    (xt/q (xt/db (:biff.xtdb/node ctx))
          '{:find  [(pull c [:candidate/ticker :candidate/trigger])
                    (pull r [:news-report/sentiment :news-report/summary])
                    (pull l [:llm-call/model :llm-call/latency-ms])]
            :where [[c :candidate/ticker]
                    [r :news-report/candidate-id (:xt/id c)]
                    [l :xt/id (:news-report/llm-call-id r)]]})))
