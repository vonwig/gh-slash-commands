(ns atomist.commands.issue
  (:require [atomist.shell :as shell]
            [cljs.core.async :refer [<!]]
            [atomist.github :as github]
            [goog.string.format]
            [atomist.commands.api :as api :refer [run]]
            [goog.string :as gstring]
            [clojure.string :as s]
            [atomist.api.v2.log :as log])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn- create-issue [{{:command/keys [repo message]} :command :as request} {:keys [labels title assignees]}]
  (github/create-issue request (:owner repo) (:name repo) {:title title :body message :labels labels :assignees assignees}))

(defn- close-issue [{{:command/keys [repo]} :command :as request} number]
  (github/patch-issue request (:owner repo) (:name repo) number {:state "closed"}))

(defn- lock-issue [{{:command/keys [repo]} :command :as request} number reason]
  (github/lock-issue request (:owner repo) (:name repo) number reason))

(defn- unlock-issue [{{:command/keys [repo]} :command :as request} number]
  (github/lock-issue request (:owner repo) (:name repo) number "unlock"))

(defn- args->issue-number [args]
  (->> (some #(re-find #"#(\d+)" %) args)
       second))

;; /issue create
;; /issue close --number 45
;; /issue close #45
;; these make sense in Commit messages
;; they can be called from Issue/PR comments as well
(def lock-reasons ["off topic" "too heated" "resolved" "spam"])

(defn- has-reason [& args]
  (let [with-spaces (apply str (interpose " " args))]
    (some #(if (s/includes? with-spaces %) %) lock-reasons)))

(defmethod run "issue" [{{:command/keys [args]
                          :label/keys [number]} :command :as request}]
  (go
    (let [{options :options errors :errors just-args :arguments}
          (shell/raw-message->options {:raw_message args}
                                      [api/label-parameter
                                       api/assignee-parameter
                                       api/number-parameter
                                       [nil "--title TITLE" "Issue Title"]
                                       [nil "--project PROJECT"]])]
      (if (empty? errors)
        (cond
          ;; create an issue using labels, title, and assignees from args
          (some #{"create"} just-args)
          (<! (create-issue request options))

          ;; close an issue when you can parse a #45 out of an issue
          (if-let [issue-number (args->issue-number just-args)]
            (and (some #{"close"} just-args) issue-number)) (<! (close-issue request (int (args->issue-number just-args))))
          ;; close an issue when a --number argument is explicitly given
          (and (some #{"close"} just-args) (:number options)) (<! (close-issue request (:number options)))
          ;; close an issue when you're making a comment on that issue, and the number has been extracted from the event
          (and (some #{"close"} just-args) number) (<! (close-issue request number))
          ;; lock an issue when you're making a commen on that issue
          (and (some #{"lock"} just-args) number) (if-let [reason (has-reason just-args)]
                                                    (do
                                                      (log/info "lock " reason)
                                                      (<! (lock-issue request number reason)))
                                                    (do
                                                      (log/warn "send post issue comment")
                                                      (<! (github/post-issue-comment
                                                           {:token (:token request)
                                                            :owner (-> request :ref :owner)
                                                            :repo (-> request :ref :repo)}
                                                           number
                                                           (gstring/format "Usage: /issue lock [%s]" (apply str (interpose "|" lock-reasons)))))))
          (and (some #{"unlock"} just-args) number) (<! (unlock-issue request number))
          :else {:errors ["/issue must specify either 'close', 'create', 'lock', or 'unlock'"]})
        {:errors errors}))))
