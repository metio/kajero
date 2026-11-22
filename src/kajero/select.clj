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
     path=templates/a.tpl       the title, body or evidence names that file
     link=<id>                  linked to that entry, either direction
     link:see-also=<id>         linked to it by that type
     schema-revision=<hash>     the data was checked against that schema revision
     outdated-schema            the data was checked against an older schema revision
     disputed | stale | unsigned | superseded | retired
     data:host                  the data has the field
     data:hosts.domain=w-1      the field, through maps and lists, has the value
     ~text  (or a bare word)    title, body, labels or data contain it

   kajero.data-query says how a data: term walks the data. The positive
   data terms of one selector must hold together - two terms about the
   elements of one list in the same element - and `not data:...` means the
   term holds nowhere.

   `compile` returns a predicate over a row
     {:labels {\"kind\" \"gotcha\"} :actors #{\"seb\"} :links #{[\"see-also\" id]} :haystack \"...\" :data ...
      :disputed? bool :stale? bool :unsigned? bool :superseded? bool}
   or throws ex-info {:reason :select/bad-term}."
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str]
            [kajero.data-query :as dq]
            [kajero.paths :as paths]))

(defn- data-term?
  [term]
  (str/starts-with? term "data:"))

(def link-types
  "The types a link between two entries has."
  #{"supersedes" "see-also" "contradicts"})

(defn- link-pred
  "link=<id> holds for an entry linked to <id> in either direction, and
   link:<type>=<id> for one linked by that type. <id> may be a prefix of
   four or more characters, as everywhere else an entry is named."
  [term]
  (let [[lhs id] (str/split term #"=" 2)
        type (second (str/split lhs #":" 2))
        bad #(throw (ex-info (str % ": " term) {:reason :select/bad-term :term term}))]
    (when (and type (not (link-types type)))
      (bad (str "a link type is one of " (str/join ", " (sort link-types)))))
    (when (< (count (str/trim (or id ""))) 4)
      (bad "a link term names an entry by four or more characters of its id"))
    (fn [row] (boolean (some (fn [[t other]] (and (or (nil? type) (= type t))
                                                  (str/starts-with? other id)))
                             (:links row))))))

(defn- term-pred
  [term]
  (cond
    (data-term? term)
    (let [q (dq/parse term)] #(boolean (seq (dq/contexts (:data %) q))))

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

    (str/starts-with? term "path=")
    (let [p (subs term 5)]
      (when (str/blank? p)
        (throw (ex-info (str "a path term needs a path: " term) {:reason :select/bad-term :term term})))
      (fn [row] (boolean (some #(paths/same-file? % p) (:paths row)))))

    (or (str/starts-with? term "link=") (str/starts-with? term "link:"))
    (link-pred term)

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

(defn data-terms
  "The positive data: terms of the selector, parsed, leaving out the ones
   after `not`."
  [selector]
  (loop [[t & more] (terms selector) acc []]
    (cond
      (nil? t) acc
      (= t "not") (recur (rest more) acc)
      (data-term? t) (recur more (conj acc (dq/parse t)))
      :else (recur more acc))))

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
  (let [together (not-empty (data-terms selector))]
    (loop [[t & more] (remove #{"and"} (str/split (str/trim (or selector "")) #"\s+"))
           preds []]
      (cond
        (nil? t) (let [preds (cond-> preds together (conj #(boolean (seq (dq/matching (:data %) together)))))]
                   (fn [row] (every? #(% row) preds)))
        (str/blank? t) (recur more preds)
        (= t "not") (if-let [n (first more)]
                      (let [p (term-pred n)] (recur (rest more) (conj preds (complement p))))
                      (throw (ex-info "`not` needs a term after it" {:reason :select/bad-term :term t})))
        ;; a positive data term is checked through `together` above
        :else (recur more (cond-> preds (not (data-term? t)) (conj (term-pred t))))))))
