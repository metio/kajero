;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.verify-test
  (:require [briko.ssh-sign :as sign]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.record :as record]
            [kajero.test-keys :as keys]
            [kajero.test-temp :as temp]
            [kajero.verify :as verify]))

(use-fixtures :each temp/each)

(def ^:private rec
  {:entry "e-1" :seq 1 :base nil :actor "claude" :at "2026-11-26T00:00:00Z" :title "t" :body "b"
   :labels {"kind" "fact"} :data {:a/x 1 :a/y 2}})

(defn- signed
  "The record with the signature of `key` over its canonical bytes."
  [key r]
  (assoc r :sig (record/sign key r)))

(defn- legacy-signed
  "The record with the signature of `key` over the bytes clojure.main's
   thread printed it as."
  [key r]
  (assoc r :sig (sign/sign-bytes key (record/legacy-bytes r) record/signature-namespace)))

(defn- state [v r] (:signature (verify/verdict v r)))

(defn- counting
  "A verifier on `actors` whose cryptographic checks are counted in the
   returned atom."
  [actors]
  (let [calls (atom 0)]
    [(verify/verifier actors {:check (fn [& args] (swap! calls inc) (apply verify/check-signature args))})
     calls]))

(deftest a-signature-of-the-actors-key-verifies
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [v (verify/verifier actors)]
      (is (= {:signature :verified :form :canonical} (verify/verdict v (signed key rec))))
      (is (verify/verified? v (signed key rec))))))

(deftest a-record-without-a-signature-is-unsigned
  (when-let [{:keys [actors]} (keys/keypair "claude")]
    (is (= {:signature :unsigned} (verify/verdict (verify/verifier actors) rec)))
    (is (= {:signature :unsigned} (verify/verdict (verify/verifier nil) rec)))))

(deftest a-key-outside-the-actors-file-is-a-bad-signature
  (when-let [{:keys [actors]} (keys/keypair "claude")]
    (let [foreign (keys/key-file)]
      (is (= :bad-signature (state (verify/verifier actors) (signed foreign rec)))))))

(deftest a-key-given-to-another-actor-is-a-bad-signature
  (when-let [{:keys [key actors]} (keys/keypair "seb")]
    (is (= :bad-signature (state (verify/verifier actors) (signed key rec))))))

(deftest a-changed-body-is-a-bad-signature
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [v (verify/verifier actors)
          {:keys [sig]} (signed key rec)]
      (is (= :bad-signature (state v (assoc rec :body "changed" :sig sig))))
      (is (= :bad-signature (state v (assoc rec :data {:a/x 1 :a/y 3} :sig sig))))
      (is (= :bad-signature (state v (assoc rec :actor "seb" :sig sig)))))))

(deftest a-signature-for-another-namespace-is-a-bad-signature
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [sig (sign/sign-bytes key (record/->bytes rec) "tik-event")]
      (is (= :bad-signature (state (verify/verifier actors) (assoc rec :sig sig)))))))

(deftest a-record-without-an-actor-verifies-as-nobody
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [v (verify/verifier actors)]
      (doseq [actor [nil "" "  " :claude]]
        (testing (pr-str actor)
          (let [r (signed key (assoc rec :actor actor))]
            (is (= :bad-signature (state v r)) "the key is granted to claude, and to nobody else")
            (is (false? (record/verified? actors r (:sig r))))
            (is (false? (verify/check-signature {:text (slurp actors) :path actors}
                                                (record/->bytes r) (:sig r) actor)))))))))

(deftest a-garbled-signature-is-a-bad-signature
  (when-let [{:keys [actors]} (keys/keypair "claude")]
    (is (= :bad-signature (state (verify/verifier actors) (assoc rec :sig (.getBytes "not a signature" "UTF-8")))))))

(deftest without-an-actors-file-no-signature-verifies
  (when-let [{:keys [key]} (keys/keypair "claude")]
    (is (= :bad-signature (state (verify/verifier nil) (signed key rec))))
    (is (= :bad-signature (state (verify/verifier (str (temp/temp-dir) "/missing")) (signed key rec))))))

