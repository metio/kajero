;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.lru-test
  (:require [clojure.test :refer [deftest is testing]]
            [kajero.derived :as derived]
            [kajero.lru :as lru]
            [kajero.record :as record]))

(deftest a-cache-holds-at-most-its-bound
  (let [c (lru/cache 3)]
    (doseq [i (range 10)] (lru/put! c i (* i i)))
    (is (= 3 (lru/size c)))
    (is (= [[49] [64] [81]] (map #(lru/lookup c %) [7 8 9])) "the most recent keys stay")
    (is (nil? (lru/lookup c 0)) "the oldest went first")))

(deftest a-lookup-keeps-a-key-from-eviction
  (let [c (lru/cache 2)]
    (lru/put! c :a 1)
    (lru/put! c :b 2)
    (lru/lookup c :a)
    (lru/put! c :c 3)
    (is (= [1] (lru/lookup c :a)) "the key read last is the most recently used")
    (is (nil? (lru/lookup c :b)) "the least recently used key went")))

(deftest a-cached-nil-is-a-hit
  (let [c (lru/cache 2)
        calls (atom 0)]
    (is (nil? (lru/through c :missing #(do (swap! calls inc) nil))))
    (is (nil? (lru/through c :missing #(do (swap! calls inc) nil))))
    (is (= 1 @calls))
    (is (= [nil] (lru/lookup c :missing)))))

(deftest through-computes-once-per-key
  (let [c (lru/cache 10)
        calls (atom 0)
        f #(do (swap! calls inc) :v)]
    (dotimes [_ 5] (lru/through c :k f))
    (is (= 1 @calls))))

(deftest a-cache-stays-bounded-under-threads
  (let [c (lru/cache 100)]
    (doall (pmap (fn [t] (dotimes [i 1000] (lru/put! c [t i] i) (lru/lookup c [t (quot i 2)]))) (range 8)))
    (is (= 100 (lru/size c)))))

(deftest a-remembered-hash-is-the-hash
  (let [rec {:entry "e" :seq 1 :title "T" :labels {"b" "2" "a" "1"} :actor "seb"}]
    (is (= (record/hash-of rec) (derived/hash-of rec) (derived/hash-of rec)))
    (testing "an equal record read again has the same hash, a signature makes no difference"
      (is (= (record/hash-of rec) (derived/hash-of (assoc (into {} rec) :sig (byte-array 3))))))
    (is (not= (derived/hash-of rec) (derived/hash-of (assoc rec :title "U"))))))

(deftest a-value-derived-from-a-revision-is-computed-once
  (let [calls (atom 0)
        rev (str "sha256-test-" (random-uuid))]
    (dotimes [_ 3] (is (= #{"a.clj"} (derived/of-revision :paths rev #(do (swap! calls inc) #{"a.clj"})))))
    (is (= 1 @calls))
    (is (= :other (derived/of-revision :haystack rev (constantly :other))) "each kind of value is kept apart")))
