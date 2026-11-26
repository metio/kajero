;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.test-keys
  "Signing keys and actors files for the tests."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [kajero.test-temp :as temp]))

(defn key-file
  "A fresh ed25519 private key file, or nil without ssh-keygen."
  []
  (let [k (str (temp/temp-dir) "/id_ed25519")]
    (when (zero? (:exit (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-f" k)))
      k)))

(defn actors-line
  "The actors file line that gives the key in `key` to `actor` for kajero."
  [actor key]
  (str actor " namespaces=\"kajero\" " (str/trim (slurp (str key ".pub")))))

(defn write-actors!
  "Writes the actors file at `path` giving each key to its actor, from
   `grants` [[actor key] ...]; returns the path."
  [path grants]
  (spit path (str (str/join "\n" (map #(apply actors-line %) grants)) "\n"))
  path)

(defn keypair
  "A fresh key and an actors file giving it to `actor`, or nil without
   ssh-keygen."
  [actor]
  (when-let [k (key-file)]
    {:key k :actors (write-actors! (str (temp/temp-dir) "/actors") [[actor k]])}))
