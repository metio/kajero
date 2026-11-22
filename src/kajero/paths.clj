;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.paths
  "The repository paths a text names, and whether two of them name the same
   file. An entry that cites `templates/_cert-issuer.tpl` is about the file
   somebody else knows as `helm-charts/cluster-info/templates/_cert-issuer.tpl`,
   so two paths name the same file when one ends with the other at a
   segment boundary."
  (:require [clojure.string :as str]))

(def ^:private token #"[A-Za-z0-9_.~+/-]+")

(def ^:private file-name #"[A-Za-z0-9_~+-][A-Za-z0-9_.~+-]*\.[A-Za-z0-9]{1,10}")

(defn- path-like?
  [t]
  (or (and (str/includes? t "/") (re-find #"[A-Za-z0-9_]" t))
      (re-matches file-name t)))

(defn cited
  "The set of path-like words in `text`: words with a slash, or a file
   name with an extension. Trailing punctuation and a :line suffix are not
   part of the path."
  [text]
  (into #{}
        (comp (map #(str/replace % #"[.]+$" ""))
              (filter path-like?))
        (re-seq token (or text ""))))

(defn- normal
  [p]
  (-> p str/trim (str/replace #"^(\./)+" "") (str/replace #":\d+(:\d+)?$" "")))

(defn same-file?
  "Whether paths `a` and `b` name the same file: equal, or one ends with
   the other at a segment boundary."
  [a b]
  (let [a (normal a) b (normal b)]
    (and (seq a) (seq b)
         (or (= a b) (str/ends-with? a (str "/" b)) (str/ends-with? b (str "/" a))))))
