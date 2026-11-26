;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.derived
  "Values computed from records that never change, kept in bounded caches
   because every search would otherwise compute them again for every entry.

   A record's hash is cached by the record itself, and what a search reads
   from a revision by the revision's hash. A hash names exactly one content,
   so a cached value can never belong to another record, and the caches are
   shared by every notebook of the process."
  (:require [kajero.lru :as lru]
            [kajero.record :as record]))

(def hash-bound
  "The most records whose hash is remembered."
  65536)

(def revision-bound
  "The most values remembered per kind of value derived from a revision.
   It is well above the number of entries a notebook holds, so a search
   over all of them finds every value of the previous search."
  32768)

(defonce ^:private hashes (lru/cache hash-bound))

(defonce ^:private by-revision (atom {}))

(defn hash-of
  "record/hash-of, remembered per record."
  [rec]
  (lru/through hashes rec #(record/hash-of rec)))

(defn of-revision
  "The value of kind `k` for the revision whose hash is `revision`, computed
   by `f` from that revision alone when it is not remembered."
  [k revision f]
  (let [c (or (get @by-revision k)
              (get (swap! by-revision #(if (contains? % k) % (assoc % k (lru/cache revision-bound)))) k))]
    (lru/through c revision f)))
