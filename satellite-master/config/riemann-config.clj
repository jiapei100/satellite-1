;; Copyright 2015 TWO SIGMA OPEN SOURCE, LLC
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.


;; default emailer uses localhost
(def email (mailer))
(def indx (index))

;; expire expired events every 5 seconds
(periodically-expire 5)

(def task-totals-time-stream
  (by :host
      (combine difference-since-beginning
               (where (> (:interval event) 600)
                      (project [(service "mesos/slave/total-tasks-failed")
                                (service "mesos/slave/total-tasks-started")
                                (service "mesos/slave/total-tasks-finished")]
                               (smap (partial fold-blackhole-thresholds satellite.core/settings)
                                     (where (state "critical")
                                            #(warn "Host failing too many tasks:" %)
                                            (throttle 1 900
                                                      #(warn "Emailing administration about task black hole." %)
                                                      (email "foo@example.com")))))))))

(streams
 indx
 (where (service #"satellite.*")
        prn)
 (where (service #"mesos/slave.*")
        prn
        ;; if a host/service pair changes state, update global state
        (changed-state
         (where (state "ok")
                delete-event
                (else
                 persist-event)))
        ;; If we stop receiving any test from a host, remove that host
        ;; from the whitelist. We don't want to send tasks to a host
        ;; that is (a) experiencing a network partition or (b) whose
        ;; tests are timing-out. If it is (c) that the satellite-slave
        ;; process is down, this at least warrants investigation.
        (where* expired?
                (fn [event]
                  (warn "Removing host due to expired event" (:host event))
                  (off-host (:host event)))
                ;; Otherwise make sure all tests pass on each host
                (else
                 (coalesce 60
                           ensure-all-tests-pass))))

 ;; check task run totals to detect task host black holes
 (where (service #"mesos/slave/total-tasks-.*")
        (by [:host :service]
            (moving-time-window (:blackhole-check-seconds satellite.core/settings)
                                task-totals-time-stream)))

 ;; if less than 70% of hosts registered with mesos are
 ;; on the whitelist, alert with an email
 (where (and (service #"mesos/prop-available-hosts")
             (< metric 0.7))
        (email "foo@example.com")))
