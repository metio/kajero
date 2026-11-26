;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.derived
  "Values computed from records that never change, kept in bounded caches
   because every search would otherwise compute them again for every entry.

   A record's hash is cached by the record itself, and what a search reads
   from a revision by the revision's hash. A hash names exactly one content,
   so a cached value can never belong to another record, and the caches are
   shared by every notebook of the process.

   Revisions are written rarely and read by every search, so a revision
   written through a notebook has its values computed when it is written
   (see `warm-revision!`); a revision another process wrote has them
   computed by the first read that needs them."
  (:require [clojure.string :as str]
            [kajero.lru :as lru]
            [kajero.paths :as paths]
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

(defn cited-paths
  "The repository paths the revision `rev`, whose hash is `revision`, names
   in its title, body and evidence - see kajero.paths."
  [revision {:keys [title body evidence]}]
  (of-revision :paths revision #(into (paths/cited title) (mapcat paths/cited) (cons body evidence))))

(defn haystack
  "The lower-case text a ~text selector term looks in: title, body, data
   and labels of the revision `rev`, whose hash is `revision`."
  [revision {:keys [title body data labels]}]
  (of-revision :haystack revision
               #(str/lower-case (str/join " " (concat [title body (some-> data pr-str)]
                                                      (map (fn [l] (str/join "=" l)) labels))))))

(defn search-text
  "The searched text of the revision `rev`, whose hash is `revision`, by
   field, as kajero.rank reads it."
  [revision {:keys [title body data labels]}]
  (of-revision :text revision
               #(hash-map :title (or title "")
                          :labels (str/join " " (map (fn [l] (str/join "=" l)) (sort labels)))
                          :body (or body "")
                          :data (if (some? data) (pr-str data) ""))))

(defn warm-revision!
  "Computes and remembers every value derived from the revision `rev`.
   Returns its hash."
  [rev]
  (let [h (hash-of rev)]
    (cited-paths h rev)
    (haystack h rev)
    (search-text h rev)
    h))