(deftest a-record-signed-over-the-legacy-rendering-still-verifies
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [v (verify/verifier actors)
          r (legacy-signed key rec)]
      (is (= {:signature :verified :form :legacy} (verify/verdict v r)))
      (testing "and its hash is the canonical one"
        (is (= (record/hash-of rec) (record/hash-of r))))
      (testing "a changed body still fails"
        (is (= :bad-signature (state v (assoc r :body "changed")))))
      (testing "a foreign key over the legacy rendering still fails"
        (is (= :bad-signature (state v (legacy-signed (keys/key-file) rec))))))))

(deftest only-records-with-a-legacy-rendering-try-it
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [[v calls] (counting actors)
          plain (dissoc rec :data)]
      (is (= :bad-signature (state v (assoc plain :sig (:sig (signed key rec))))))
      (is (= 1 @calls) "a record without namespaced maps is checked over one rendering"))))

(deftest removing-an-actor-from-the-file-invalidates-its-verdicts
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [v (verify/verifier actors)
          r (signed key rec)
          other (keys/key-file)]
      (is (= :verified (state v r)))
      (keys/write-actors! actors [["seb" other]])
      (is (= :bad-signature (state v r)) "the removed actor reads as not verified at once")
      (keys/write-actors! actors [["seb" other] ["claude" key]])
      (is (= :verified (state v r)) "and verifies again once it is back")
      (keys/write-actors! actors [["claude" other]])
      (is (= :bad-signature (state v r)) "a new key for the actor does not vouch for the old one")
      (.delete (java.io.File. ^String actors))
      (is (= :bad-signature (state v r)) "a deleted actors file vouches for nobody"))))

(deftest a-cached-verdict-is-not-checked-again
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [[v calls] (counting actors)
          r (signed key rec)]
      (dotimes [_ 5] (is (= :verified (state v r))))
      (is (= 1 @calls))
      (testing "a bad signature is remembered too"
        (let [bad (assoc r :body "changed")]
          (dotimes [_ 3] (state v bad))
          ;; canonical and legacy rendering, once
          (is (= 3 @calls))))
      (testing "a different signature over the same record is judged on its own"
        (state v (assoc rec :sig (:sig (legacy-signed key rec))))
        (is (= 5 @calls))))))

(deftest a-changed-actors-file-is-checked-afresh
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [[v calls] (counting actors)
          r (signed key rec)]
      (state v r)
      (spit actors "# a comment changes the digest\n" :append true)
      (is (= :verified (state v r)))
      (is (= 2 @calls))
      (testing "an unchanged file keeps its verdicts"
        (state v r)
        (is (= 2 @calls))))))

(deftest warming-judges-every-record-once
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [[v calls] (counting actors)
          records (for [i (range 4)] (signed key (assoc rec :seq i :data {:n i})))]
      (is (= 4 (verify/warm! v records)))
      (is (= 4 @calls))
      (is (every? #(= :verified (state v %)) records))
      (is (= 4 @calls) "reads after warming hit the cache")
      (is (= 5 (verify/warm! v (cons rec records))) "unsigned records count but are never checked")
      (is (= 4 @calls)))))

(deftest the-registry-is-kept-while-the-file-stays-the-same
  (when-let [{:keys [actors]} (keys/keypair "claude")]
    (let [v (verify/verifier actors)
          a (verify/registry v)]
      (is (identical? a (verify/registry v)))
      (spit actors "\n" :append true)
      (is (not= (:digest a) (:digest (verify/registry v)))))))

(deftest a-rewrite-of-the-same-size-and-modification-time-is-noticed
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [v (verify/verifier actors)
          r (signed key rec)
          f (java.io.File. ^String actors)
          before (java.nio.file.Files/getLastModifiedTime (.toPath f) (make-array java.nio.file.LinkOption 0))
          size (.length f)]
      (is (= :verified (state v r)))
      (keys/write-actors! actors [["claude" (keys/key-file)]])
      (java.nio.file.Files/setLastModifiedTime (.toPath f) before)
      (is (= size (.length f)) "the replacement line has the same length")
      (is (= :bad-signature (state v r))))))

