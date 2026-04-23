(ns com.robotfund.llm
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [xtdb.api :as xt]))

(def fast-model    "gemini-2.5-flash")
(def quality-model "gemini-2.5-pro")

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
        (and (= status 429) sleep)
        (do (Thread/sleep sleep) (recur (inc attempt)))
        :else (throw (ex-info (str "Gemini API error status=" status) {:status status :body (:body resp)}))))))

(defn complete
  "Calls Gemini, persists an :llm-call entity to XTDB, returns the response text.
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
        out-tok (or (-> resp :usageMetadata :candidatesTokenCount) 1)]
    (let [node (:biff.xtdb/node ctx)
          tx   (xt/submit-tx node
                             [[::xt/put {:xt/id                  (random-uuid)
                                         :llm-call/model         model
                                         :llm-call/prompt        prompt
                                         :llm-call/response      text
                                         :llm-call/input-tokens  in-tok
                                         :llm-call/output-tokens out-tok
                                         :llm-call/latency-ms    elapsed
                                         :llm-call/called-at     (java.util.Date.)}]])]
      (xt/await-tx node tx))
    ;;=> Syntax error compiling at (src/com/robotfund/llm.clj:41:16).
    ;;   Unable to resolve symbol: model in this context
    ;;   
    text))

(comment
  (require '[com.robotfund :as app]
           '[com.robotfund.llm :as llm]
           '[xtdb.api :as xt])

  (def ctx @app/system)

  ;; Basic call — returns the answer and writes an :llm-call entity to XTDB
  (llm/complete ctx "What is 2+2?" {})

  ;; Quality model
  ;; This does not work, probably because the quality models are not yet generally available. 
  (llm/complete ctx "Summarise the investment case for NVDA in one sentence." {:model llm/quality-model})

  (llm/complete ctx "Summarise the investment case for NVDA in one sentence." {})

  ;; Confirm the :llm-call was persisted (fresh db snapshot)
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull e [*])]
          :where [[e :llm-call/model]]})
  )
