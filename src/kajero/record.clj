;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.record
  "The canonical bytes of a record, its content hash and its signature.

   A record is a map of plain data. Its canonical form prints every map with
   sorted keys, so the same record always gives the same bytes, the same
   hash and a signature that verifies wherever the bytes are read. The
   text is printed by kajero.print under fixed print settings, so the
   bytes do not depend on the thread or process that prints them."
  (:require [kajero.print :as kp]
            [tik.sign :as sign])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util HexFormat)))

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

(defn- render
  ^bytes [settings record]
  (.getBytes ^String (kp/edn-str settings (canonical (dissoc record :sig))) StandardCharsets/UTF_8))

(defn ->bytes
  "The canonical bytes of the record: what its hash covers and what a new
   signature signs."
  ^bytes [record]
  (render kp/settings record))

(defn legacy-bytes
  "The bytes the record printed as on a thread with *print-namespace-maps*
   true, as the CLI and the stdio MCP server signed them before the print
   settings were fixed; nil when they equal the canonical bytes, which is
   every record without a map whose keys share one namespace. `canonical`
   is the record's canonical bytes when the caller has them already."
  (^bytes [record] (legacy-bytes record (->bytes record)))
  (^bytes [record ^bytes canonical]
   (let [legacy (render (assoc kp/settings :namespace-maps true) record)]
     (when-not (java.util.Arrays/equals legacy canonical)
       legacy))))

(defn sha256
  "The sha256 of `b`, as `sha256-<hex>`."
  [^bytes b]
  (str "sha256-" (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") b))))

(defn bytes+hash
  "[canonical bytes, hash] of the record, rendering it once."
  [record]
  (let [b (->bytes record)]
    [b (sha256 b)]))

(defn hash-of
  "The sha256 of the record's canonical bytes, as `sha256-<hex>`."
  [record]
  (sha256 (->bytes record)))

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
