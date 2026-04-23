#!/usr/bin/env bb
;; nREPL client using babashka's built-in bencode.core.
;; Opens a connection, evals, sends a close op, then closes the socket.
;; Usable both as a library (required from bb.edn tasks) and as a CLI script.
;; CLI usage: bb ops/nrepl_eval.bb '<clojure expression>'

(ns nrepl-eval
  (:require [bencode.core :as b]))

(defn- bytes->str [x]
  (if (bytes? x) (String. x) (str x)))

(defn- read-msg [raw]
  (let [res (zipmap (map (comp keyword bytes->str) (keys raw))
                    (map #(if (bytes? %) (bytes->str %) %) (vals raw)))]
    (cond-> res
      (:status res)   (update :status #(mapv bytes->str %))
      (:sessions res) (update :sessions #(mapv bytes->str %)))))

(defn nrepl-eval
  "Evaluates code against the running nREPL server.
   Properly closes the session before returning.
   Returns {:vals [...] :err <string-or-nil> :ex <string-or-nil>}."
  [code & {:keys [port] :or {port 7888}}]
  (with-open [sock (java.net.Socket. "localhost" (int port))]
    (let [os      (.getOutputStream sock)
          in      (java.io.PushbackInputStream. (.getInputStream sock))
          _       (b/write-bencode os {"op" "clone" "id" "1"})
          session (:new-session (read-msg (b/read-bencode in)))
          _       (b/write-bencode os {"op"      "eval"
                                       "code"    code
                                       "id"      "2"
                                       "session" session})]
      (loop [vals [] errors nil exceptions nil]
        (let [{:keys [status out value err ex]} (read-msg (b/read-bencode in))]
          (when out (print out) (flush))
          (if (some #{"done"} status)
            (do (b/write-bencode os {"op" "close" "session" session})
                {:vals vals :err errors :ex exceptions})
            (recur (cond-> vals value (conj value))
                   (or errors err)
                   (or exceptions ex))))))))

(defn print-result [{:keys [vals err ex]}]
  (when ex  (println "EXCEPTION:" ex))
  (when err (println "ERROR:" err))
  (doseq [v vals] (println v))
  (when (and (empty? vals) (nil? err) (nil? ex)) (println "nil")))

;; Only run as a CLI script when executed directly, not when required as a library.
(when (= *file* (System/getProperty "babashka.file"))
  (let [code (first *command-line-args*)]
    (when-not code
      (println "Usage: bb ops/nrepl_eval.bb '<expression>'")
      (System/exit 1))
    (print-result (nrepl-eval code))))
