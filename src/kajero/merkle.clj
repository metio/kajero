;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.merkle
  "An order-independent Merkle tree over a set of leaves, each keyed by
   the hash of one record.

   A notebook is a set of records that merges by union (ADR 0002), so the
   tree must give the same digest for the same set however its records
   arrived. It is a trie on the hex digits of the record hash: a node holds
   its leaves directly while it has at most `bucket-max` of them, and
   otherwise splits into 16 children by the next digit. Whether a node is a
   bucket or splits depends only on how many leaves fall below it, so the
   shape, and with it every digest, is a function of the set alone.
   Adding a leaf rebuilds the nodes on one path, which is the depth of the
   trie plus one bucket.

   A leaf is a map {:hash :sig :verdict :kind :entries}: the record's hash
   computed from its canonical bytes, the sha256 of its signature (nil when
   it has none), its signature verdict as a string, its kind and the
   entries it belongs to. Every field enters the leaf's digest, so a leaf
   whose verdict or signature changed is a different leaf.

   Every digest is domain-separated and length-framed, so no leaf, bucket,
   inner node or root can be read as another, and no two field lists frame
   to the same bytes."
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Arrays HexFormat)))

(def format-version
  "The version of the tree and the root formula. A checkpoint of another
   version is ignored."
  1)

(def bucket-max
  "The most leaves a node holds before it splits by the next hex digit."
  32)

(def ^:private max-depth
  "The hex digits of a sha256: a node this deep can not split further."
  64)

(defn- frame!
  [^MessageDigest md ^String s]
  (let [b (.getBytes (or s "") StandardCharsets/UTF_8)
        n (alength b)]
    (.update md (byte-array [(unchecked-byte (bit-shift-right n 24)) (unchecked-byte (bit-shift-right n 16))
                             (unchecked-byte (bit-shift-right n 8)) (unchecked-byte n)]))
    (.update md b)))

(defn- framed
  "The sha256 of the length-framed strings `fields`."
  ^bytes [fields]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (doseq [f fields] (frame! md f))
    (.digest md)))

(defn- hex [^bytes b] (.formatHex (HexFormat/of) b))

(def ^:private empty-digest (framed ["kajero-merkle-empty/1"]))

(defn valid-key?
  "Whether `k` is a record hash as kajero writes it: sha256- and 64
   lower-case hex digits."
  [k]
  (boolean (and (string? k) (re-matches #"sha256-[0-9a-f]{64}" k))))

(defn leaf-digest
  "The digest of one leaf."
  ^bytes [{:keys [hash sig verdict kind entries]}]
  (framed (into ["kajero-merkle-leaf/1" hash (or sig "") (str verdict) (some-> kind name) (str (count entries))]
                entries)))

(defn- nibble
  ^long [^String k ^long depth]
  (Character/digit (.charAt k (+ 7 depth)) 16))

(defn- bucket-digest
  ^bytes [leaves]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (frame! md "kajero-merkle-bucket/1")
    (doseq [[_ [_ ^bytes d]] leaves] (.update md d))
    (.digest md)))

(defn- inner-digest
  ^bytes [kids]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (frame! md "kajero-merkle-inner/1")
    (doseq [kid kids]
      (let [^bytes d (if kid (:digest kid) empty-digest)]
        (.update md d)))
    (.digest md)))

(defn- bucket
  "A bucket node over `leaves`, a sorted map of key to [leaf digest]."
  [leaves]
  {:n (count leaves) :leaves leaves :digest (bucket-digest leaves)})

(defn- inner
  [kids]
  {:n (reduce + 0 (map #(:n % 0) kids)) :kids kids :digest (inner-digest kids)})

(defn- node-of
  "The node at `depth` over the sorted map `leaves`, by the shape rule."
  [^long depth leaves]
  (cond
    (empty? leaves) nil
    (or (<= (count leaves) (long bucket-max)) (>= depth (long max-depth))) (bucket leaves)
    :else (let [groups (group-by #(nibble (key %) depth) leaves)]
            (inner (mapv #(some->> (get groups %) (into (sorted-map)) (node-of (inc depth))) (range 16))))))

(defn- insert
  [node ^long depth k entry]
  (cond
    (nil? node) (bucket (sorted-map k entry))

    (:leaves node)
    (let [leaves (assoc (:leaves node) k entry)]
      (if (and (> (count leaves) (long bucket-max)) (< depth (long max-depth)))
        (node-of depth leaves)
        (bucket leaves)))

    :else
    (let [i (nibble k depth)]
      (inner (update (:kids node) i insert (inc depth) k entry)))))

(def empty-tree
  "The tree of no leaves."
  nil)

(defn- check-key!
  [k]
  (when-not (valid-key? k)
    (throw (ex-info (str "a leaf is keyed by a record hash: " k) {:reason :kajero/bad-leaf :hash k}))))

(defn add
  "`tree` with `leaf` in it, replacing the leaf of the same hash. Leaves
   that are equal leave the tree as it was."
  [tree leaf]
  (let [k (:hash leaf)]
    (check-key! k)
    (let [d (leaf-digest leaf)
          [_ held] (get-in (loop [node tree depth 0]
                             (cond (nil? node) nil
                                   (:leaves node) node
                                   :else (recur (get (:kids node) (nibble k depth)) (inc depth))))
                           [:leaves k])]
      (if (and held (Arrays/equals ^bytes held d))
        tree
        (insert tree 0 k [leaf d])))))

(defn add-all
  "`tree` with every leaf of `leaves` added."
  [tree leaves]
  (reduce add tree leaves))

(defn build
  "The tree of `leaves`, built in one pass from the shape rule rather than
   leaf by leaf. A later leaf of the same hash replaces an earlier one."
  [leaves]
  (let [m (reduce (fn [m leaf]
                    (check-key! (:hash leaf))
                    (assoc m (:hash leaf) [leaf (leaf-digest leaf)]))
                  (sorted-map)
                  leaves)]
    (node-of 0 m)))

(defn size
  "How many leaves the tree holds."
  ^long [tree]
  (long (:n tree 0)))

(defn digest
  "The digest of the tree as sha256-<hex>, the same for the same set of
   leaves whatever order they were added in."
  [tree]
  (str "sha256-" (hex (if tree (:digest tree) empty-digest))))

(defn lookup
  "The leaf whose record hash is `k`, or nil."
  [tree k]
  (when (valid-key? k)
    (loop [node tree depth 0]
      (cond (nil? node) nil
            (:leaves node) (first (get (:leaves node) k))
            :else (recur (get (:kids node) (nibble k depth)) (inc depth))))))

(defn leaves
  "Every leaf of the tree, in the order of their record hashes."
  [tree]
  (letfn [(walk [node]
            (cond (nil? node) []
                  (:leaves node) (map (comp first val) (:leaves node))
                  :else (mapcat walk (:kids node))))]
    (walk tree)))

(defn root
  "The root a checkpoint signs: the sha256 of the format version, the
   digest of the actors file the verdicts were judged under (nil without
   one) and the tree's digest."
  [format-v actors tree-digest]
  (str "sha256-" (hex (framed ["kajero-checkpoint-root" (str format-v) (or actors "") tree-digest]))))
