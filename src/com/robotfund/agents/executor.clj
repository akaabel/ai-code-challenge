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

(defn- parse-double* [v]
  (when v (try (Double/parseDouble (str v)) (catch Exception _ nil))))

(defn- write-fill! [ctx order alpaca-resp]
  (let [qty-filled (some-> (parse-double* (:filled_qty alpaca-resp)) long)
        price      (parse-double* (:filled_avg_price alpaca-resp))]
    (if (or (nil? qty-filled) (nil? price) (zero? qty-filled))
      (println (str "Executor: fill data incomplete for " (:order/ticker order)
                    " — will retry next cycle"
                    " (qty=" (:filled_qty alpaca-resp)
                    " price=" (:filled_avg_price alpaca-resp) ")"))
      (let [fill {:xt/id          (random-uuid)
                  :fill/order-id  (:xt/id order)
                  :fill/ticker    (:order/ticker order)
                  :fill/quantity  (int qty-filled)
                  :fill/price     price
                  :fill/filled-at (java.util.Date.)}]
        (schema/validate! :fill fill)
        (xt/submit-tx (:biff.xtdb/node ctx) [[::xt/put fill]])
        (println (str "Executor: fill " (:order/ticker order)
                      " " (name (:order/side order))
                      " " qty-filled " @ $" (format "%.2f" price)))))))

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
          (let [alpaca-resp (alpaca/get-order (:order/alpaca-id order))]
            (if (= "filled" (:status alpaca-resp))
              (do
                (println (str "Executor: stale order already filled — reconciling " (:order/ticker order)))
                (reconcile-order! ctx order))
              (do
                (alpaca/cancel-order (:order/alpaca-id order))
                (xt/await-tx (:biff.xtdb/node ctx)
                             (update-order-status! ctx order :cancelled))
                (println (str "Executor: cancelled stale order " (:order/ticker order))))))
          (reconcile-order! ctx order))
        (catch Exception e
          (println (str "Executor: reconcile error [" (:order/ticker order) "]: "
                        (.getMessage e))))))))

(defn- fail-proposal! [ctx proposal reason]
  (xt/await-tx (:biff.xtdb/node ctx)
               (xt/submit-tx (:biff.xtdb/node ctx)
                             [[::xt/put (assoc proposal
                                              :trade-proposal/decision :failed
                                              :trade-proposal/reason   reason)]]))
  (println (str "Executor: proposal failed [" (:trade-proposal/ticker proposal) "]: " reason)))

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

(defn- orphaned-filled-orders [db]
  (let [filled-order-ids (set (map first (xt/q db '{:find [o]
                                                    :where [[_ :fill/order-id o]]})))]
    (->> (map first (xt/q db '{:find  [(pull o [*])]
                               :where [[o :order/status :filled]]}))
         (remove #(filled-order-ids (:xt/id %))))))

(defn- cancelled-orders-without-fills [db]
  (let [filled-order-ids (set (map first (xt/q db '{:find [o]
                                                    :where [[_ :fill/order-id o]]})))]
    (->> (map first (xt/q db '{:find  [(pull o [*])]
                               :where [[o :order/status :cancelled]]}))
         (remove #(filled-order-ids (:xt/id %))))))

(defn- recover-fill-for-order! [ctx order activities-by-id positions]
  (let [ticker (:order/ticker order)
        acts   (get activities-by-id (:order/alpaca-id order))]
    (cond
      (seq acts)
      (let [act (first acts)]
        (println (str "Executor: recover fill [" ticker "] via activity price=" (:price act)))
        (write-fill! ctx order {:filled_qty (:qty act) :filled_avg_price (:price act)}))

      (get positions ticker)
      (let [pos (get positions ticker)]
        (println (str "Executor: recover fill [" ticker "] via position avg_entry_price=" (:avg_entry_price pos)))
        (write-fill! ctx order {:filled_qty       (str (:order/quantity order))
                                :filled_avg_price (:avg_entry_price pos)}))

      :else
      (println (str "Executor: cannot recover fill — no activity or position for [" ticker "]")))))

(defn- recover-orphaned-fills! [ctx]
  (let [db              (xt/db (:biff.xtdb/node ctx))
        filled-orphans  (orphaned-filled-orders db)
        ;; cancelled orders where position still exists are stale-cancel false positives
        cancelled-orphans (cancelled-orders-without-fills db)]
    (when (or (seq filled-orphans) (seq cancelled-orphans))
      (println (str "Executor: recovering fills — "
                    (count filled-orphans) " filled-orphan(s), "
                    (count cancelled-orphans) " cancelled-orphan(s)"))
      (try
        (let [activities  (alpaca/get-fill-activities)
              by-order-id (group-by :order_id activities)
              positions   (into {} (map (juxt :symbol identity) (alpaca/get-positions)))]
          (doseq [order filled-orphans]
            (recover-fill-for-order! ctx order by-order-id positions))
          (doseq [order cancelled-orphans]
            (when (get positions (:order/ticker order))
              ;; Position exists → order was actually filled; correct the status first
              (xt/await-tx (:biff.xtdb/node ctx)
                           (update-order-status! ctx order :filled))
              (recover-fill-for-order! ctx order by-order-id positions))))
        (catch Exception e
          (println (str "Executor: orphan recovery error: " (.getMessage e))))))))

(defn run-executor
  "Reconciles any pending orders, cancels stale ones, recovers orphaned fills,
   then executes all unexecuted approved/resized :trade-proposals via Alpaca
   market orders. Writes :order and :fill to XTDB. Returns count of orders placed."
  [ctx]
  (reconcile-pending! ctx)
  (recover-orphaned-fills! ctx)
  (let [db        (xt/db (:biff.xtdb/node ctx))
        proposals (unexecuted-proposals db)]
    (reduce
     (fn [total proposal]
       (try
         (execute-proposal! ctx proposal)
         (inc total)
         (catch Exception e
           (let [msg (str "order rejected by Alpaca: " (.getMessage e))]
             (println (str "Executor: order error [" (:trade-proposal/ticker proposal) "]: " msg))
             (fail-proposal! ctx proposal msg))
           total)))
     0
     proposals)))

(comment
  (require '[repl :refer [get-context]])

  (def ctx (get-context))

  ;; Run full executor cycle (reconcile pending + place new orders)
  (run-executor ctx)

  ;; All orders — check status of each (look for :filled with no fill entry)
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull o [:order/ticker :order/side :order/quantity
                           :order/status :order/alpaca-id])]
          :where [[o :order/ticker]]})

  ;; Orphaned orders — :filled status but no fill entity
  (orphaned-filled-orders (xt/db (:biff.xtdb/node ctx)))

  ;; All fills
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull f [*])]
          :where [[f :fill/ticker]]})

  ;; What Alpaca fill activities exist?
  (com.robotfund.alpaca/get-fill-activities)

  ;; Full chain: proposal → order → fill
  (xt/q (xt/db (:biff.xtdb/node ctx))
        '{:find  [(pull p [:trade-proposal/ticker :trade-proposal/action :trade-proposal/quantity])
                  (pull o [:order/status :order/alpaca-id])
                  (pull f [:fill/quantity :fill/price])]
          :where [[p :trade-proposal/decision :approved]
                  [o :order/proposal-id p]
                  [f :fill/order-id o]]}))
