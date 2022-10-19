(ns atomist.commands.pr
  (:require [atomist.shell :as shell]
            [cljs.core.async :refer [<!]]
            [atomist.commands.api :as api :refer [run]]
            [atomist.commands.auth :refer [try-user-then-installation commit-error]]
            [atomist.github :as github]
            [goog.string :as gstring]
            [goog.string.format]
            [atomist.api.v2.log :as log])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defmethod run "pr" [{{:command/keys [args token repo login]
                       :push/keys [branch]
                       :label/keys [number]} :command :as request}]
  (go
    (let [{{:keys [title base labels reviewers draft issue-number]} :options
           errors :errors
           just-args :arguments}
          (shell/raw-message->options {:raw_message args}
                                      [[nil "--title TITLE" "Pull Request Title"]
                                       [nil "--approve" "Approve Pull Request"]
                                       [nil "--request-changes" "request changes"]
                                       [nil "--draft"]
                                       [nil "--base BASE" "base branch ref"
                                        :default (or (:defaultBranch repo) "master")]
                                       api/label-parameter
                                       api/reviewer-parameter
                                       api/number-parameter])]
      (if (empty? errors)
        (let [p {:token token :owner (:owner repo) :repo (:name repo)}]
          (cond
            
            (and branch base (= branch base))
            (let [errors {:errors [(gstring/format "/pr branch and base can not be the same")]}]
              (<! (commit-error request [:client-error (-> errors :errors first)]))
              errors)

            ;; create when /pr --title --base and our current commit points at a branch (Commit message only)
            ;; if we don't know the branch, no pr would be created
            (and branch base title)
            (<! (try-user-then-installation request login token
                                            (fn [_ {:keys [token]}]
                                              (go
                                                (let [response (<! (github/post-pr
                                                                    {:token token :owner (:owner repo) :repo (:name repo)}
                                                                    (merge {:title title
                                                                            :body ""
                                                                            :head branch
                                                                            :base base}
                                                                           (if draft {:draft true}))
                                                                    labels))]
                                                  (cond (= "Bad credentials" (-> response :body :message))
                                                        (do
                                                          (<! (commit-error request [:bad-creds "Bad credentials"]))
                                                          response)
                                                        :else response))))))

            ;; close a PR from a comment
            ;; this also allows a PR to be closed from a commit comment to some other branch (why would that be useful?)
            (and (some #{"close"} just-args) (or number issue-number))
            (<!
             (try-user-then-installation request login token
                                         (fn [_ {:keys [token]}]
                                           (github/patch-pr-state (assoc p :token token) (or number issue-number) "closed"))))

            ;; close a PR from a commit to it's branch
            (and (some #{"close"} just-args) branch)
            (<!
             (try-user-then-installation request login token
                                         (fn [_ {:keys [token]}]
                                           (github/close-pr (assoc p :token token) branch))))

            ;; mark a PR READY from a Comment
            (and (some #{"ready"} just-args) (or number issue-number))
            (<!
             (try-user-then-installation request login token
                                         (fn [_ {:keys [person token]}]
                                           (go
                                             (if person
                                               (<! (github/pr-is-ready-by-number (assoc p :token token) (or number issue-number)))
                                               (let [errors {:errors ["github authorization required for marking PRs ready for review"]}]
                                                 (log/warn "can not mark PRs ready with an installation token")
                                                 (<! (commit-error request [:user-auth-only (-> errors :errors first)]))
                                                 errors))))))

            ;; mark a PR READY from a Commit to it's head branch
            (and (some #{"ready"} just-args) branch)
            (<!
             (try-user-then-installation request login token
                                         (fn [_ {:keys [person token]}]
                                           (go
                                             (if person
                                               (<! (github/pr-is-ready-by-branch (assoc p :token token) branch))
                                               (let [errors {:errors ["github authorization required for marking PRs ready for review"]}]
                                                 (log/warn "can not mark PRs ready with an installation token")
                                                 (<! (commit-error request [:user-auth-only (-> errors :errors first)]))
                                                 errors))))))

            ;(and (some #{"draft"} just-args) number)
            ;(<! (github/patch-pr-draft-state p number true))
            ;(and (some #{"draft"} just-args) branch)
            ;(<! (github/draft-pr p branch true))

            ;; TODO can we do anything with reviews?

            :else
            (let [errors {:errors [(gstring/format "bad arguments to /pr")]}]
              (<! (commit-error request [:client-error (-> errors :errors first)]))
              errors)))

        {:errors errors}))))

;; create --title --label --reviewer --project --base
;; close --repo number | branch | url
;; ready --repo number | branch | url
;; review --approve --body --comment --request-changes
