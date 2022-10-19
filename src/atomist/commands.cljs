(ns atomist.commands
  (:require [cljs.spec.alpha :as s]
            [atomist.commands.api :refer [run]]
            [goog.string :as gstring]
            [goog.string.format]
            [atomist.commands.pr]
            [atomist.commands.issue]
            [atomist.commands.label]
            [atomist.api.v2.log :as log]
            [cljs.core.async :refer [<!] :as async :refer-macros [go]]))

(defn run-commands [handler]
  (fn [request]
    (go
      (let [return-values (<! (->> (for [command (:commands request)]
                                     (run (assoc request :command command)))
                                   (async/merge)
                                   (async/reduce conj [])))]
        (log/info "return-values " return-values)
        (<! (handler (assoc request :status {:command-count (count return-values)
                                             :errors (->> (mapcat :errors return-values)
                                                          (filter identity)
                                                          (into []))
                                             :statuses (->> (map :status return-values)
                                                            (filter identity)
                                                            (into []))})))))))

(defn validate-command-spec [d]
  (when (not (s/valid? :command/spec d))
    (with-out-str (s/explain :command/spec d))))

(defn validate-commands [handler]
  (fn [request]
    (go
      (let [failures (->> (:commands request)
                          (map validate-command-spec)
                          (filter identity))]
        (if (seq failures)
          (assoc request :status {:errors failures})
          (<! (handler request)))))))

(defn command-status [handler]
  (fn [request]
    (go
      (let [{{:keys [command-count errors statuses]} :status commands :commands :as request} (<! (handler request))]
        (log/infof "statuses are %s" statuses)
        (log/infof "errors are %s" errors)
        (log/infof "ran %d commands" command-count)
        (assoc request
               :atomist/status
               {:status :completed
                :reason (cond (seq errors)
                              (->> (interpose "," errors) (apply str))
                              (seq statuses)
                              (gstring/format "command statuses %s" (->> (interpose "," statuses) (apply str)))
                              :else
                              (gstring/format "ran %s" (->> commands (map :command/command) (into []))))})))))

