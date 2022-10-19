(ns atomist.commands.api 
  (:require [cljs.spec.alpha :as s]))

(def label-parameter ["-l" "--label LABEL" "add labels"
                      :id :labels
                      :default []
                      :assoc-fn (fn [m k v] (update-in m [k] conj v))])

(def reviewer-parameter ["-r" "--reviewer REVIEWER" "assign reviewers"
                         :id :reviewers
                         :default []
                         :assoc-fn (fn [m k v] (update-in m [k] conj v))])

(def number-parameter ["-n" "--number NUMBER" "Issue/PR number"
                       :id :issue-number])

(def assignee-parameter [nil "--assignee ASSIGNEE"
                         :id :assignees
                         :default []
                         :assoc-fn (fn [m k v] (update-in m [k] conj v))])

;; register new commands by adding new run defmethods
;;   that take a single arg conforming to :command/command and return a channel
;;   the channel should emit maps with :status or :errors
(defmulti run (comp :command/command :command))

;; will only have the sha on Commit messages
(s/def :push/sha string?)
(s/def :push/branch string?)
;; will only have the number for PR and Issue Comments
(s/def :label/number integer?)
(s/def :label/default-color string?)
;; whether it's an Issue/PR Comment or a Commit message, we'll have the following data
;; we'll pass args un processed to each command handler
(s/def :command/args string?)
(s/def :repo/owner string?)
(s/def :repo/name string?)
(s/def :repo/defaultBranch string?)
(s/def :command/repo (s/keys :req-un [:repo/owner :repo/name :repo/defaultBranch]))
(s/def :command/token string?)
;; the message refers to either the Commit message or the Pr/Issue Comment
(s/def :command/message string?)
(s/def :command/login string?)
(s/def :command/source #{:command.source/commit-message :command.source/issue-comment :command.source/commit-comment})
(s/def :command/base (s/keys :req [:command/command :command/args :command/token :command/repo :command/message :command/login :command/source]))

(s/def :command/command string?)
(defmulti command-type :command/command)
(defmethod command-type "pr" [_] (s/merge :command/base (s/keys :opt [:push/branch :label/number :push/sha])))
(defmethod command-type "label" [_] (s/merge :command/base (s/keys :req [:label/number :label/default-color])))
(defmethod command-type "issue" [_] :command/base)
(s/def :command/spec (s/multi-spec command-type :command/command))

