(ns atomist.main
  (:require [cljs.core.async :refer [<!] :as async]
            [goog.string :as gstring]
            [goog.string.format]
            [clojure.data]
            [atomist.github]
            [atomist.commands :as commands]
            [atomist.api.v2.start :refer [start-http-listener]]
            [atomist.promise :refer [chan->promise]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

;; note: use (?m) to put regex parser in multiline mode so this recognizes a slash command anywhere in the body and
;; records arguments up to the next end of line
;; note: a body can have multiple commands
(defn- atomist-commands 
  "return command matches - capture command name without slash prefix and args"
  [s active-commands]
  (re-seq (re-pattern (gstring/format "(?m)/(%s) (.*)?"
                                      (->> (interpose "|" active-commands) (apply str)))) s))

(defn- result-with-parameters 
  "destructure the result and parameters out of the execution request"
  [{{{:keys [result] {:keys [parameters]} :configuration} :subscription} :context} cb]
  (let [active-commands (->> parameters (filter #(= "active-commands" (:name %))) first :value)
        default-color (->> parameters (filter #(= "default-label-color" (:name %))) first :value)]
    (cb result active-commands default-color)))

(defn- push-mode [[[{:git.commit/keys [message sha author repo] [{branch :git.ref/name}] :git.ref/_commit}]]
                  active-commands
                  default-color]
  (->> (for [[_ command args] (atomist-commands message active-commands)]
         (when command
           {:command/command command
            :command/args args
            :command/message message
            :command/token (-> repo :git.repo/org :github.org/installation-token)
            :command/source :command.source/commit-message
            :command/login (:git.user/login author)
            :command/repo {:owner (-> repo :git.repo/org :git.org/name)
                           :name (:git.repo/name repo)
                           :defaultBranch (:git.repo/default-branch repo)}
            :push/branch branch
            :push/sha sha
            :label/default-color default-color}))
       (filter identity)))

(defn- comment-mode [[[{:github.comment/keys [body issue author]}]]
                     active-commands
                     default-color]
  (->> (for [[_ command args] (atomist-commands body active-commands)]
         (when command
           (let [repo (or (-> issue :github.pullrequest/repo)
                          (-> issue :github.issue/repo))]
             {:command/command command
              :command/args args
              :command/message body
              :command/token (-> repo :git.repo/org :github.org/installation-token)
              :command/source :command.source/issue-comment
              :command/login (:git.user/login author)
              :command/repo {:owner (-> repo :git.repo/org :git.org/name)
                             :name (:git.repo/name repo)
                             :defaultBranch (:git.repo/default-branch repo)}
              :label/number (or (-> issue :github.pullrequest/number)
                                (-> issue :github.issue/number))
              :label/default-color default-color})))
       (filter identity)))

(defn check-push-or-comment-for-intents [handler]
  (fn [request]
    (go
      (let [commands (cond
                       (= "on_comment" (-> request :context :subscription :name)) (result-with-parameters request comment-mode)
                       (= "on_commit_message" (-> request :context :subscription :name)) (result-with-parameters request push-mode)
                       :else :none)]
        (if (seq commands)
          (<! (handler (assoc request :commands commands)))
          (assoc request :atomist/status {:state :completed
                                          :reason "no command intents"}))))))

(def handle-subscription
  (-> #(go %)
      (commands/run-commands)
      (commands/validate-commands)
      (commands/command-status)
      (check-push-or-comment-for-intents)))

(defn ^:export handler
  [& _]
  (start-http-listener (fn [r] (chan->promise (handle-subscription r)))))


