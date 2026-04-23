(ns com.robotfund.schedule
  (:require [clojure.tools.logging :as log]
            [com.biffweb :as biff]
            [com.robotfund.agents.scanner :as scanner]
            [com.robotfund.agents.news :as news]
            [com.robotfund.agents.analyst :as analyst]
            [com.robotfund.agents.risk :as risk]
            [com.robotfund.agents.executor :as executor])
  (:import (java.time ZoneId LocalTime DayOfWeek LocalDate)
           (java.util Date)))

(def ^:private et-zone (ZoneId/of "America/New_York"))
(def ^:private market-open (LocalTime/of 9 30))
(def ^:private market-close (LocalTime/of 16 0))

;; NYSE holidays for 2026 — the only year this fund trades
(def ^:private holidays-2026
  #{(LocalDate/of 2026 1 1)
    (LocalDate/of 2026 1 19)
    (LocalDate/of 2026 2 16)
    (LocalDate/of 2026 4 3)
    (LocalDate/of 2026 5 25)  ;; Memorial Day — last trading day is May 22
    (LocalDate/of 2026 7 3)
    (LocalDate/of 2026 9 7)
    (LocalDate/of 2026 11 26)
    (LocalDate/of 2026 12 25)})

(defn market-hours?
  "Returns true if date falls within NYSE trading hours: 09:30–16:00 ET, Mon–Fri, non-holiday."
  [^Date date]
  (let [zdt    (-> date .toInstant (.atZone et-zone))
        dow    (.getDayOfWeek zdt)
        t      (.toLocalTime zdt)
        d      (.toLocalDate zdt)]
    (and (not (#{DayOfWeek/SATURDAY DayOfWeek/SUNDAY} dow))
         (not (contains? holidays-2026 d))
         (not (.isBefore t market-open))
         (.isBefore t market-close))))

(defn- agent-schedule [offset-minutes]
  (->> (iterate #(biff/add-seconds % (* 15 60))
                (biff/add-seconds (Date.) (* 60 offset-minutes)))
       (filter market-hours?)))

(defn- scanner-task  [ctx] (log/info "scheduler/scanner:"  (scanner/run-scanner ctx)   "candidates"))
(defn- news-task     [ctx] (log/info "scheduler/news:"     (news/run-news-agent ctx)   "reports"))
(defn- analyst-task  [ctx] (log/info "scheduler/analyst:"  (analyst/run-analyst ctx)   "analyses"))
(defn- risk-task     [ctx] (log/info "scheduler/risk:"     (risk/run-risk ctx)         "proposals"))
(defn- executor-task [ctx] (log/info "scheduler/executor:" (executor/run-executor ctx) "orders"))

(def module
  {:tasks [{:task #'scanner-task  :schedule #(agent-schedule 0)}
           {:task #'news-task     :schedule #(agent-schedule 2)}
           {:task #'analyst-task  :schedule #(agent-schedule 5)}
           {:task #'risk-task     :schedule #(agent-schedule 7)}
           {:task #'executor-task :schedule #(agent-schedule 10)}]})
