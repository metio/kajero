;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.cite
  "Which entries cite which, derived from records the notebook already
   holds, and how much a citation adds to a search rank.

   Entry A cites entry B when A's current revision names B in its body as
   [[<id or prefix>]], or when a `see-also` link runs from A to B. A
   `supersedes` or `contradicts` link points at a replaced or disputed
   entry and is no endorsement, so it does not count. Each citing entry
   counts once per target, however many references and links it holds; an
   entry never cites itself; and a retired or superseded entry cites
   nothing, because what it says has been withdrawn or replaced. Only the
   current revision is read, so a reference removed in a later revision
   stops counting and earlier revisions of one entry never add up.

   Nothing here is stored: the counts are recomputed from the revisions and
   links on every search."
  (:require [clojure.string :as str]))

(def max-boost
  "The most a search rank gains from citations. It is less than the gap
   between one match in the labels (6) and one in the body (2), so a cited
   entry mentioning a term once in its body stays below an entry carrying
   it in a label or the title, and citations decide only between close
   term scores."
  3)

(defn boost
  "What `n` citing entries add to a search rank: one per citing entry, up
   to max-boost."
  [n]
  (min max-boost n))

(def ^:private reference #"\[\[([0-9a-fA-F][0-9a-fA-F-]{3,35})\]\]")

(defn references
  "The ids and id prefixes `body` names as [[...]], as written."
  [body]
  (into #{} (map second) (re-seq reference (or body ""))))

(defn- resolver
  "A function from an id or prefix to the one id of `ids` it names, nil
   when it names none or several."
  [ids]
  (let [sorted (into (sorted-set) ids)]
    (fn [ref]
      (let [ref (str/lower-case ref)
            fits (take 2 (take-while #(str/starts-with? % ref) (subseq sorted >= ref)))]
        (when (= 1 (count fits)) (first fits))))))

(defn incoming
  "How many entries cite each entry, as {target n}, from `views`: every
   entry of the notebook as {:entry :body :links :retired :superseded?},
   :links being the {:source :type :target} links touching it."
  [views]
  (let [resolve (resolver (map :entry views))]
    (->> views
         (remove #(or (:retired %) (:superseded? %)))
         (mapcat (fn [{:keys [entry body links]}]
                   (->> (concat (keep resolve (references body))
                                (keep #(when (and (= "see-also" (:type %)) (= entry (:source %)))
                                         (:target %))
                                      links))
                        (remove #{entry})
                        distinct)))
         frequencies)))
