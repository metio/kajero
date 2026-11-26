;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.rank
  "Where the text terms of a selector matched an entry, how well, and the
   text around each match.

   An entry's text is four fields - title, labels, body and data - and a
   term is matched case-insensitively as a substring, as the selector does.
   A match in the title counts most and one in the data least, and repeats
   in one field add up to a cap, so a long body that repeats a word does
   not outrank a title that names it."
  (:require [clojure.string :as str])
  (:import (java.util.regex Matcher Pattern)))

(def fields
  "The fields searched, most telling first, with the weight of one match."
  [[:title 12] [:labels 6] [:body 2] [:data 1]])

(def ^:private repeat-cap
  "How many matches of one term in one field count."
  5)

(def ^:private context
  "How many characters of text a snippet keeps on either side of a match."
  60)

(defn- pattern ^Pattern [term]
  (Pattern/compile (Pattern/quote term) (bit-or Pattern/CASE_INSENSITIVE Pattern/UNICODE_CASE)))

(defn- occurrences
  "The start and end of every match of `p` in `text`."
  [^Pattern p ^String text]
  (let [^Matcher m (.matcher p text)]
    (loop [acc []]
      (if (.find m) (recur (conj acc [(.start m) (.end m)])) acc))))

(defn- one-line [s] (str/trim (str/replace s #"\s+" " ")))

(defn snippet
  "The text around the match [start end] of `text`, cut at word boundaries
   and marked with an ellipsis where it was cut."
  [^String text [start end]]
  (let [from (max 0 (- start context))
        to (min (count text) (+ end context))
        from (if (pos? from) (or (some #(when (Character/isWhitespace (.charAt text %)) (inc %))
                                       (range from start))
                                 from)
                 from)
        to (if (< to (count text)) (or (some #(when (Character/isWhitespace (.charAt text %)) %)
                                             (range to end -1))
                                       to)
               to)]
    (str (when (pos? from) "…") (one-line (subs text from to)) (when (< to (count text)) "…"))))

(defn compile-terms
  "The terms with their patterns, compiled once for every entry a search
   ranks: [[term pattern] ...]."
  [terms]
  (mapv (juxt identity pattern) terms))

(defn matches-compiled
  "matches over terms compiled by compile-terms."
  [text compiled]
  (vec (for [[term p] compiled
             :let [hit (some (fn [[field _]]
                               (when-let [o (first (occurrences p (get text field "")))]
                                 [field o]))
                             fields)]
             :when hit
             :let [[field o] hit]]
         {:term term :field (name field) :snippet (snippet (get text field "") o)})))

(defn score-compiled
  "score over terms compiled by compile-terms."
  [text compiled]
  (reduce + 0 (for [[_ p] compiled
                    [field weight] fields]
                (* weight (min repeat-cap (count (occurrences p (get text field ""))))))))

(defn matches
  "For each term, where it matched first: {:term :field :snippet}, the field
   being the most telling one that holds it. A term found nowhere has no
   entry. `text` maps each field to its string."
  [text terms]
  (matches-compiled text (compile-terms terms)))

(defn score
  "How well the terms match `text`: per term and field, the field's weight
   times its matches up to the cap."
  [text terms]
  (score-compiled text (compile-terms terms)))
