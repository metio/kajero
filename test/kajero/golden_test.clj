;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.golden-test
  "Signatures made by an earlier signer still verify.

   test/golden/tik-sign-8447157 holds a flat-file notebook, a checkpoint
   and a signed skill entry made by kajero 866cde7, which signed through
   tik.sign of tik 8447157, with throwaway keys whose private halves were
   deleted after signing. The actors file and the checkpoint's public key
   are the only key material kept. Every record, the checkpoint and the
   manifest must verify under them, and a changed byte must not."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.checkpoint :as checkpoint]
            [kajero.core :as k]
            [kajero.record :as record]
            [kajero.skills :as skills]
            [kajero.store :as store]
            [kajero.test-temp :as temp]
            [kajero.verify :as verify])
  (:import (java.io File)
           (java.nio.file Files LinkOption)))

(use-fixtures :each temp/each)

(def ^:private golden (io/file "test/golden/tik-sign-8447157"))

(defn- copy-tree!
  "Copies the directory `from` to `to`, so a test that opens a notebook
   never writes into the fixtures."
  [^File from ^File to]
  (doseq [^File f (file-seq from)
          :let [rel (.relativize (.toPath from) (.toPath f))
                target (.resolve (.toPath to) rel)]]
    (if (.isDirectory f)
      (Files/createDirectories target (make-array java.nio.file.attribute.FileAttribute 0))
      (Files/copy (.toPath f) target ^"[Ljava.nio.file.CopyOption;" (make-array java.nio.file.CopyOption 0))))
  (str to))

(defn- fixture
  "A copy of the golden notebook in a temp directory, with the paths a test
   needs."
  []
  (let [dir (io/file (temp/temp-dir) "golden")]
    (copy-tree! golden dir)
    {:notes (str dir "/notes")
     :actors (str dir "/actors")
     :checkpoints (str dir "/checkpoints")
     :pubkey (str/trim (slurp (io/file dir "checkpoint.pub")))
     :skill-entry (str dir "/skill-entry.json")}))

(defn- records [notes]
  (:records (store/changes (store/files notes) nil)))

(deftest every-golden-record-verifies
  (let [{:keys [notes actors]} (fixture)
        recs (records notes)
        v (verify/verifier actors)]
    (is (= 6 (count recs)) "three revisions, two observations and a link")
    (doseq [[kind rec] recs]
      (testing (str kind " " (record/hash-of rec))
        (is (= {:signature :verified :form :canonical} (verify/verdict v rec)))
        (is (true? (record/verified? actors rec (:sig rec))))))))

(deftest a-changed-golden-record-does-not-verify
  (let [{:keys [notes actors]} (fixture)
        v (verify/verifier actors)]
    (doseq [[_ rec] (records notes)]
      (let [changed (assoc rec :at "2026-11-28T00:00:00Z")]
        (is (= {:signature :bad-signature} (verify/verdict v changed)))
        (is (false? (record/verified? actors changed (:sig rec))))))))

(deftest the-golden-notebook-opens-verified-and-covered-by-its-checkpoint
  (let [{:keys [notes actors checkpoints pubkey]} (fixture)
        nb (k/notebook {:dir notes :actors actors
                        :checkpoint (merge checkpoint/defaults {:dir checkpoints :pubkey pubkey})})]
    (try
      (is (= 2 (count (k/search nb ""))))
      (is (empty? (k/search nb "unverified")))
      (let [status (k/checkpoint-status nb)]
        (is (empty? (:warnings status)))
        (is (= 6 (:covered status)))
        (is (zero? (:missing status)))
        (is (true? (get-in status [:latest :actors-match?]))))
      (finally (k/close! nb)))))

(deftest the-golden-checkpoint-verifies-only-under-its-own-key
  (let [{:keys [checkpoints pubkey actors]} (fixture)
        ^File f (first (filter #(str/ends-with? (.getName ^File %) ".checkpoint") (.listFiles (io/file checkpoints))))
        writer-key (last (str/split (str/trim (slurp actors)) #" " 3))]
    (is (true? (:valid? (checkpoint/read-checkpoint pubkey f))))
    (is (= "the signature does not verify under the pinned checkpoint key"
           (:reason (checkpoint/read-checkpoint writer-key f))))))

(deftest the-golden-skill-manifest-verifies
  (let [{:keys [skill-entry actors]} (fixture)
        entry (json/parse-string (slurp skill-entry))]
    (is (= {:verified? true :actor "claude"} (skills/verify-entry actors entry)))
    (testing "a changed digest does not verify"
      (let [changed (update-in entry ["resources" 0 "digest"] #(str (subs % 0 (dec (count %))) (if (str/ends-with? % "0") "1" "0")))]
        (is (= :bad-signature (:reason (skills/verify-entry actors changed))))))))

(deftest the-fixtures-hold-no-private-key
  (doseq [^File f (file-seq golden)
          :when (Files/isRegularFile (.toPath f) (make-array LinkOption 0))]
    (is (not (str/includes? (slurp f) "PRIVATE KEY")) (str f))))
