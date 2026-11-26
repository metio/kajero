;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.tik
  "tik tickets as evidence. An evidence item of the form tik:<ticket id> cites
   a ticket of the tik store the notebook is bound to; the citation is checked
   when it is written, and pages show the ticket's title and the stages it
   derives now, read from `tik status` at the time of reading, or up to
   `display-ttl-ms` before it."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [kajero.lru :as lru]))

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

(defprotocol Lookup
  (for-display [lookup id]
    "The ticket `id` to show beside an entry, or nil when there is none.")
  (for-write [lookup id]
    "The ticket `id` when it exists, for checking a citation before it is
     written, or nil when it does not."))

(extend-protocol Lookup
  clojure.lang.IFn
  (for-display [f id] (f id))
  (for-write [f id] (f id)))

(def display-ttl-ms
  "How long a ticket read for display is shown before it is read again.
   Each `tik status` is a process of about 40 ms, and an entry page shows
   every ticket its records cite."
  60000)

(def cache-bound
  "The most tickets remembered."
  1024)

(defn cached
  "The lookup `status` - a function from a ticket id to the ticket or nil -
   remembering its answers for display for `ttl-ms`, at most `bound` of
   them. A citation is checked for a write against a remembered answer only
   when that answer found the ticket; a ticket remembered as missing is
   asked again, so a ticket made a moment ago is never refused from memory.
   `clock` answers milliseconds, for tests."
  [status & [{:keys [ttl-ms bound clock] :or {ttl-ms display-ttl-ms bound cache-bound}}]]
  (let [c (lru/cache bound)
        clock (or clock #(System/currentTimeMillis))
        remembered (fn [id]
                     (when-let [[{:keys [at ticket]}] (lru/lookup c id)]
                       (when (< (- (long (clock)) (long at)) (long ttl-ms)) [ticket])))
        ask! (fn [id] (let [at (clock) t (status id)] (lru/put! c id {:at at :ticket t}) t))]
    (reify Lookup
      (for-display [_ id]
        (if-let [[t] (remembered id)] t (ask! id)))
      (for-write [_ id]
        (or (first (remembered id)) (ask! id))))))
