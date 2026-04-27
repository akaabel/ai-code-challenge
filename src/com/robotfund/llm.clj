(ns com.robotfund.llm
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [xtdb.api :as xt]))

(def fast-model    "gemma-3-27b-it")
(def quality-model "gemma-3-27b-it")

(def ^:private base-url "https://generativelanguage.googleapis.com/v1beta")

(def ^:private retry-sleeps [5000 15000 30000 60000])

(defn- call-gemini [url body]
  (loop [attempt 0]
    (let [resp   (http/post url {:content-type     :json
                                 :body             body
                                 :as               :json
                                 :throw-exceptions false})
          status (:status resp)
          sleep  (get retry-sleeps attempt)]
      (cond
        (= status 200) (:body resp)
        (and (#{429 503} status) sleep)
        (do (Thread/sleep sleep) (recur (inc attempt)))
        :else (throw (ex-info (str "Gemini API error status=" status) {:status status :body (:body resp)}))))))

(defn complete
  "Calls Gemini, persists an :llm-call entity to XTDB.
   Returns {:text response-string :llm-call-id uuid}.
   opts: :model (default fast-model)"
  [ctx prompt {:keys [model] :or {model fast-model}}]
  (let [api-key (System/getenv "GEMINI_API_KEY")
        url     (str base-url "/models/" model ":generateContent?key=" api-key)
        body    (json/encode {:contents [{:parts [{:text prompt}]}]})
        t0      (System/currentTimeMillis)
        resp    (call-gemini url body)
        elapsed (- (System/currentTimeMillis) t0)
        text    (-> resp :candidates first :content :parts first :text)
        in-tok  (or (-> resp :usageMetadata :promptTokenCount) 1)
        out-tok (or (-> resp :usageMetadata :candidatesTokenCount) 1)
        call-id (random-uuid)
        node    (:biff.xtdb/node ctx)
        tx      (xt/submit-tx node
                               [[::xt/put {:xt/id                  call-id
                                           :llm-call/model         model
                                           :llm-call/prompt        prompt
                                           :llm-call/response      text
                                           :llm-call/input-tokens  in-tok
                                           :llm-call/output-tokens out-tok
                                           :llm-call/latency-ms    elapsed
                                           :llm-call/called-at     (java.util.Date.)}]])]
    (xt/await-tx node tx)
    {:text text :llm-call-id call-id}))

(comment
  (require '[com.robotfund :as app]
           '[com.robotfund.llm :as llm])

  (def ctx @app/system)

  ;; Basic call — returns {:text "..." :llm-call-id uuid} and writes :llm-call to XTDB
  (:text (llm/complete ctx "What is 2+2?" {}))

  ;; Quality model
  ;; This does not work, probably because the quality models are not yet generally available. 
  (llm/complete ctx "Summarise the investment case for NVDA in one sentence." {:model llm/quality-model})

  (llm/complete ctx "Summarise the investment case for NVDA in one sentence." {})

  ;; Confirm the :llm-call was persisted (fresh db snapshot)
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull e [*])]
          :where [[e :llm-call/model]]})
  )
