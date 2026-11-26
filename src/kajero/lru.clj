;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.lru
  "A bounded map that forgets the least recently used key first, safe to
   share between threads. Every cache of the server is one of these, so no
   cache grows with what clients ask for."
  (:import (java.util Iterator LinkedHashMap)))

(defn cache
  "An empty cache holding at most `bound` keys."
  [bound]
  {:bound (long bound) :map (LinkedHashMap. 16 0.75 true)})

(defn lookup
  "[value] when the cache holds `k`, else nil, so a cached nil is told
   apart from a missing key. A hit makes `k` the most recently used."
  [{:keys [^LinkedHashMap map]} k]
  (locking map
    (when (.containsKey map k)
      [(.get map k)])))

(defn put!
  "Stores `v` under `k`, forgetting the least recently used keys beyond the
   bound. Returns `v`."
  [{:keys [^LinkedHashMap map bound]} k v]
  (locking map
    (.put map k v)
    (let [^Iterator it (.iterator (.entrySet map))]
      (loop [n (.size map)]
        (when (and (> n (long bound)) (.hasNext it))
          (.next it)
          (.remove it)
          (recur (dec n))))))
  v)

(defn through
  "The value cached under `k`, computing it with `f` and caching it when
   missing. `f` runs outside the lock, so two threads may both compute a
   missing value; both get a correct one, as `f` must be a pure function of
   `k`."
  [c k f]
  (if-let [[v] (lookup c k)]
    v
    (put! c k (f))))

(defn size
  "How many keys the cache holds."
  [{:keys [^LinkedHashMap map]}]
  (locking map (.size map)))
