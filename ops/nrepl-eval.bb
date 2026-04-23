#!/usr/bin/env bb
;; nREPL client using babashka/nrepl-client (configured in bb.edn).
;; Usage: bb ops/nrepl-eval.bb '<clojure expression>'
;; Note: eval errors appear in the running app's console, not here.

(require '[babashka.nrepl-client :as nrepl])

(defn nrepl-eval
  "Evaluates code against the running nREPL server on localhost:7888."
  [code & {:keys [port] :or {port 7888}}]
  (nrepl/eval-expr {:port port :expr code}))

(let [code (first *command-line-args*)]
  (when-not code
    (println "Usage: bb ops/nrepl-eval.bb '<expression>'")
    (System/exit 1))
  (doseq [v (:vals (nrepl-eval code))]
    (println v)))
