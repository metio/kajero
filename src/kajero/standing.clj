;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: 0BSD
(ns kajero.standing
  "An entry's standing, derived from the observations of its current
   revision on every read and never stored.

   One actor counts once per window, so repeating an observation adds
   nothing. A `failed` observation of the current revision disputes it until
   a new revision answers. An entry is stale once it went unconfirmed - no
   new revision and no `held` observation - for longer than the window of
   its `kind`."
  (:import (java.time Duration Instant)))

(def windows
  "How long an entry of each `kind` stays fresh without confirmation; a kind
   without a window never goes stale."
  {"fact" (Duration/ofDays 90)
   "howto" (Duration/ofDays 180)
   "gotcha" (Duration/ofDays 180)})

(def ^:private counting-window (Duration/ofDays 30))

(defn- independent
  "Observations that count: an actor's observation counts when it comes a
   counting window or more after that actor's last counted one."
  [observations]
  (->> observations
       (group-by :actor)
       vals
       (map (fn [obs]
              (->> (sort (map #(Instant/parse (:at %)) obs))
                   (reduce (fn [counted ^Instant t]
                             (if (or (empty? counted)
                                     (not (.isBefore t (.plus ^Instant (peek counted) counting-window))))
                               (conj counted t)
                               counted))
                           [])
                   count)))
       (reduce + 0)))

(defn- latest
  [observations]
  (when (seq observations) (reduce #(if (pos? (compare %1 %2)) %1 %2) (map :at observations))))

(defn derive-standing
  "The standing of `revision` given every observation of it, at `now`."
  [revision observations ^Instant now]
  (let [{held "held" failed "failed"} (group-by :outcome observations)
        confirmed (or (latest held) (:at revision))
        window (get windows (get-in revision [:labels "kind"]))]
    {:held (independent held)
     :failed (independent failed)
     :last-held (latest held)
     :last-failed (latest failed)
     :disputed? (boolean (seq failed))
     :stale? (boolean (and window (.isAfter now (.plus (Instant/parse confirmed) ^Duration window))))}))
