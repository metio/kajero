;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.checkpoint-test
  "Signed checkpoints: a valid one spares judging what it covers and
   nothing else, a forged or broken one is ignored whole, a record that
   disappears is reported until it returns, and the verdicts are those of
   full verification whatever the checkpoint says."
  (:require [briko.ssh-sign :as sign]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.shell :as sh]
            [kajero.checkpoint :as checkpoint]
            [kajero.cli]
            [kajero.core :as k]
            [kajero.index :as index]
            [kajero.mcp :as mcp]
            [kajero.merkle :as merkle]
            [kajero.record :as record]
            [kajero.store :as store]
            [kajero.test-keys :as keys]
            [kajero.test-temp :as temp]
            [kajero.verify :as verify]
            [kajero.web :as web])
  (:import (java.io File)
           (java.util Random)))

(use-fixtures :each temp/each)

(defn- setup
  "A flat-file notebook with a writer key granted to claude, and a
   checkpoint key the actors file does not grant; nil without ssh-keygen."
  []
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (when-let [ck (keys/key-file)]
      (let [notes (str (temp/temp-dir) "/notes")]
        {:dir notes :actors actors :key key
         :cfg (merge checkpoint/defaults {:dir (str notes ".checkpoints") :key ck
                                          :pubkey (str/trim (slurp (str ck ".pub")))})}))))

(defn- counting
  "[calls check]: a signature check that counts its calls."
  []
  (let [calls (atom 0)]
    [calls (fn [& args] (swap! calls inc) (apply verify/check-signature args))]))

(defn- open
  [{:keys [dir actors cfg]} & [opts]]
  (k/notebook (merge {:dir dir :actors actors :checkpoint cfg} opts)))

(defn- write-n!
  "Writes `n` signed entries, each with a signed observation; returns their ids."
  [nb key n & [prefix]]
  (vec (for [i (range n)]
         (let [{:keys [entry revision]} (k/write! nb {:title (str (or prefix "entry ") i) :labels {"kind" "fact"}
                                                      :actor "claude" :key key})]
           (k/observe! nb entry {:outcome "held" :revision revision :evidence ["checked"] :actor "claude" :key key})
           entry))))

(defn- records [{:keys [dir]}]
  (:records (store/changes (store/files dir) nil)))

(defn- verdicts
  "hash -> verdict of every record of the store, through the verifier `v`."
  [v recs]
  (into {} (for [[_ rec] recs] (let [h (record/hash-of rec)] [h (verify/verdict v rec h)]))))

(defn- full-verdicts
  "What a verifier that never saw a checkpoint judges."
  [{:keys [actors] :as env}]
  (verdicts (verify/verifier actors) (records env)))

