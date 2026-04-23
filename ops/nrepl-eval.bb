#!/usr/bin/env bb
;; Minimal nREPL client. Usage: bb ops/nrepl-eval.bb '<clojure expression>'
;; Connects to nREPL on localhost:7888, evaluates the expression, prints results.

(import '[java.net Socket]
        '[java.io PushbackInputStream])

(defn- ben-encode [x]
  (cond
    (string? x)
    (let [b (.getBytes x "UTF-8")]
      (str (alength b) ":" x))

    (int? x) (str "i" x "e")

    (map? x)
    (str "d"
         (apply str (map (fn [[k v]]
                           (str (ben-encode (if (keyword? k) (name k) (str k)))
                                (ben-encode v)))
                         (sort-by (fn [[k _]] (if (keyword? k) (name k) (str k))) x)))
         "e")

    (sequential? x)
    (str "l" (apply str (map ben-encode x)) "e")

    :else (ben-encode (str x))))

(defn- read-ben [^PushbackInputStream in]
  (let [b (.read in)]
    (when (not= b -1)
      (cond
        (= b (int \i))
        (let [sb (StringBuilder.)]
          (loop [c (.read in)]
            (if (= c (int \e))
              (Long/parseLong (str sb))
              (do (.append sb (char c)) (recur (.read in))))))

        (= b (int \l))
        (loop [acc []]
          (let [peek (.read in)]
            (if (= peek (int \e))
              acc
              (do (.unread in peek)
                  (recur (conj acc (read-ben in)))))))

        (= b (int \d))
        (loop [acc {}]
          (let [peek (.read in)]
            (if (= peek (int \e))
              acc
              (do (.unread in peek)
                  (let [k (read-ben in) v (read-ben in)]
                    (recur (assoc acc k v)))))))

        :else ; string: remaining digits + colon + content
        (let [sb (StringBuilder. (str (char b)))]
          (loop [c (.read in)]
            (if (= c (int \:))
              (let [len (Long/parseLong (str sb))
                    buf (byte-array len)]
                (.read in buf 0 len)
                (String. buf "UTF-8"))
              (do (.append sb (char c)) (recur (.read in))))))))))

(defn nrepl-eval
  "Evaluates code string against the running nREPL on port 7888.
   Returns a map with :values (vector of result strings) and :err (if any)."
  [code & {:keys [port ns timeout]
           :or   {port 7888 ns "user" timeout 60000}}]
  (with-open [sock   (doto (Socket.) (.connect (java.net.InetSocketAddress. "localhost" port) 5000))
              in     (PushbackInputStream. (.getInputStream sock))
              out    (.getOutputStream sock)]
    (.setSoTimeout sock timeout)
    (let [id  (str (random-uuid))
          msg (ben-encode {"op" "eval" "id" id "code" code "ns" ns})]
      (.write out (.getBytes msg "UTF-8"))
      (.flush out)
      (loop [values [] err nil]
        (let [resp (read-ben in)]
          (if (nil? resp)
            {:values values :err err}
            (let [status (get resp "status")
                  done?  (and (sequential? status) (some #{"done"} status))]
              (cond
                done?                {:values values :err err}
                (get resp "ex")      (recur values (get resp "ex"))
                (get resp "err")     (recur values (get resp "err"))
                (get resp "value")   (recur (conj values (get resp "value")) err)
                :else                (recur values err)))))))))

(let [code (first *command-line-args*)]
  (when-not code
    (println "Usage: bb ops/nrepl-eval.bb '<expression>'")
    (System/exit 1))
  (let [{:keys [values err]} (nrepl-eval code)]
    (when err  (println "ERROR:" err))
    (doseq [v values] (println v))
    (when (and (empty? values) (nil? err)) (println "nil"))))
