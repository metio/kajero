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
     disputed | stale | superseded | retired
     unsigned                   the current revision carries no signature
     bad-signature              it carries one that does not verify against
                                the actors file - see kajero.verify
     unverified                 either: its signature does not verify
     missed                     a search did not find the current revision
                                although it held the answer: the entry
                                needs the words a searcher would use
     coverage                   a kind=coverage entry: the record that a
                                question was asked, with its population,
                                method and day - `coverage label:system=dns`
                                lists what has been surveyed about DNS
     never-returned             no search has returned the entry since the
                                counting began; never-returned=2026-11-01
                                since that day - see kajero.usage
     returned-unopened          searches returned the entry at least 3
                                times and no session opened it after one of
                                them; returned-unopened=10 at least 10 times
     data:host                  the data has the field
     data:hosts.domain=w-1      the field, through maps and lists, has the value
     ~text  (or a bare word)    title, body, labels or data contain it
     a|b|c                      any of the terms holds: `calico|coredns`,
                                `label:kind=gotcha|label:kind=fact`

   An alternation is one term among the ANDed ones, and `not a|b` holds
   when none of them does. Its parts may be any term except a data: term,
   whose terms must hold together in one element, which a choice between
   them cannot promise. The text alternatives all count towards ranking, so
   an entry holding more of them comes first.

   kajero.data-query says how a data: term walks the data. The positive
   data terms of one selector must hold together - two terms about the
   elements of one list in the same element - and `not data:...` means the
   term holds nowhere.

   `compile` returns a predicate over a row
     {:labels {\"kind\" \"gotcha\"} :actors #{\"seb\"} :links #{[\"see-also\" id]} :haystack \"...\" :data ...
      :disputed? bool :stale? bool :missed? bool :unsigned? bool :bad-signature? bool
      :unverified? bool :superseded? bool}
   or throws ex-info {:reason :select/bad-term}. `reads` names the keys of
   the row the predicate reads, so a caller computes only those."
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str]
            [kajero.data-query :as dq]
            [kajero.paths :as paths]
            [kajero.usage :as usage])
  (:import (java.time LocalDate)
           (java.time.format DateTimeParseException)))

(defn- reading
  "`pred` as a predicate that declares the row keys `ks` it reads. The
   wrapper is a fresh function, so a keyword or a shared predicate can
   carry the declaration."
  [ks pred]
  #_{:splint/disable [lint/fn-wrapper]}
  (with-meta (fn [row] (pred row)) {::reads (set ks)}))

