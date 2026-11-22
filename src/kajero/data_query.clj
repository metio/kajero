;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.data-query
  "Where a data: term holds inside an entry's data, and which elements a
   set of them picks.

   A term names a dotted field and optionally a value. Walking the field
   through the data, a map step reads the key, as a keyword or a string; a
   list step tries every map in the list, so data:hosts.domain=x holds when
   some host has that domain. At the end of the field, a value compares
   with the printed form, a keyword without its colon, and a list of plain
   values matches when any element does.

   Every place a term holds is a context: the get-in path of the element it
   holds in, the deepest list element the walk passed through, or the data
   itself. A set of terms holds together at a context when each of them
   holds at that context or at one above it, so two terms about hosts must
   hold for the same host, while a term about the whole reading holds for
   any host of it. The elements picked are the ones at the deepest contexts
   where all of them hold."
  (:require [clojure.string :as str]))

(defn- printed [v]
  (cond (keyword? v) (subs (str v) 1)
        (string? v) v
        :else (pr-str v)))

(defn- lookup
  "The value of `seg` in map `m` and the key that holds it, a keyword before
   a string, or nil when neither does."
  [m seg]
  (let [kw (keyword seg)]
    (cond (contains? m kw) [kw (get m kw)]
          (contains? m seg) [seg (get m seg)])))

(defn- end-holds? [value v]
  (cond (nil? v) false
        (nil? value) true
        (and (sequential? v) (not-any? coll? v)) (boolean (some #(= value (printed %)) v))
        :else (= value (printed v))))

(defn- walk
  [node ctx [seg & more] value]
  (when (map? node)
    (when-let [[k v] (lookup node seg)]
      (cond
        (empty? more) (when (end-holds? value v) [ctx])
        (map? v) (walk v ctx more value)
        (sequential? v) (into [] (comp (map-indexed vector)
                                       (mapcat (fn [[i e]] (walk e (conj ctx k i) more value))))
                              v)
        :else nil))))

(defn- roots
  "The top-level elements of the data with their contexts: the data itself
   when it is a map, each map of it when it is a list."
  [data]
  (cond (map? data) [[[] data]]
        (sequential? data) (into [] (keep-indexed (fn [i e] (when (map? e) [[i] e]))) data)
        :else []))

(defn parse
  "{:field \"hosts.domain\" :value \"x\"} of a data: term, the value nil for a
   presence term, or ex-info {:reason :select/bad-term} when it names no
   field."
  [term]
  (let [[field value] (str/split (subs term 5) #"=" 2)]
    (when (or (str/blank? field) (some str/blank? (str/split field #"\." -1)))
      (throw (ex-info (str "a data term needs a field: " term) {:reason :select/bad-term :term term})))
    {:field field :value value}))

(defn contexts
  "Every context at which the parsed term holds in `data`, in document
   order."
  [data {:keys [field value]}]
  (let [segs (str/split field #"\.")]
    (into [] (mapcat (fn [[ctx node]] (walk node ctx segs value))) (roots data))))

(defn- prefix? [p c]
  (and (<= (count p) (count c)) (= p (subvec c 0 (count p)))))

(defn matching
  "The elements of `data` the parsed terms pick together: the ones at the
   deepest contexts where every term holds at that context or above it, in
   the order the terms find them, which is document order whenever one
   term reaches the deepest list. Empty when they never hold together."
  [data terms]
  (let [per-term (mapv #(contexts data %) terms)
        candidates (distinct (apply concat per-term))
        together (filterv (fn [c] (every? (fn [cs] (some #(prefix? % c) cs)) per-term)) candidates)
        deepest (remove (fn [c] (some #(and (not= % c) (prefix? c %)) together)) together)]
    (mapv #(if (empty? %) data (get-in data %)) deepest)))
