;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: 0BSD
(ns kajero.record
  "The canonical bytes of a record, its content hash and its signature.

   A record is a map of plain data. Its canonical form prints every map with
   sorted keys, so the same record always gives the same bytes, the same
   hash and a signature that verifies wherever the bytes are read."
  (:require [tik.sign :as sign])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def signature-namespace
  "The ssh-keygen -Y namespace of kajero signatures, so a kajero signature
   can never pass as a tik event signature and the reverse."
  "kajero")

(defn- canonical
  [x]
  (cond
    (map? x) (into (sorted-map) (update-vals x canonical))
    (set? x) (into (sorted-set) (map canonical x))
    (sequential? x) (mapv canonical x)
    :else x))

(defn ->bytes
  ^bytes [record]
  (.getBytes (pr-str (canonical (dissoc record :sig))) StandardCharsets/UTF_8))

(defn hash-of
  "The sha256 of the record's canonical bytes, as `sha256-<hex>`."
  [record]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (->bytes record))]
    (str "sha256-" (apply str (map #(format "%02x" %) digest)))))

(defn sign
  "The detached signature of the record by the key in `key-file`."
  ^bytes [key-file record]
  (sign/sign-bytes key-file (->bytes record) signature-namespace))

(defn verified?
  "Whether `sig` is the record's actor's signature over the record, per the
   allowed-signers file `actors`."
  [actors record ^bytes sig]
  (boolean (and actors sig
                (sign/verify-bytes actors (->bytes record) sig (:actor record)
                                   signature-namespace))))
