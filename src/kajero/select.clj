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
     ~text  (or a bare word)    title, body or labels contain it

   `compile` returns a predicate over a row
     {:labels {\"kind\" \"gotcha\"} :actors #{\"seb\"} :haystack \"...\"
      :disputed? bool :stale? bool :unsigned? bool :superseded? bool}
   or throws ex-info {:reason :select/bad-term}."
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str]))

(defn- term-pred
  [term]
  (cond
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
       (not (str/starts-with? t "label:"))))

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
  (loop [[t & more] (remove #{"and"} (str/split (str/trim (or selector "")) #"\s+"))
         preds []]
    (cond
      (nil? t) (fn [row] (every? #(% row) preds))
      (str/blank? t) (recur more preds)
      (= t "not") (if-let [n (first more)]
                    (let [p (term-pred n)] (recur (rest more) (conj preds (complement p))))
                    (throw (ex-info "`not` needs a term after it" {:reason :select/bad-term :term t})))
      :else (recur more (conj preds (term-pred t))))))
