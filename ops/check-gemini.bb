#!/usr/bin/env bb
;; Verifies that the Gemini API key in ~/fund/.env is valid and reachable.
;; Usage: bb ops/check-gemini.bb
(require '[babashka.http-client :as http]
         '[cheshire.core :as json])

(def env-file (str (System/getenv "HOME") "/fund/.env"))

(defn load-env [file]
  (into {}
        (keep (fn [line]
                (let [line (clojure.string/trim line)]
                  (when (and (seq line)
                             (not (clojure.string/starts-with? line "#")))
                    (let [idx (.indexOf line "=")]
                      (when (pos? idx)
                        [(subs line 0 idx) (subs line (inc idx))])))))
              (clojure.string/split-lines (slurp file)))))

(def env (load-env env-file))
(def api-key (get env "GEMINI_API_KEY"))

(when (clojure.string/blank? api-key)
  (println "ERROR: GEMINI_API_KEY not found in" env-file)
  (System/exit 1))

(println (str "Checking Gemini API key: " (subs api-key 0 8) "..."))

(let [url  (str "https://generativelanguage.googleapis.com/v1beta"
                "/models/gemini-2.5-flash:generateContent?key=" api-key)
      body (json/encode {:contents [{:parts [{:text "Reply with the single word: ok"}]}]})
      resp (http/post url {:headers {"Content-Type" "application/json"} :body body :throw false})]
  (if (= (:status resp) 200)
    (let [parsed (json/decode (:body resp) true)
          text   (-> parsed :candidates first :content :parts first :text)]
      (println (str "Gemini responded: " (clojure.string/trim text)))
      (println "Connection ok."))
    (do
      (println (str "FAILED — HTTP " (:status resp)))
      (println (:body resp))
      (System/exit 1))))