(defn- checkpoint-files [{:keys [cfg]}]
  (->> (.listFiles (io/file (:dir cfg)))
       (filter #(str/ends-with? (.getName ^File %) ".checkpoint"))
       (sort-by #(.getName ^File %))))

(defn- latest-file ^File [env] (last (checkpoint-files env)))

(defn- revision-file ^File [{:keys [dir]} entry]
  (io/file dir "entries" entry "revisions" "00000001.edn"))

(defn- observation-file ^File [{:keys [dir]} entry]
  (first (filter #(str/ends-with? (.getName ^File %) ".edn")
                 (.listFiles (io/file dir "entries" entry "observations")))))

(defn- signed-checkpoint
  "A notebook of `n` entries with a checkpoint signed over it."
  [env n]
  (let [nb (open env)
        ids (write-n! nb (:key env) n)]
    (is (some? (k/sign-checkpoint! nb)))
    ids))

;; ----------------------------------------------------------- the delta

(deftest a-warm-start-judges-only-the-records-added-since-the-checkpoint
  (when-let [env (setup)]
    (signed-checkpoint env 10)
    (testing "a start right after the checkpoint judges nothing"
      (let [[calls check] (counting)
            nb (open env {:check check})]
        (is (zero? @calls))
        (is (= 20 (:covered (k/checkpoint-status nb))))
        (is (= 20 (:seeded (k/checkpoint-status nb))))
        (is (= (full-verdicts env) (verdicts (:verifier nb) (records env))))))
    (testing "records written since are judged, each once"
      (write-n! (k/notebook {:dir (:dir env) :actors (:actors env)}) (:key env) 3 "later ")
      (let [[calls check] (counting)
            nb (open env {:check check})]
        (is (= 6 @calls) "three revisions and three observations")
        (is (= 20 (:covered (k/checkpoint-status nb))))
        (is (= 26 (:records (k/checkpoint-status nb))))
        (reset! calls 0)
        (is (= 13 (count (k/search nb "not unverified"))))
        (is (zero? @calls) "every verdict a search needs is remembered")))))

(deftest a-cold-start-judges-every-record
  (when-let [env (setup)]
    (write-n! (k/notebook {:dir (:dir env) :actors (:actors env)}) (:key env) 4)
    (let [[calls check] (counting)
          nb (open env {:check check})]
      (is (= 8 @calls))
      (is (nil? (:latest (k/checkpoint-status nb))))
      (is (= 8 (:records (k/checkpoint-status nb)))))))

(deftest without-checkpoint-settings-no-tree-is-kept
  (when-let [env (setup)]
    (let [nb (k/notebook {:dir (:dir env) :actors (:actors env)})]
      (write-n! nb (:key env) 2)
      (is (nil? (index/tree (:store nb))))
      (is (= {:enabled? false} (k/checkpoint-status nb)))
      (is (empty? (k/search nb "missing-records"))))))

;; ------------------------------------------------------------ tampering

(deftest a-covered-record-edited-in-place-is-judged-again-and-reads-bad
  (when-let [env (setup)]
    (let [[id] (signed-checkpoint env 3)
          f (revision-file env id)]
      (spit f (str/replace (slurp f) "entry 0" "tampered"))
      (let [[calls check] (counting)
            nb (open env {:check check})]
        (is (<= 1 @calls 2) "only the edited record, whose hash changed")
        (is (= :bad-signature (:signature (k/entry nb id))))
        (is (= 1 (:missing (k/checkpoint-status nb))) "the signed bytes are gone")
        (is (= [id] (map :entry (k/search nb "missing-records"))))
        (is (= (full-verdicts env) (verdicts (:verifier nb) (records env))))))))

(deftest a-replaced-signature-is-judged-again
  (when-let [env (setup)]
    (let [[id] (signed-checkpoint env 2)
          sig (io/file (str (revision-file env id) ".sig"))
          other (keys/key-file)
          rec (edn/read-string (slurp (revision-file env id)))]
      (io/copy (record/sign other rec) sig)
      (let [[calls check] (counting)
            nb (open env {:check check})]
        (is (<= 1 @calls 2) "the canonical bytes, and the legacy rendering if it differs")
        (is (= :bad-signature (:signature (k/entry nb id))))
        (is (zero? (:missing (k/checkpoint-status nb))) "the record is there, under another signature")
        (is (= 3 (:covered (k/checkpoint-status nb))))))))

;; ----------------------------------------------------------- forgeries

(defn- assert-ignored
  "The notebook opened over `env` ignored its only checkpoint, with a
   warning naming `reason`, and judged every record."
  [env reason]
  (let [[calls check] (counting)
        nb (open env {:check check})
        status (k/checkpoint-status nb)]
    (is (nil? (:latest status)))
    (is (some #(str/includes? % reason) (:warnings status)) (pr-str (:warnings status)))
    (is (= (count (records env)) @calls) "a full verification")
    (is (zero? (:seeded status)))
    (is (= (full-verdicts env) (verdicts (:verifier nb) (records env))))))

(deftest a-checkpoint-signed-by-another-key-is-ignored
  (when-let [env (setup)]
    (signed-checkpoint env 3)
    (let [f (latest-file env)
          header (first (str/split-lines (slurp f)))]
      (io/copy (sign/sign-bytes (keys/key-file) (record/->bytes (edn/read-string header)) checkpoint/signature-namespace)
               (io/file (str f ".sig")))
      (assert-ignored env "does not verify"))))

(deftest a-checkpoint-signed-for-another-namespace-is-ignored
  (when-let [env (setup)]
    (signed-checkpoint env 2)
    (let [f (latest-file env)
          header (first (str/split-lines (slurp f)))]
      (io/copy (sign/sign-bytes (get-in env [:cfg :key]) (record/->bytes (edn/read-string header)) record/signature-namespace)
               (io/file (str f ".sig")))
      (assert-ignored env "does not verify"))))

(deftest a-checkpoint-with-an-edited-root-is-ignored
  (when-let [env (setup)]
    (signed-checkpoint env 3)
    (let [f (latest-file env)
          [header & body] (str/split-lines (slurp f))
          h (edn/read-string header)
          forged (assoc h :root (merkle/root 1 (:actors h) (merkle/digest nil)))]
      (spit f (str/join "\n" (cons (String. (record/->bytes forged) "UTF-8") body)))
      (assert-ignored env "does not verify"))))

(deftest a-checkpoint-with-an-edited-leaf-is-ignored
  (when-let [env (setup)]
    (signed-checkpoint env 3)
    (let [f (latest-file env)
          [header first-leaf & more] (str/split-lines (slurp f))]
      (spit f (str/join "\n" (concat [header (str/replace first-leaf #"verified:canonical" "verified:legacy")] more)))
      (assert-ignored env "do not match the tree"))))

(deftest a-checkpoint-with-a-dropped-leaf-is-ignored
  (when-let [env (setup)]
    (signed-checkpoint env 3)
    (let [f (latest-file env)
          [header _ & more] (str/split-lines (slurp f))]
      (spit f (str/join "\n" (cons header more)))
      (assert-ignored env "leaf count"))))

(deftest a-checkpoint-without-its-signature-is-ignored
  (when-let [env (setup)]
    (signed-checkpoint env 2)
    (io/delete-file (str (latest-file env) ".sig"))
    (assert-ignored env "no signature")))

(deftest a-checkpoint-of-an-unknown-format-is-ignored
  (when-let [env (setup)]
    (signed-checkpoint env 2)
    (let [f (latest-file env)
          [header & body] (str/split-lines (slurp f))
          h (assoc (edn/read-string header) :format 2)
          h (assoc h :root (merkle/root 2 (:actors h) (:tree h)))
          hb (record/->bytes h)]
      (spit f (str/join "\n" (cons (String. hb "UTF-8") body)))
      (io/copy (sign/sign-bytes (get-in env [:cfg :key]) hb checkpoint/signature-namespace) (io/file (str f ".sig")))
      (assert-ignored env "unknown format"))))

(deftest a-garbled-checkpoint-is-ignored
  (when-let [env (setup)]
    (signed-checkpoint env 2)
    (let [f (latest-file env)]
      (spit f (str (slurp f) "L not-a-hash - unsigned page -\n"))
      (assert-ignored env "malformed line"))))

(deftest a-forged-verdict-can-not-make-a-bad-record-good
  (when-let [env (setup)]
    (let [nb (open env)
          {:keys [entry]} (k/write! nb {:title "unsigned" :labels {"kind" "fact"} :actor "claude"})
          f (revision-file env entry)]
      (spit (str f ".sig") "garbage")
      (let [nb (open env)]
        (is (= :bad-signature (:signature (k/entry nb entry))))
        (k/sign-checkpoint! nb))
      (let [cf (latest-file env)]
        (spit cf (str/replace (slurp cf) #" bad-signature " " verified:canonical "))
        (assert-ignored env "do not match the tree")
        (is (= :bad-signature (:signature (k/entry (open env) entry))))))))

(deftest a-newer-forged-checkpoint-leaves-the-older-valid-one-in-use
  (when-let [env (setup)]
    (let [nb (open env)]
      (write-n! nb (:key env) 2)
      (k/sign-checkpoint! nb)
      (write-n! nb (:key env) 1 "more ")
      (k/sign-checkpoint! nb))
    (let [[older newer] (checkpoint-files env)]
      (spit newer (str/replace (slurp newer) #"verified:canonical" "unsigned"))
      (let [[calls check] (counting)
            nb (open env {:check check})
            status (k/checkpoint-status nb)]
        (is (= (.getName ^File older) (get-in status [:latest :file])))
        (is (some #(str/includes? % (.getName ^File newer)) (:warnings status)))
        (is (= 2 @calls) "only what the older checkpoint does not cover")))))

;; ------------------------------------------------------- missing records

(deftest a-deleted-record-is-reported-until-it-returns
  (when-let [env (setup)]
    (let [[a b] (signed-checkpoint env 3)
          f (observation-file env b)
          saved (slurp f)
          saved-sig (slurp (str f ".sig"))]
      (io/delete-file (str f ".sig"))
      (io/delete-file f)
      (let [nb (open env)
            status (k/checkpoint-status nb)]
        (is (= 1 (:missing status)))
        (is (= [{:kind :observation :entries [b]}] (map #(select-keys % [:kind :entries]) (:missing-records status))))
        (is (= [b] (map :entry (k/search nb "missing-records"))))
        (is (contains? (set (map :entry (k/search nb "not missing-records"))) a))
        (is (not (contains? (set (map :entry (k/search nb "not missing-records"))) b)))
        (testing "a new checkpoint carries the missing record forward"
          (k/sign-checkpoint! nb)
          (let [nb (open env)]
            (is (= 1 (:missing (k/checkpoint-status nb))))
            (is (= 1 (get-in (k/checkpoint-status nb) [:latest :missing])))
            (k/sign-checkpoint! nb))))
      (testing "restoring the record ends the report"
        (spit f saved)
        (spit (str f ".sig") saved-sig)
        (let [nb (open env)]
          (is (zero? (:missing (k/checkpoint-status nb))))
          (k/sign-checkpoint! nb)
          (is (zero? (get-in (k/checkpoint-status nb) [:latest :missing]))))))))

(deftest a-deleted-entry-is-reported-at-store-level
  (when-let [env (setup)]
    (let [[_ b] (signed-checkpoint env 2)]
      (doseq [^File f (reverse (file-seq (io/file (:dir env) "entries" b)))] (.delete f))
      (let [status (k/checkpoint-status (open env))]
        (is (= 2 (:missing status)))
        (is (= #{b} (set (mapcat :entries (:missing-records status)))))))))

(deftest the-home-page-the-cli-and-mcp-report-missing-records
  (when-let [env (setup)]
    (let [[_ b] (signed-checkpoint env 2)
          f (observation-file env b)]
      (io/delete-file f)
      (let [nb (open env)
            page (:body (web/respond nb {:actor "seb"} "t0ken" {:method "GET" :path "/" :query nil}))
            tool (mcp/call-tool nb {:actor "claude"} "kajero_checkpoint" {})]
        (is (str/includes? page "1 records the latest checkpoint held are missing"))
        (is (str/includes? page b))
        (is (= 1 (:missing tool)))
        (is (= 1 (:missing (#'kajero.cli/run-command nb ["checkpoint"]))))))))

;; ------------------------------------------------------------ the actors

(deftest a-changed-actors-file-invalidates-every-checkpoint
  (when-let [env (setup)]
    (signed-checkpoint env 3)
    (spit (:actors env) (str (slurp (:actors env)) (keys/actors-line "seb" (keys/key-file)) "\n"))
    (let [[calls check] (counting)
          nb (open env {:check check})
          status (k/checkpoint-status nb)]
      (is (= 6 @calls) "every record judged again")
      (is (zero? (:seeded status)))
      (is (false? (get-in status [:latest :actors-match?])))
      (is (some #(str/includes? % "actors file changed") (:warnings status)))
      (is (zero? (:missing status)) "the set is still compared")
      (testing "the next checkpoint is signed under the new actors file"
        (let [c (k/sign-checkpoint! nb)]
          (is (= (record/sha256 (.getBytes ^String (slurp (:actors env)) "UTF-8")) (get-in c [:header :actors])))
          (let [[calls check] (counting)]
            (open env {:check check})
            (is (zero? @calls))))))))

(deftest an-actors-file-changed-while-running-rebuilds-the-tree-before-signing
  (when-let [env (setup)]
    (let [nb (open env)
          [id] (write-n! nb (:key env) 2)]
      (k/sign-checkpoint! nb)
      (keys/write-actors! (:actors env) [["seb" (keys/key-file)]])
      (let [c (k/sign-checkpoint! nb)
            leaf (first (filter #(= [id] (:entries %)) (:leaves c)))]
        (is (= "bad-signature" (:verdict leaf)) "claude is no longer an actor")
        (is (= (full-verdicts env) (verdicts (verify/verifier (:actors env)) (records env))))
        (is (= (:root (k/checkpoint-status nb)) (get-in c [:header :root])))))))

(deftest a-checkpoint-key-the-actors-file-grants-is-refused
  (when-let [env (setup)]
    (let [ck (get-in env [:cfg :key])]
      (keys/write-actors! (:actors env) [["claude" (:key env)] ["seb" ck]])
      (let [nb (open env)
            status (k/checkpoint-status nb)]
        (is (false? (:enabled? status)))
        (is (some #(str/includes? % "grants the checkpoint key") (:warnings status)))
        (is (nil? (k/sign-checkpoint! nb)))
        (is (nil? (index/tree (:store nb))))))))

(deftest a-signing-key-that-is-not-the-pinned-one-signs-nothing
  (when-let [env (setup)]
    (let [env (assoc-in env [:cfg :key] (keys/key-file))
          nb (open env)
          status (k/checkpoint-status nb)]
      (is (true? (:enabled? status)))
      (is (false? (:signing? status)))
      (is (some #(str/includes? % "not the pinned public key") (:warnings status)))
      (is (nil? (k/sign-checkpoint! nb))))))

(deftest a-key-that-is-not-ed25519-is-refused
  (when-let [env (setup)]
    (let [d (temp/temp-dir)
          k (str d "/ecdsa")]
      (when (zero? (:exit (sh/sh "ssh-keygen" "-q" "-t" "ecdsa" "-N" "" "-f" k)))
        (let [status (k/checkpoint-status (open (assoc env :cfg (assoc (:cfg env) :key k :pubkey nil))))]
          (is (false? (:enabled? status)))
          (is (some #(str/includes? % "not an ed25519 key") (:warnings status))))))))

;; ---------------------------------------------------------- the history

(deftest an-unchanged-notebook-signs-no-new-checkpoint
  (when-let [env (setup)]
    (let [nb (open env)]
      (write-n! nb (:key env) 2)
      (let [c (k/sign-checkpoint! nb)]
        (is (= (:file c) (:file (k/sign-checkpoint! nb))))
        (is (= 1 (count (checkpoint-files env))))))))

(deftest only-the-newest-checkpoints-are-kept
  (when-let [env (setup)]
    (let [env (assoc-in env [:cfg :keep] 3)
          nb (open env)]
      (dotimes [i 5]
        (write-n! nb (:key env) 1 (str "round " i))
        (k/sign-checkpoint! nb))
      (is (= 3 (count (checkpoint-files env))))
      (is (= 3 (count (filter #(str/ends-with? (.getName ^File %) ".checkpoint.sig")
                              (.listFiles (io/file (get-in env [:cfg :dir]))))))))))

(deftest checkpoints-live-outside-the-records
  (when-let [env (setup)]
    (signed-checkpoint env 2)
    (is (= (str (:dir env) ".checkpoints") (get-in env [:cfg :dir])))
    (is (= (checkpoint/beside {:dir (str (:dir env) "/")}) (get-in env [:cfg :dir])))
    (is (= "/x/notes.db.checkpoints" (checkpoint/beside {:path "/x/notes.db"})))
    (is (not-any? #(str/includes? (str %) "checkpoint") (map (comp :title second) (records env))))))

(deftest writes-past-the-threshold-sign-a-checkpoint
  (when-let [env (setup)]
    (let [env (update env :cfg assoc :writes 4 :interval-ms 3600000)
          nb (k/keep-checkpointing! (open env))
          until (+ (System/currentTimeMillis) 20000)]
      (while (and (empty? (checkpoint-files env)) (< (System/currentTimeMillis) until)) (Thread/sleep 50))
      (is (= 1 (count (checkpoint-files env))) "the checkpoint at start")
      (write-n! nb (:key env) 2)
      (while (and (< (count (checkpoint-files env)) 2) (< (System/currentTimeMillis) until)) (Thread/sleep 50))
      (is (= 2 (count (checkpoint-files env))) "four writes later")
      (is (= 4 (get-in (k/checkpoint-status nb) [:latest :leaves])))
      (k/close! nb))))

(deftest a-sqlite-notebook-keeps-checkpoints-beside-its-database
  (when-let [env (setup)]
    (let [db (str (temp/temp-dir) "/notes.db")
          cfg (assoc (:cfg env) :dir (checkpoint/beside {:path db}))
          nb (k/notebook {:path db :actors (:actors env) :checkpoint cfg})
          [id] (write-n! nb (:key env) 2)]
      (k/sign-checkpoint! nb)
      (let [[calls check] (counting)
            nb (k/notebook {:path db :actors (:actors env) :checkpoint cfg :check check})]
        (is (zero? @calls))
        (is (= 4 (:covered (k/checkpoint-status nb))))
        (is (= :verified (:signature (k/entry nb id))))))))

;; ------------------------------------------------- against full verification

(defn- generated-store!
  "A notebook from `seed`: signed, unsigned and badly signed records,
   checkpoints signed at random points, then records added, edited, given
   another signature or deleted after the last checkpoint."
  [env seed]
  (let [r (Random. seed)
        other (keys/key-file)
        nb (open env)
        ids (atom [])
        pick #(when (seq @ids) (nth @ids (.nextInt r (count @ids))))
        step! (fn []
                (case (.nextInt r 6)
                  (0 1) (let [{:keys [entry]} (k/write! nb {:title (str "e" (.nextInt r 1000)) :labels {"kind" "fact"}
                                                            :actor "claude" :key (when (pos? (.nextInt r 5)) (:key env))})]
                          (swap! ids conj entry))
                  2 (when-let [id (pick)]
                      (k/observe! nb id {:outcome "held" :evidence ["x"] :actor "claude"
                                         :key (when (pos? (.nextInt r 4)) (:key env))}))
                  3 (when (< 1 (count @ids))
                      (k/link! nb {:source (first @ids) :type "see-also" :target (peek @ids) :actor "claude" :key (:key env)}))
                  4 (when-let [id (pick)]
                      (let [f (revision-file env id)]
                        (case (.nextInt r 4)
                          0 (spit f (str/replace (slurp f) "\"e" "\"edited e"))
                          1 (spit (str f ".sig") "garbage")
                          2 (io/copy (record/sign other (edn/read-string (slurp f))) (io/file (str f ".sig")))
                          3 (some-> (observation-file env id) io/delete-file))))
                  5 (k/sign-checkpoint! (open env))))]
    (dotimes [_ (+ 10 (.nextInt r 25))]
      ;; an observation of a revision edited on disk is refused, which is
      ;; one more way a store can be
      (try (step!) (catch clojure.lang.ExceptionInfo _ nil)))))

(deftest verdicts-with-checkpoints-equal-full-verification-over-generated-stores
  (when-let [base (setup)]
    (let [seen (atom {:covered 0 :missing 0 :bad 0 :unsigned 0})]
      (doseq [seed (range 12)]
        (let [env (assoc base :dir (str (temp/temp-dir) "/notes"))
              env (assoc-in env [:cfg :dir] (str (:dir env) ".checkpoints"))]
          (generated-store! env seed)
          (let [nb (open env)
                recs (records env)]
            (is (= (full-verdicts env) (verdicts (:verifier nb) recs)) (str "seed " seed))
            (is (= (count (set (map (comp record/hash-of second) recs))) (:records (k/checkpoint-status nb))) (str "seed " seed))
            (is (= (map :hash (merkle/leaves (:merkle (index/tree (:store nb)))))
                   (sort (distinct (map (comp record/hash-of second) recs)))))
            (let [status (k/checkpoint-status nb)
                  states (frequencies (map :signature (vals (full-verdicts env))))]
              (swap! seen #(merge-with + % {:covered (:covered status) :missing (:missing status)
                                            :bad (get states :bad-signature 0) :unsigned (get states :unsigned 0)}))))))
      (testing "the generated stores exercise every case"
        (is (every? pos? (vals @seen)) (pr-str @seen))))))

(deftest a-checkpoint-whose-header-nests-too-deep-is-ignored
  (when-let [env (setup)]
    (signed-checkpoint env 1)
    (let [f (latest-file env)
          lines (str/split-lines (slurp f))]
      (spit f (str/join "\n" (cons (str (str/join (repeat 100000 "[")) (str/join (repeat 100000 "]"))) (rest lines))))
      (let [r (checkpoint/read-checkpoint (get-in env [:cfg :pubkey]) f)]
        (is (false? (:valid? r)))
        (is (str/starts-with? (:reason r) "unreadable"))))))
