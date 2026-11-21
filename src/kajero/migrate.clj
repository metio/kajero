;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.migrate
  "Declarative migrations from one schema to another.

   An entry labelled kind=migration holds, as its data,

     {:from \"<schema entry>\" :to \"<schema entry>\"
      :steps [[:rename :old :new]          ; a key gets a new name
              [:drop :k]                   ; a key goes away
              [:default :k value]          ; a missing key gets a value
              [:set :k value]              ; a key gets a value, always
              [:map-values :k {old new}]   ; listed values of a key change
              [:move [:a :b] [:c]]]}       ; a value moves to another path

   The steps apply to every map of a list of maps, or to a single map. They
   are data and never code: a migration can not run a function, so applying
   one can not do more than rewrite the data it is given."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def ^:private path [:vector {:min 1} :any])

(def ^:private step
  [:or
   [:tuple [:= :rename] :any :any]
   [:tuple [:= :drop] :any]
   [:tuple [:= :default] :any :any]
   [:tuple [:= :set] :any :any]
   [:tuple [:= :map-values] :any [:map-of :any :any]]
   [:tuple [:= :move] path path]])

(def migration
  "The shape of a kind=migration entry's data."
  [:map {:closed true}
   [:from [:string {:min 1}]]
   [:to [:string {:min 1}]]
   [:steps [:vector step]]])

(defn explain
  "nil when `data` is a migration, else its errors in plain words."
  [data]
  (some-> (m/explain migration data) me/humanize))

(defn- dissoc-in [m [k & ks]]
  (if (seq ks)
    (if (map? (get m k)) (update m k dissoc-in ks) m)
    (dissoc m k)))

(defn- apply-step [row [op a b]]
  (if-not (map? row)
    row
    (case op
      :rename (if (contains? row a) (-> row (dissoc a) (assoc b (get row a))) row)
      :drop (dissoc row a)
      :default (if (contains? row a) row (assoc row a b))
      :set (assoc row a b)
      :map-values (if (and (contains? row a) (contains? b (get row a))) (assoc row a (get b (get row a))) row)
      :move (let [v (get-in row a ::absent)]
              (if (= ::absent v) row (-> row (dissoc-in a) (assoc-in b v)))))))

(defn run
  "`data` after the migration's steps, applied to each map of a list of maps
   or to a single map; anything else passes through unchanged."
  [{:keys [steps]} data]
  (let [one (fn [row] (reduce apply-step row steps))]
    (cond
      (map? data) (one data)
      (sequential? data) (mapv one data)
      :else data)))
