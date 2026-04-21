(ns com.robotfund.schema
  (:require [malli.core :as malc]
            [malli.error :as me]
            [malli.registry :as malr]))

(def schema
  {;; Biff scaffold
   :user/id :uuid
   :user [:map {:closed true}
          [:xt/id                     :user/id]
          [:user/email                :string]
          [:user/joined-at            inst?]
          [:user/foo {:optional true} :string]
          [:user/bar {:optional true} :string]]

   :msg/id :uuid
   :msg [:map {:closed true}
         [:xt/id       :msg/id]
         [:msg/user    :user/id]
         [:msg/text    :string]
         [:msg/sent-at inst?]]

   ;; LLM audit trail (defined first; referenced by news-report and analysis)
   :llm-call/id :uuid

   :llm-call [:map {:closed true}
              [:xt/id                  :llm-call/id]
              [:llm-call/model         :string]
              [:llm-call/prompt        :string]
              [:llm-call/response      :string]
              [:llm-call/input-tokens  pos-int?]
              [:llm-call/output-tokens pos-int?]
              [:llm-call/latency-ms    pos-int?]
              [:llm-call/called-at     inst?]]

   ;; Scanner
   :candidate/id      :uuid
   :candidate/trigger [:enum :price-change :volume-spike]

   :candidate [:map {:closed true}
               [:xt/id                                    :candidate/id]
               [:candidate/ticker                         :string]
               [:candidate/scanned-at                     inst?]
               [:candidate/trigger                        :candidate/trigger]
               [:candidate/price-change-pct {:optional true} double?]
               [:candidate/volume-ratio     {:optional true} double?]]

   ;; News agent
   :news-report/id :uuid

   :news-report [:map {:closed true}
                 [:xt/id                    :news-report/id]
                 [:news-report/candidate-id :candidate/id]
                 [:news-report/ticker       :string]
                 [:news-report/sentiment    number?]
                 [:news-report/summary      :string]
                 [:news-report/headlines    [:vector :string]]
                 [:news-report/llm-call-id  :llm-call/id]
                 [:news-report/reported-at  inst?]]

   ;; Analyst agent
   :analysis/id     :uuid
   :analysis/action [:enum :buy :sell :hold]

   :analysis [:map {:closed true}
              [:xt/id                   :analysis/id]
              [:analysis/candidate-id   :candidate/id]
              [:analysis/news-report-id :news-report/id]
              [:analysis/ticker         :string]
              [:analysis/rating         [:int {:min 1 :max 10}]]
              [:analysis/reasoning      :string]
              [:analysis/action         :analysis/action]
              [:analysis/llm-call-id    :llm-call/id]
              [:analysis/analyzed-at    inst?]]

   ;; Risk manager
   :trade-proposal/id       :uuid
   :trade-proposal/decision [:enum :approved :rejected :resized]

   :trade-proposal [:map {:closed true}
                    [:xt/id                      :trade-proposal/id]
                    [:trade-proposal/analysis-id :analysis/id]
                    [:trade-proposal/ticker      :string]
                    [:trade-proposal/action      :analysis/action]
                    [:trade-proposal/quantity    pos-int?]
                    [:trade-proposal/decision    :trade-proposal/decision]
                    [:trade-proposal/reason      :string]
                    [:trade-proposal/proposed-at inst?]]

   ;; Executor
   :order/id     :uuid
   :order/side   [:enum :buy :sell]
   :order/status [:enum :pending :filled :cancelled :expired]

   :order [:map {:closed true}
           [:xt/id             :order/id]
           [:order/proposal-id :trade-proposal/id]
           [:order/ticker      :string]
           [:order/side        :order/side]
           [:order/quantity    pos-int?]
           [:order/alpaca-id   :string]
           [:order/status      :order/status]
           [:order/placed-at   inst?]]

   :fill/id :uuid

   :fill [:map {:closed true}
          [:xt/id          :fill/id]
          [:fill/order-id  :order/id]
          [:fill/ticker    :string]
          [:fill/quantity  pos-int?]
          [:fill/price     pos?]
          [:fill/filled-at inst?]]})

(defn validate!
  "Throws ex-info with humanized errors when value does not conform to schema-key."
  [schema-key value]
  (let [registry (malr/composite-registry malc/default-registry schema)
        result   (malc/explain schema-key value {:registry registry})]
    (when result
      (throw (ex-info (str "Schema validation failed for " schema-key)
                      {:schema-key schema-key
                       :value      value
                       :errors     (me/humanize result)})))))

(def module
  {:schema schema})

(comment
  ;; Valid candidate — validate! returns nil
  (validate! :candidate
             {:xt/id                     (random-uuid)
              :candidate/ticker          "AAPL"
              :candidate/scanned-at      (java.util.Date.)
              :candidate/trigger         :price-change
              :candidate/price-change-pct 2.3})

  ;; Invalid candidate (missing required :candidate/ticker) — throws ex-info
  (validate! :candidate
             {:xt/id                (random-uuid)
              :candidate/scanned-at (java.util.Date.)
              :candidate/trigger    :price-change})

  ;; Invalid candidate (unrecognised trigger) — throws ex-info
  (validate! :candidate
             {:xt/id                (random-uuid)
              :candidate/ticker     "MSFT"
              :candidate/scanned-at (java.util.Date.)
              :candidate/trigger    :bad-trigger})

  ;; Valid llm-call — validate! returns nil
  (validate! :llm-call
             {:xt/id                  (random-uuid)
              :llm-call/model         "claude-haiku-4-5-20251001"
              :llm-call/prompt        "Should we buy AAPL?"
              :llm-call/response      "Based on recent momentum..."
              :llm-call/input-tokens  42
              :llm-call/output-tokens 120
              :llm-call/latency-ms    850
              :llm-call/called-at     (java.util.Date.)})

  ;; Invalid analysis (rating out of 1–10 range) — throws ex-info
  (validate! :analysis
             {:xt/id                   (random-uuid)
              :analysis/candidate-id   (random-uuid)
              :analysis/news-report-id (random-uuid)
              :analysis/ticker         "AAPL"
              :analysis/rating         11
              :analysis/reasoning      "Strong momentum"
              :analysis/action         :buy
              :analysis/llm-call-id    (random-uuid)
              :analysis/analyzed-at    (java.util.Date.)}))
