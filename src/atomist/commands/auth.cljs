(ns atomist.commands.auth
  (:require [atomist.github :as github]
            [atomist.api :as api]
            [goog.string :as gstring]
            [goog.string.format]
            [atomist.api.v2.log :as log]
            [cljs.core.async :refer [<!] :as async :refer-macros [go]]))

(defn authorization-link 
  "create an auth link so that we can collect authorization to run a github command as you"
  [{:keys [team-id resource-provider-id redirect-uri state]}]
  (gstring/format
   "https://api.atomist.com/v2/auth/teams/%s/resource-providers/%s/token?redirect-uri=%s&state=%s"
   team-id
   resource-provider-id
   (js/encodeURIComponent (or redirect-uri "https://www.atomist.com/success"))
   (or state "state")))

(defn consider-authorizing-your-user 
  "add comment advising user that they can link their account to the bot so that commands run as them"
  [{{:command/keys [token repo command source] sha :push/sha number :label/number} :command :as request}]
  (let [m {:owner (:owner repo) :repo (:name repo) :token token}
        comment-body (gstring/format
                      "running %s with installation token - consider authorizing your user so that slash commands run as you:  [Authorization link](%s)"
                      command
                      (authorization-link
                       {:team-id (-> request :team :id)
                        :resource-provider-id (-> request :provider :id)}))]
    (case source
      :command.source/commit-message
      (github/post-commit-comment m sha comment-body)
      :command.source/issue-comment
      (github/post-pr-comment m number comment-body))))

(defn try-user-then-installation
  "see if comment was made by a user that has authorized the bot to run commands as them
     run with token set to installation token if they're not authorized
     run with token set to oauth token if authorized"
  [request login installation-token h]
  ((-> (fn [{:keys [token person]}]
         (go
           (if person
             (log/info "using user token")
             (do
               (<! (consider-authorizing-your-user request))
               (log/info "using installation token")))
           (if (and token person)
             (<! (h request {:person person
                             :token token}))
             (<! (h request {:token installation-token})))))
       (api/extract-github-user-token-by-github-login))
   (assoc request :login login)))

(defn commit-error 
  "write an an error message (:bad-creds, :user-auth-only, or :client-error) back to user
    error is written as either a commit message or a pr comment depending on where the command
    originally arrived"
  [{{:command/keys [token repo command source] sha :push/sha number :label/number} :command :as request} [code message]]
  (let [m {:owner (:owner repo) :repo (:name repo) :token token}
        comment-body
        (case code
          :bad-creds (gstring/format
                      "Authorization failure running `/%s`. You can re-authorize your GitHub user from [this authorization link](%s)"
                      command (authorization-link
                               {:team-id (-> request :team :id)
                                :resource-provider-id (-> request :provider :id)}))
          :user-auth-only (gstring/format
                           "The `%s` command can only be run with User authorization.  You can authorize your GitHub user from [this authorization link](%s)"
                           command (authorization-link
                                    {:team-id (-> request :team :id)
                                     :resource-provider-id (-> request :provider :id)}))
          :client-error (gstring/format "unable to run `/%s`:\n\n%s" command message)
          (gstring/format "unable to run `/%s`:\n\n%s" command message))]
    (log/info "set error on " (or sha number))
    (case source
      :command.source/commit-message
      (github/post-commit-comment m sha comment-body)
      :command.source/issue-comment
      (github/post-pr-comment m number comment-body))))

