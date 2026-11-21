;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.tik
  "tik tickets as evidence. An evidence item of the form tik:<ticket id> cites
   a ticket of the tik store the notebook is bound to; the citation is checked
   when it is written, and pages show the ticket's title and the stages it
   derives now, read from `tik status` at the time of reading."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as sh]))

(def ^:private ref-pattern #"^tik:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$")

(defn ticket-ids
  "The ticket ids an evidence list cites, in order."
  [evidence]
  (into [] (comp (keep #(second (re-matches ref-pattern (str %)))) (distinct)) evidence))

(defn status-in
  "A function from a ticket id to {:ticket :title :current :process}, read
   with the tik CLI from the store at `tik-root`, or nil when the store holds
   no such ticket."
  [tik-root]
  (fn [id]
    (let [{:keys [exit out]} (sh/sh "tik" "status" id "--edn"
                                    :env (assoc (into {} (System/getenv)) "TIK_ROOT" (str tik-root)))]
      (when (zero? exit)
        (let [s (edn/read-string {:default (fn [_ v] v)} out)]
          {:ticket id
           :title (:title s)
           :current (mapv name (:current s))
           :process (some-> (get-in s [:rules :process]) name)})))))
