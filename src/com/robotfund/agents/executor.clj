(ns com.robotfund.agents.executor
  (:require [com.robotfund.alpaca :as alpaca]
            [com.robotfund.schema :as schema]
            [xtdb.api :as xt]))

(def ^:private stale-ms (* 30 60 1000))
(def ^:private fill-poll-ms 3000)

(defn- unexecuted-proposals [db]
  (let [done     (set (map first (xt/q db '{:find [p]
                                            :where [[_ :order/proposal-id p]]})))
        approved (map first (xt/q db '{:find [(pull p [*])]
                                       :where [[p :trade-proposal/decision :approved]]}))
        resized  (map first (xt/q db '{:find [(pull p [*])]
                                       :where [[p :trade-proposal/decision :resized]]}))]
    (remove #(done (:xt/id %)) (concat approved resized))))

(defn- pending-orders [db]
  (map first (xt/q db '{:find  [(pull o [*])]
                         :where [[o :order/status :pending]]})))

(defn- fill-exists? [db order-id]
  (seq (xt/q db '{:find [f]
                  :in   [order-id]
                  :where [[f :fill/order-id order-id]]}
             order-id)))

(defn- alpaca->order-status [alpaca-status]
  (case alpaca-status
    "filled"     :filled
    "canceled"   :cancelled
    "expired"    :expired
    :pending))

(defn- write-fill! [ctx order alpaca-resp]
  (let [qty-filled (long (Double/parseDouble (str (:filled_qty alpaca-resp))))
        price      (Double/parseDouble (str (:filled_avg_price alpaca-resp)))
        fill       {:xt/id          (random-uuid)
                    :fill/order-id  (:xt/id order)
                    :fill/ticker    (:order/ticker order)
                    :fill/quantity  (int qty-filled)
                    :fill/price     price
                    :fill/filled-at (java.util.Date.)}]
    (schema/validate! :fill fill)
    (xt/submit-tx (:biff.xtdb/node ctx) [[::xt/put fill]])
    (println (str "Executor: fill " (:order/ticker order)
                  " " (name (:order/side order))
                  " " qty-filled " @ $" (format "%.2f" price)))))

(defn- update-order-status! [ctx order new-status]
  (xt/submit-tx (:biff.xtdb/node ctx)
                [[::xt/put (assoc order :order/status new-status)]]))

(defn- reconcile-order! [ctx order]
  (let [alpaca-resp (alpaca/get-order (:order/alpaca-id order))
        new-status  (alpaca->order-status (:status alpaca-resp))
        db          (xt/db (:biff.xtdb/node ctx))]
    (when (not= new-status :pending)
      (xt/await-tx (:biff.xtdb/node ctx)
                   (update-order-status! ctx order new-status)))
    (when (and (= new-status :filled)
               (not (fill-exists? db (:xt/id order))))
      (xt/await-tx (:biff.xtdb/node ctx)
                   (write-fill! ctx order alpaca-resp)))
    new-status))

(defn- reconcile-pending! [ctx]
  (let [db      (xt/db (:biff.xtdb/node ctx))
        orders  (pending-orders db)
        stale-t (java.util.Date. (- (System/currentTimeMillis) stale-ms))]
    (doseq [order orders]
      (try
        (if (.before (:order/placed-at order) stale-t)
          (do
            (alpaca/cancel-order (:order/alpaca-id order))
            (xt/await-tx (:biff.xtdb/node ctx)
                         (update-order-status! ctx order :cancelled))
            (println (str "Executor: cancelled stale order " (:order/ticker order))))
          (reconcile-order! ctx order))
        (catch Exception e
          (println (str "Executor: reconcile error [" (:order/ticker order) "]: "
                        (.getMessage e))))))))

(defn- execute-proposal! [ctx proposal]
  (let [ticker     (:trade-proposal/ticker proposal)
        action     (:trade-proposal/action proposal)
        qty        (:trade-proposal/quantity proposal)
        side       (name action)
        alpaca-resp (alpaca/place-order ticker qty side)
        alpaca-id  (:id alpaca-resp)
        order      {:xt/id             (random-uuid)
                    :order/proposal-id  (:xt/id proposal)
                    :order/ticker       ticker
                    :order/side         action
                    :order/quantity     (int qty)
                    :order/alpaca-id    alpaca-id
                    :order/status       :pending
                    :order/placed-at    (java.util.Date.)}]
    (schema/validate! :order order)
    (xt/await-tx (:biff.xtdb/node ctx)
                 (xt/submit-tx (:biff.xtdb/node ctx) [[::xt/put order]]))
    (println (str "Executor: placed " side " " qty " " ticker " (alpaca-id=" alpaca-id ")"))
    (Thread/sleep fill-poll-ms)
    (reconcile-order! ctx order)
    order))

(defn run-executor
  "Reconciles any pending orders, cancels stale ones, then executes all
   unexecuted approved/resized :trade-proposals via Alpaca market orders.
   Writes :order and :fill to XTDB. Returns count of orders placed."
  [ctx]
  (reconcile-pending! ctx)
  (let [db        (xt/db (:biff.xtdb/node ctx))
        proposals (unexecuted-proposals db)]
    (reduce
     (fn [total proposal]
       (try
         (execute-proposal! ctx proposal)
         (inc total)
         (catch Exception e
           (println (str "Executor: order error [" (:trade-proposal/ticker proposal) "]: "
                         (.getMessage e)))
           total)))
     0
     proposals)))

(comment
  (require '[repl :refer [get-context]]
           '[xtdb.api :as xt])

  (def ctx (get-context))

  ;; Run full executor cycle (reconcile pending + place new orders)
  (run-executor ctx)

  ;; All orders
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull o [:order/ticker :order/side :order/quantity
                           :order/status :order/alpaca-id])]
          :where [[o :order/ticker]]})

  ;; All fills
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull f [*])]
          :where [[f :fill/ticker]]})

  ;; Full chain: proposal → order → fill
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull p [:trade-proposal/ticker :trade-proposal/action :trade-proposal/quantity])
                  (pull o [:order/status :order/alpaca-id])
                  (pull f [:fill/quantity :fill/price])]
          :where [[p :trade-proposal/decision :approved]
                  [o :order/proposal-id p]
                  [f :fill/order-id o]]}))