(defn- slow-counting
  "A verifier on `actors` whose checks take a while and are counted per
   message and signature, so concurrent callers overlap."
  [actors]
  (let [calls (atom {})]
    [(verify/verifier actors {:check (fn [reg ^bytes message ^bytes sig actor]
                                       (swap! calls update [(vec message) (vec sig)] (fnil inc 0))
                                       (Thread/sleep 20)
                                       (verify/check-signature reg message sig actor))})
     calls]))

(deftest concurrent-verdicts-check-each-record-once
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [[v calls] (slow-counting actors)
          records (vec (for [i (range 4)] (signed key (assoc rec :seq i :data {:n i}))))
          start (java.util.concurrent.CountDownLatch. 1)
          pool (java.util.concurrent.Executors/newFixedThreadPool 16)
          futures (doall (for [_ (range 16) r records]
                           (.submit pool ^Callable (fn [] (.await start) (state v r)))))]
      (.countDown start)
      (is (every? #(= :verified (.get ^java.util.concurrent.Future %)) futures))
      (.shutdown pool)
      (is (= 4 (count @calls)) "every record was checked")
      (is (every? #{1} (vals @calls)) "and each exactly once"))))

(deftest a-registry-read-once-per-request-still-sees-the-file-change-between-requests
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [v (verify/verifier actors)
          r (signed key rec)
          f (java.io.File. ^String actors)
          request (fn [] (let [reg (verify/registry v)] (:signature (verify/verdict v reg r nil))))]
      (is (= :verified (request)))
      (testing "a same-size rewrite with the modification time restored"
        (let [before (java.nio.file.Files/getLastModifiedTime (.toPath f) (make-array java.nio.file.LinkOption 0))
              size (.length f)]
          (keys/write-actors! actors [["claude" (keys/key-file)]])
          (java.nio.file.Files/setLastModifiedTime (.toPath f) before)
          (is (= size (.length f)))
          (is (= :bad-signature (request)))))
      (testing "a removed actor"
        (keys/write-actors! actors [["claude" key]])
        (is (= :verified (request)))
        (keys/write-actors! actors [["seb" (keys/key-file)]])
        (is (= :bad-signature (request)))))))

(deftest a-legacy-signature-verifies-on-every-path
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [r (legacy-signed key rec)]
      (testing "with the hash given"
        (is (= {:signature :verified :form :legacy}
               (verify/verdict (verify/verifier actors) r (record/hash-of r)))))
      (testing "with the registry of a request"
        (let [v (verify/verifier actors)]
          (is (= {:signature :verified :form :legacy} (verify/verdict v (verify/registry v) r nil)))))
      (testing "after a parallel warm-up"
        (let [[v calls] (counting actors)]
          (is (= 1 (verify/warm! v [r])))
          (is (= 2 @calls) "canonical, then legacy")
          (is (= {:signature :verified :form :legacy} (verify/verdict v r)))
          (is (= 2 @calls)))))))

(deftest a-parallel-warm-up-gives-the-verdicts-of-serial-judgement
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [foreign (keys/key-file)
          records (vec (for [i (range 60)
                             :let [r (assoc rec :seq i :data (if (#{0 1} (mod i 5)) {:a/x i :a/y i} {:n i}))]]
                         (case (mod i 5)
                           0 (signed key r)
                           1 (legacy-signed key r)
                           2 r
                           3 (signed foreign r)
                           4 (assoc (signed key r) :body "changed"))))
          serial (let [v (verify/verifier actors)] (mapv #(verify/verdict v %) records))
          warmed (verify/verifier actors)]
      (is (= 60 (verify/warm! warmed records)))
      (is (= serial (mapv #(verify/verdict warmed %) records)))
      (is (= #{:verified :unsigned :bad-signature} (set (map :signature serial)))))))
