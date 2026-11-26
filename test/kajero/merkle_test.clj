;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.merkle-test
  "Properties of the Merkle tree over generated sets of leaves: the digest
   depends on the set alone, every change of the set changes it, and the
   tree built leaf by leaf equals the tree built in one pass."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kajero.merkle :as merkle])
  (:import (java.util Collections HexFormat Random)))

(defn- rand-hash [^Random r]
  (let [b (byte-array 32)]
    (.nextBytes r b)
    (str "sha256-" (.formatHex (HexFormat/of) b))))

(def ^:private verdicts ["verified:canonical" "verified:legacy" "unsigned" "bad-signature"])

(defn- rand-nth* [^Random r xs] (nth xs (.nextInt r (count xs))))

(defn- rand-leaf [^Random r]
  (let [kind (rand-nth* r [:revision :observation :link :page])]
    {:hash (rand-hash r)
     :sig (when (pos? (.nextInt r 4)) (rand-hash r))
     :verdict (rand-nth* r verdicts)
     :kind kind
     :entries (case kind
                :page []
                :link [(str (java.util.UUID. (.nextLong r) (.nextLong r))) (str (java.util.UUID. (.nextLong r) (.nextLong r)))]
                [(str (java.util.UUID. (.nextLong r) (.nextLong r)))])}))

