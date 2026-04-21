(ns user
  (:require [repl :refer [get-context]]
            [com.biffweb :as biff]
            [xtdb.api :as xt]))

(defn save!
  "Upsert doc into XTDB. Adds a random :xt/id if absent.
  Returns the tx result — keep it to use with db-as-of."
  [doc]
  (let [{:keys [biff.xtdb/node]} (get-context)
        doc' (update doc :xt/id #(or % (random-uuid)))
        tx   (xt/submit-tx node [[::xt/put doc']])]
    (xt/await-tx node tx)))

(defn q
  "Datalog query against the current XTDB snapshot."
  [query]
  (let [{:keys [biff/db]} (get-context)]
    (biff/q db query)))

(comment
  ;; ── round-trip ──────────────────────────────────────────────────────────────

  ;; Write a document. save! returns the tx result map.
  (def tx1 (save! {:xt/id ::note, :note/text "hello XTDB v1 aka"}))

  ;; Read it back by entity id.
  (let [{db :biff/db} (get-context)]
    (xt/entity db ::note))
  ;; => {:xt/id :user/note, :note/text "hello XTDB v1"}

  ;; Query via Datalog — pull all documents that have :note/text.
  (q '{:find  [(pull doc [*])]
       :where [[doc :note/text]]})

  ;; Overwrite with a new version.
  (def tx2 (save! {:xt/id ::note, :note/text "hello XTDB v2"}))

  ;; Current snapshot shows the updated value.
  (let [{db :biff/db} (get-context)]
    (xt/entity db ::note))
  ;; => {:xt/id :user/note, :note/text "hello XTDB v2"}

  ;; ── as-of ───────────────────────────────────────────────────────────────────

  ;; Time-travel: db snapshot as of tx1 → returns the original value.
  (let [{node :biff.xtdb/node} (get-context)
        db-then (xt/db node {::xt/tx tx1})]
    (xt/entity db-then ::note))
  ;; => {:xt/id :user/note, :note/text "hello XTDB v1"}

  ;; ── cleanup ─────────────────────────────────────────────────────────────────
  ;; To reset the dev DB: rm -r storage/xtdb  (never in prod!)
  )
