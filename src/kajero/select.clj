;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.select
  "Selectors pick entries, in the grammar of tik's --where: whitespace-
   separated terms, all ANDed, each optionally prefixed `not`; `and` is
   allowed as noise.

     label:kind                 the label is present
     label:kind=gotcha          present and equal
     actor=seb                  some revision or observation by seb
     ticket=<id>                some record cites tik:<id> as evidence
     schema-revision=<hash>     the data was checked against that schema revision
     outdated-schema            the data was checked against an older schema revision
     disputed | stale | unsigned | superseded | retired
     data:host                  some row of the data has the field
     data:host=w-1              some row has the field with that value
     ~text  (or a bare word)    title, body, labels or data contain it

   The rows of an entry's data are the maps of a list, or the data itself
   when it is one map. A field is a key, matched as a keyword or a string,
   and a dotted field follows nested maps (data:owner.team=dns). A value is
   compared with the field's printed form, a keyword without its colon, so
   data:cpus=8 and data:state=ready both match. The positive data terms of
   one selector must all hold in the same row; `not data:...` means no row
   matches.

   `compile` returns a predicate over a row
     {:labels {\"kind\" \"gotcha\"} :actors #{\"seb\"} :haystack \"...\" :rows [...]
      :disputed? bool :stale? bool :unsigned? bool :superseded? bool}
   or throws ex-info {:reason :select/bad-term}."
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str]))

(defn- field-value
  [row path]
  (reduce (fn [m seg]
            (when (map? m)
              (let [v (get m (keyword seg) ::none)]
                (if (= ::none v) (get m seg) v))))
          row
          (str/split path #"\.")))

(defn- printed [v]
  (cond (keyword? v) (subs (str v) 1)
        (string? v) v
        :else (pr-str v)))

(defn- data-term?
  [term]
  (str/starts-with? term "data:"))

(defn data-row-pred
  "The predicate a data: term asks of one row of the data."
  [term]
  (let [[k v] (str/split (subs term 5) #"=" 2)]
    (when (str/blank? k)
      (throw (ex-info (str "a data term needs a field: " term) {:reason :select/bad-term :term term})))
    (if (some? v)
      #(let [x (field-value % k)] (and (some? x) (= v (printed x))))
      #(some? (field-value % k)))))

(defn- term-pred
  [term]
  (cond
    (data-term? term)
    (let [p (data-row-pred term)] #(boolean (some p (:rows %))))

    (= term "disputed") :disputed?
    (= term "stale") :stale?
    (= term "unsigned") :unsigned?
    (= term "superseded") :superseded?
    (= term "retired") :retired?

    (str/starts-with? term "label:")
    (let [[k v] (str/split (subs term 6) #"=" 2)]
      (when (str/blank? k)
        (throw (ex-info (str "a label term needs a key: " term) {:reason :select/bad-term :term term})))
      (if (some? v)
        #(= v (get-in % [:labels k]))
        #(contains? (:labels %) k)))

    (str/starts-with? term "schema-revision=")
    (let [h (subs term 16)] #(= h (:schema-revision %)))

    (= term "outdated-schema") :outdated-schema?

    (str/starts-with? term "ticket=")
    (let [id (subs term 7)] #(contains? (:tickets %) id))

    (str/starts-with? term "actor=")
    (let [actor (subs term 6)] #(contains? (:actors %) actor))

    (str/includes? term "=")
    (throw (ex-info (str "unknown selector term: " term) {:reason :select/bad-term :term term}))

    :else
    (let [text (str/lower-case (str/replace term #"^~" ""))]
      #(str/includes? (:haystack % "") text))))

(defn- terms [selector]
  (remove #{"and" ""} (str/split (str/trim (or selector "")) #"\s+")))

(defn names?
  "Whether the selector has the term `term`, alone or after `not`."
  [selector term]
  (boolean (some #{term} (terms selector))))

(defn- text-term? [t]
  (and (not (contains? #{"not" "disputed" "stale" "unsigned" "superseded" "retired" "outdated-schema"} t))
       (not (str/includes? t "="))
       (not (str/starts-with? t "label:"))
       (not (data-term? t))))

(defn rows-pred
  "The predicate every positive data term of the selector asks of one row,
   or nil when it has none: the rows a search returns are the ones it holds
   for."
  [selector]
  (loop [[t & more] (terms selector) preds []]
    (cond
      (nil? t) (when (seq preds) (apply every-pred preds))
      (= t "not") (recur (rest more) preds)
      (data-term? t) (recur more (conj preds (data-row-pred t)))
      :else (recur more preds))))

(defn text-terms
  "The text the selector asks for, lower-cased and without `~`: its bare
   words and ~terms, leaving out the ones after `not`."
  [selector]
  (loop [[t & more] (terms selector) acc []]
    (cond
      (nil? t) acc
      (= t "not") (recur (rest more) acc)
      (text-term? t) (recur more (conj acc (str/lower-case (str/replace t #"^~" ""))))
      :else (recur more acc))))

(defn compile
  [selector]
  (let [same-row (rows-pred selector)]
    (loop [[t & more] (remove #{"and"} (str/split (str/trim (or selector "")) #"\s+"))
           preds []]
      (cond
        (nil? t) (let [preds (cond-> preds same-row (conj #(boolean (some same-row (:rows %)))))]
                   (fn [row] (every? #(% row) preds)))
        (str/blank? t) (recur more preds)
        (= t "not") (if-let [n (first more)]
                      (let [p (term-pred n)] (recur (rest more) (conj preds (complement p))))
                      (throw (ex-info "`not` needs a term after it" {:reason :select/bad-term :term t})))
        ;; a positive data term is checked through same-row above
        :else (recur more (cond-> preds (not (data-term? t)) (conj (term-pred t))))))))