(defn- leaves [seed n]
  (let [r (Random. seed)] (vec (repeatedly n #(rand-leaf r)))))

(defn- shuffled [seed xs]
  (let [l (java.util.ArrayList. ^java.util.Collection xs)]
    (Collections/shuffle l (Random. seed))
    (vec l)))

(defn- shape
  "The nodes of a tree with their digests as hex, comparable by value."
  [node]
  (when node
    (cond-> {:n (:n node) :digest (.formatHex (HexFormat/of) ^bytes (:digest node))}
      (:kids node) (assoc :kids (mapv shape (:kids node)))
      (:leaves node) (assoc :leaves (vec (keys (:leaves node)))))))

(def ^:private sizes
  "Sizes on both sides of a split and of several levels of the trie."
  [0 1 2 31 32 33 64 100 511 512 513 2000])

(deftest the-digest-does-not-depend-on-the-order-of-insertion
  (doseq [n sizes seed (range 5)]
    (let [ls (leaves (+ (* 1000 n) seed) n)
          d (merkle/digest (merkle/add-all merkle/empty-tree ls))]
      (is (= d (merkle/digest (merkle/add-all merkle/empty-tree (shuffled seed ls)))) (str n " leaves, seed " seed))
      (is (= d (merkle/digest (merkle/add-all merkle/empty-tree (reverse ls))))))))

(deftest the-incremental-tree-equals-the-tree-built-in-one-pass
  (doseq [n sizes seed (range 5)]
    (let [ls (leaves (+ (* 7000 n) seed) n)
          incremental (merkle/add-all merkle/empty-tree ls)
          scratch (merkle/build (shuffled seed ls))]
      (is (= (merkle/digest incremental) (merkle/digest scratch)) (str n " leaves, seed " seed))
      (is (= (shape incremental) (shape scratch)) "the same nodes, not only the same digest")
      (is (= n (merkle/size incremental) (merkle/size scratch))))))

(deftest adding-a-tree-to-a-built-tree-equals-building-both
  (doseq [n [0 40 700] m [1 33 300]]
    (let [ls (leaves (+ n m) (+ n m))
          [a b] (split-at n ls)]
      (is (= (merkle/digest (merkle/build ls))
             (merkle/digest (merkle/add-all (merkle/build a) b)))))))

(deftest adding-one-leaf-changes-the-digest
  (let [r (Random. 42)]
    (doseq [n sizes]
      (let [ls (leaves (+ 99 n) n)
            t (merkle/build ls)
            more (merkle/add t (rand-leaf r))]
        (is (not= (merkle/digest t) (merkle/digest more)) (str "a leaf added to " n))
        (is (= (inc n) (merkle/size more)))))))

(deftest every-field-of-a-leaf-is-in-the-digest
  (let [ls (leaves 5 200)
        t (merkle/build ls)
        l (nth ls 17)]
    (doseq [[what changed] [["verdict" (assoc l :verdict (if (= "unsigned" (:verdict l)) "bad-signature" "unsigned"))]
                            ["signature" (assoc l :sig (rand-hash (Random. 1)))]
                            ["no signature" (assoc l :sig nil :verdict "unsigned")]
                            ["kind" (assoc l :kind (if (= :page (:kind l)) :link :page))]
                            ["entries" (update l :entries conj "another")]]]
      (let [t2 (merkle/add t changed)]
        (is (not= (merkle/digest t) (merkle/digest t2)) what)
        (is (= (merkle/size t) (merkle/size t2)) (str what " replaces the leaf"))
        (is (= changed (merkle/lookup t2 (:hash l))))))))

(deftest entries-are-framed-one-by-one
  (let [h (rand-hash (Random. 3))]
    (is (not= (seq (merkle/leaf-digest {:hash h :verdict "unsigned" :kind :link :entries ["a,b"]}))
              (seq (merkle/leaf-digest {:hash h :verdict "unsigned" :kind :link :entries ["a" "b"]}))))))

(deftest adding-an-equal-leaf-keeps-the-tree
  (let [ls (leaves 8 300)
        t (merkle/build ls)]
    (is (identical? t (merkle/add t (nth ls 120))))
    (is (= (merkle/digest t) (merkle/digest (merkle/add-all t ls))))))

(deftest leaves-come-back-in-hash-order
  (doseq [n [0 5 33 1000]]
    (let [ls (leaves (+ 11 n) n)
          t (merkle/add-all merkle/empty-tree (shuffled 3 ls))]
      (is (= (sort-by :hash ls) (merkle/leaves t)))
      (is (every? #(= % (merkle/lookup t (:hash %))) ls)))))

(deftest a-lookup-of-an-absent-or-malformed-hash-finds-nothing
  (let [t (merkle/build (leaves 1 100))]
    (is (nil? (merkle/lookup t (rand-hash (Random. 77)))))
    (is (nil? (merkle/lookup t "sha256-xyz")))
    (is (nil? (merkle/lookup nil (rand-hash (Random. 77)))))))

(deftest a-leaf-must-be-keyed-by-a-record-hash
  (doseq [bad [nil "" "abc" "sha256-ABCDEF" (str "sha1-" (str/join (repeat 64 "a")))]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"record hash"
                          (merkle/add nil {:hash bad :verdict "unsigned" :kind :page :entries []})))))

(deftest leaves-sharing-a-long-prefix-split-deeply
  (let [prefix (str/join (repeat 62 "a"))
        ls (for [i (range 200)]
             {:hash (str "sha256-" prefix (format "%02x" i)) :verdict "unsigned" :kind :page :entries []})
        incremental (merkle/add-all nil (shuffled 1 ls))]
    (is (= (merkle/digest (merkle/build ls)) (merkle/digest incremental)))
    (is (= 200 (merkle/size incremental)))))

(deftest the-root-binds-format-actors-and-tree
  (let [d (merkle/digest (merkle/build (leaves 2 10)))
        a "sha256-0000000000000000000000000000000000000000000000000000000000000000"
        r (merkle/root 1 a d)]
    (is (merkle/valid-key? r))
    (is (= r (merkle/root 1 a d)))
    (is (not= r (merkle/root 2 a d)))
    (is (not= r (merkle/root 1 nil d)))
    (is (not= r (merkle/root 1 a (merkle/digest nil))))))

(deftest the-empty-tree-has-a-fixed-digest
  (is (= (merkle/digest nil) (merkle/digest (merkle/build []))))
  (is (zero? (merkle/size nil)))
  (testing "and it differs from a tree of one leaf"
    (is (not= (merkle/digest nil) (merkle/digest (merkle/build (leaves 1 1)))))))
