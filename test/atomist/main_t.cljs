(ns atomist.main-t
  (:require [cljs.test :refer-macros [async deftest is testing run-tests use-fixtures]]
            [cljs.core.async :refer [>! <! timeout chan]]
            [atomist.promise :as promise]
            [atomist.api :as api]
            [atomist.cljs-log :as log]
            [atomist.main :as main]
            [atomist.graphql-channels]
            [goog.string.format]
            [goog.string :as gstring])
  (:require-macros [cljs.core.async.macros :refer [go]]))

;; example of making assertions on an async handler chain
(deftest invoke-handler-chain-test
  (let [event #js {:data {:Push []}
                   :extensions {:correlation_id "corrid"
                                :team_id "teamid"
                                :team_name "teamname"}}
        send-response-callback (fn [& args]
                                 ;; TODO can make assertions on messages sent to the response topic
                                 (is true)
                                 (new js/Promise (fn [resolver _] (resolver true))))
        request-handler-chain (-> (fn [request]
                                    (log/info "request " request)
                                    (is true)
                                    (go request))
                                  (api/status))]
    (async
     done
     (go
       (<! (promise/from-promise
            (api/make-request event send-response-callback request-handler-chain)))
       (log/info "okay done now")
       (done)))))

(deftest wish-test
  (let [r {:body {:data {:GitHubAppInstallation [{:token {:secret "github-token-secret"}}]}}}
        event #js {:data {:Comment [{:body "Comment stuff and then\n/wish a bunch of stuff\nand then continue with comment"
                                     :by {:login "slimsslenderslacks"}
                                     :issue {:number 23
                                             :repo {:id "repo-id"
                                                    :name "repo-name"
                                                    :owner "repo-owner-name"
                                                    :org {:id "org-id"
                                                          :owner "repo-org-owner"}}}}]}
                   :extensions {:team_id "team-id"
                                :team_name "team-name"}
                   :token "github-token"}
        callback (fn [& args] (new js/Promise (fn [accept _] (accept true))))]
    (async
     done
     (go (<! (promise/from-promise
              (api/make-request event callback
                                (-> (api/finished)
                                    (main/run-commands)
                                    (main/validate-commands)
                                    (main/add-commands)
                                    (main/check-push-or-comment-for-intents)
                                    (api/add-skill-config)
                                    (api/extract-github-token)
                                    (api/create-ref-from-event)
                                    (api/log-event)
                                    (api/status :send-status (fn [{{:keys [errors status]} :status :as request}]
                                                               (cond
                                                                 (seq errors)
                                                                 (apply str errors)
                                                                 (not (nil? status))
                                                                 (gstring/format "command status %s" status)
                                                                 :else
                                                                 (if-let [data-keys (-> request :data keys)]
                                                                   (gstring/format "processed %s" data-keys)
                                                                   "check this"))))))))
         (done)))))

(use-fixtures :once
  {:before (fn [])
   :after (fn [])})

(comment
  (set! atomist.graphql-channels/graphql->channel (fn [& args] (go (println "don't call it!!!")
                                                                   {:some-data "whaewt"})))
  (enable-console-print!)
  (run-tests))