(defn reads
  "The row keys a predicate made here reads; a predicate `compile`
   returned reads no other key of its row."
  [pred]
  (::reads (meta pred) #{}))

(defn- alternation? [term] (str/includes? term "|"))

(defn- data-term?
  [term]
  (and (str/starts-with? term "data:") (not (alternation? term))))

(defn- alternatives
  "The parts of an alternation, refusing an empty part or a data: term."
  [term]
  (let [parts (str/split term #"\|" -1)
        bad #(throw (ex-info (str % ": " term) {:reason :select/bad-term :term term}))]
    (when (some str/blank? parts)
      (bad "an alternation has an empty part"))
    (when (some #(str/starts-with? % "data:") parts)
      (bad "a data: term cannot be one alternative among others"))
    parts))

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
    (reading #{:links}
             (fn [row] (boolean (some (fn [[t other]] (and (or (nil? type) (= type t))
                                                           (str/starts-with? other id)))
                                      (:links row)))))))

(def usage-terms
  "The terms that read the usage counts beside the notebook."
  #{"never-returned" "returned-unopened"})

(def returned-unopened-default
  "How often an entry must have been returned for returned-unopened to
   pick it, when the term names no number."
  3)

(defn- usage-term
  "The usage term `term` names, with or without its =argument, or nil."
  [term]
  (usage-terms (first (str/split term #"=" 2))))

(defn- bad-term!
  [message term]
  (throw (ex-info (str message ": " term) {:reason :select/bad-term :term term})))

(defn- never-returned-pred
  [term]
  (let [[_ since] (str/split term #"=" 2)]
    (when (some? since)
      (try (LocalDate/parse since)
           (catch DateTimeParseException _
             (bad-term! "never-returned= takes a day as YYYY-MM-DD" term))))
    (reading #{:usage} (fn [row] (zero? (usage/returned-since (:usage row) since))))))

(defn- returned-unopened-pred
  [term]
  (let [[_ n] (str/split term #"=" 2)
        at-least (if (nil? n)
                   returned-unopened-default
                   (or (when (re-matches #"[1-9][0-9]{0,8}" n) (parse-long n))
                       (bad-term! "returned-unopened= takes a whole number of at least 1" term)))]
    (reading #{:usage}
             (fn [row]
               (let [u (:usage row)]
                 (and (>= (usage/total u :returned) at-least)
                      (zero? (usage/total u :opened-after-return))))))))

(def ^:private flag-terms
  "The terms that read one boolean of the row, by the key they read."
  {"disputed" :disputed? "stale" :stale? "unsigned" :unsigned? "bad-signature" :bad-signature?
   "unverified" :unverified? "superseded" :superseded? "retired" :retired? "missed" :missed?
   "outdated-schema" :outdated-schema?})

(defn- term-pred
  [term]
  (cond
    (alternation? term)
    (let [preds (mapv term-pred (alternatives term))]
      (reading (into #{} (mapcat reads) preds)
               (fn [row] (boolean (some #(% row) preds)))))

    (data-term? term)
    (let [q (dq/parse term)] (reading #{:data} #(boolean (seq (dq/contexts (:data %) q)))))

    (flag-terms term) (let [k (flag-terms term)] (reading #{k} k))
    (= term "coverage") (reading #{:labels} #(= "coverage" (get-in % [:labels "kind"])))

    (= "never-returned" (usage-term term)) (never-returned-pred term)
    (= "returned-unopened" (usage-term term)) (returned-unopened-pred term)

    (str/starts-with? term "label:")
    (let [[k v] (str/split (subs term 6) #"=" 2)]
      (when (str/blank? k)
        (throw (ex-info (str "a label term needs a key: " term) {:reason :select/bad-term :term term})))
      (reading #{:labels}
               (if (some? v)
                 #(= v (get-in % [:labels k]))
                 #(contains? (:labels %) k))))

    (str/starts-with? term "schema-revision=")
    (let [h (subs term 16)] (reading #{:schema-revision} #(= h (:schema-revision %))))

    (str/starts-with? term "ticket=")
    (let [id (subs term 7)] (reading #{:tickets} #(contains? (:tickets %) id)))

    (str/starts-with? term "path=")
    (let [p (subs term 5)]
      (when (str/blank? p)
        (throw (ex-info (str "a path term needs a path: " term) {:reason :select/bad-term :term term})))
      (reading #{:paths} (fn [row] (boolean (some #(paths/same-file? % p) (:paths row))))))

    (or (str/starts-with? term "link=") (str/starts-with? term "link:"))
    (link-pred term)

    (str/starts-with? term "actor=")
    (let [actor (subs term 6)] (reading #{:actors} #(contains? (:actors %) actor)))

    (str/includes? term "=")
    (throw (ex-info (str "unknown selector term: " term) {:reason :select/bad-term :term term}))

    :else
    (let [text (str/lower-case (str/replace term #"^~" ""))]
      (reading #{:haystack} #(str/includes? (:haystack % "") text)))))

(defn- terms [selector]
  (remove #{"and" ""} (str/split (str/trim (or selector "")) #"\s+")))

(defn names?
  "Whether the selector has the term `term`, alone or after `not`."
  [selector term]
  (boolean (some #{term} (terms selector))))

(defn reads-usage?
  "Whether the selector has a usage term anywhere, after `not` or in an
   alternation too: such a search is a report on the counts."
  [selector]
  (boolean (some usage-term (mapcat #(str/split % #"\|") (terms selector)))))

(defn retrieval?
  "Whether a search with this selector counts as returning its entries: it
   names at least one term, so it is not a listing of every entry, and it
   is no report on the counts, which would count the entries it reports."
  [selector]
  (and (boolean (seq (terms selector))) (not (reads-usage? selector))))

(defn- text-term? [t]
  (and (not (contains? #{"not" "disputed" "stale" "unsigned" "bad-signature" "unverified" "superseded" "retired" "outdated-schema" "coverage" "missed"} t))
       (not (usage-term t))
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
   words and ~terms, each text alternative of an alternation among them,
   leaving out the ones after `not`."
  [selector]
  (letfn [(text [t] (str/lower-case (str/replace t #"^~" "")))]
    (loop [[t & more] (terms selector) acc []]
      (cond
        (nil? t) acc
        (= t "not") (recur (rest more) acc)
        (alternation? t) (recur more (into acc (map text (filter text-term? (alternatives t)))))
        (text-term? t) (recur more (conj acc (text t)))
        :else (recur more acc)))))

(defn compile
  "The predicate over a row that holds when every term of the selector
   holds. It declares the row keys its terms read - see `reads` - so a
   caller computes only those; the terms are tried in order and the first
   that fails ends the check."
  [selector]
  (let [together (not-empty (data-terms selector))]
    (loop [[t & more] (remove #{"and"} (str/split (str/trim (or selector "")) #"\s+"))
           preds []]
      (cond
        (nil? t) (let [preds (cond-> preds
                               together (conj (reading #{:data} #(boolean (seq (dq/matching (:data %) together))))))]
                   (reading (into #{} (mapcat reads) preds)
                            (fn [row] (every? #(% row) preds))))
        (str/blank? t) (recur more preds)
        (= t "not") (if-let [n (first more)]
                      (let [p (term-pred n)] (recur (rest more) (conj preds (reading (reads p) (complement p)))))
                      (throw (ex-info "`not` needs a term after it" {:reason :select/bad-term :term t})))
        ;; a positive data term is checked through `together` above
        :else (recur more (cond-> preds (not (data-term? t)) (conj (term-pred t))))))))
