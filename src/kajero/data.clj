;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.data
  "Structured data in a revision, and the malli schemas that describe it.

   A revision may carry :data, any EDN value, beside its Markdown body.
   Agents read and write the data itself; the pages render it, a list of
   maps as a table. An entry labelled kind=schema holds a malli schema as its
   data, and a revision labelled schema=<entry> is checked against that
   schema's current revision when it is written.

   Schemas are data and never code: malli's evaluator is disabled, so a
   schema whose :fn names a function is refused instead of evaluated."
  (:require [briko.edn :as bedn]
            [malli.core :as m]
            [malli.error :as me]))

(def max-edn-bytes
  "The longest EDN text a client may send as data: the largest request
   body kajero.web reads."
  (* 16 1024 1024))

(defn read-edn
  "The one form EDN text `s` holds, read by briko.edn as data and nothing
   else: at most max-edn-bytes, nested at most 64 deep, and no tagged
   literal but #inst and #uuid, as clojure.edn reads them. Anything else
   is ex-info {:reason :kajero/bad-edn}. Every client's data passes here,
   so the store holds only data within those bounds."
  [s]
  (try (bedn/read-string s {:max-bytes max-edn-bytes :readers default-data-readers})
       (catch Exception e
         (throw (ex-info (str "the data is not EDN: " (ex-message e)) {:reason :kajero/bad-edn})))))

(def ^:private options {::m/disable-sci true})

(defn schema
  "The malli schema of `form`, or ex-info {:reason :kajero/bad-schema}."
  [form]
  (try
    (m/schema form options)
    (catch Exception e
      (throw (ex-info (str "the data is not a malli schema: " (or (:type (ex-data e)) (ex-message e)))
                      {:reason :kajero/bad-schema})))))

(defn explain
  "nil when `data` fits the schema, else its errors in plain words."
  [schema-form data]
  (some-> (m/explain (schema schema-form) data) me/humanize))

(def coverage-schema
  "The data of an entry labelled kind=coverage: the record that a question
   was asked, whatever it found. :population says what was examined - a
   sentence, a count or the list itself - and :asked is the day, so a
   reader can tell an unasked question from an unrecorded answer and judge
   whether a survey is too old or too narrow to lean on. :found may
   summarise or point at the answer entries."
  [:map
   [:question [:string {:min 3}]]
   [:population [:and some? [:not= ""]]]
   [:method [:string {:min 3}]]
   [:asked [:re #"^\d{4}-\d{2}-\d{2}"]]
   [:found {:optional true} any?]])

(defn columns
  "The columns of a table of maps: the keys of a map schema's entries in their
   order when one describes the rows, then every other key in the order the
   rows first use it."
  [rows schema-form]
  (let [declared (try
                   (let [s (schema schema-form)
                         row (if (#{:vector :sequential :set} (m/type s)) (first (m/children s)) s)]
                     (when (= :map (m/type row)) (mapv first (m/entries row))))
                   (catch Exception _ nil))]
    (into (vec declared) (comp (mapcat keys) (distinct) (remove (set declared))) rows)))

(defn table?
  "Whether the data reads best as a table: a non-empty list of maps."
  [data]
  (and (sequential? data) (seq data) (every? map? data)))
