;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.warm-up-test
  "A server answers before its checkpoint is read and its Merkle tree is
   built. Until the warm-up ends, no verdict comes from the checkpoint
   without a judgement, missing records are pending rather than none, no
   checkpoint is signed, and writes made meanwhile end up in the tree as if
   it had been built over the final records from scratch."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.checkpoint :as checkpoint]
            [kajero.core :as k]
            [kajero.index :as index]
            [kajero.mcp :as mcp]
            [kajero.merkle :as merkle]
            [kajero.test-keys :as keys]
            [kajero.test-temp :as temp]
            [kajero.verify :as verify]
            [kajero.web :as web])
  (:import (java.io File)
           (java.util Random)
           (java.util.concurrent CountDownLatch TimeUnit)))

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

(defn- open
  "A notebook as the servers open it: the warm-up is left to the caller."
  [{:keys [dir actors cfg]} & [opts]]
  (k/notebook (merge {:dir dir :actors actors :checkpoint cfg :warm-up :background} opts)))

(defn- open-warm [{:keys [dir actors cfg]}]
  (k/notebook {:dir dir :actors actors :checkpoint cfg}))

(defn- write-n!
  [nb key n & [prefix]]
  (vec (for [i (range n)]
         (let [{:keys [entry revision]} (k/write! nb {:title (str (or prefix "entry ") i) :labels {"kind" "fact"}
                                                      :actor "claude" :key key})]
           (k/observe! nb entry {:outcome "held" :revision revision :evidence ["checked"] :actor "claude" :key key})
           entry))))

(defn- signed-checkpoint [env n]
  (let [nb (open-warm env)
        ids (write-n! nb (:key env) n)]
    (is (some? (k/sign-checkpoint! nb)))
    ids))

(defn- checkpoint-files [{:keys [cfg]}]
  (->> (.listFiles (io/file (:dir cfg)))
       (filter #(str/ends-with? (.getName ^File %) ".checkpoint"))
       (sort-by #(.getName ^File %))))

(defn- revision-file ^File [{:keys [dir]} entry]
  (io/file dir "entries" entry "revisions" "00000001.edn"))

(defn- observation-file ^File [{:keys [dir]} entry]
  (first (filter #(str/ends-with? (.getName ^File %) ".edn")
                 (.listFiles (io/file dir "entries" entry "observations")))))

(defn- counting []
  (let [calls (atom 0)]
    [calls (fn [& args] (swap! calls inc) (apply verify/check-signature args))]))

(defn- refused [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(defn- tree-digest [nb]
  (merkle/digest (:merkle (index/tree (:store nb)))))

;; ------------------------------------------------------------ verdicts

(deftest a-verdict-not-yet-known-is-judged-never-taken-as-verified
  (when-let [env (setup)]
    (let [[good tampered] (signed-checkpoint env 2)]
      (spit (revision-file env tampered) (str/replace (slurp (revision-file env tampered)) "entry 1" "forged"))
      (let [[calls check] (counting)
            nb (open env {:check check})]
        (is (= :warming (get-in (k/checkpoint-status nb) [:warm-up :state])))
        (is (zero? (:seeded (k/checkpoint-status nb))) "nothing is taken from the checkpoint before it is read")
        (testing "a covered, unchanged record reads verified only after its own judgement"
          (is (= :verified (:signature (k/entry nb good))))
          (is (pos? @calls)))
        (testing "a record edited after the checkpoint reads as bad, not as the checkpoint's verdict"
          (is (= :bad-signature (:signature (k/entry nb tampered))))
          (is (false? (:signed? (k/entry nb tampered)))))
        (testing "signature terms judge on demand during the warm-up"
          (is (= [tampered] (map :entry (k/search nb "bad-signature"))))
          (is (= [tampered] (map :entry (k/search nb "unverified"))))
          (is (= [good] (map :entry (k/search nb "not unverified")))))))))

;; ------------------------------------------------------- missing records

(deftest missing-records-is-pending-until-the-comparison-has-run
  (when-let [env (setup)]
    (let [[a b] (signed-checkpoint env 2)]
      (io/delete-file (observation-file env b))
      (let [nb (open env)
            status (k/checkpoint-status nb)]
        (testing "while warming up, nothing is said about missing records"
          (is (= :pending (:missing status)))
          (is (not (contains? status :missing-records)))
          (is (not (contains? status :root)))
          (is (nil? (k/missing-records nb)))
          (doseq [selector ["missing-records" "not missing-records" "missing-records|label:kind=fact"
                            "label:kind=fact not missing-records"]]
            (is (= :kajero/warming-up (refused #(k/search nb selector))) selector)))
        (testing "other selectors answer meanwhile"
          (is (= 2 (count (k/search nb "label:kind=fact")))))
        (k/warm-up! nb)
        (testing "after the warm-up, the comparison answers"
          (is (= :ready (get-in (k/checkpoint-status nb) [:warm-up :state])))
          (is (= 1 (:missing (k/checkpoint-status nb))))
          (is (= [b] (map :entry (k/search nb "missing-records"))))
          (is (= [a] (map :entry (k/search nb "not missing-records")))))))))

(deftest the-home-page-mcp-and-the-web-report-the-warm-up
  (when-let [env (setup)]
    (signed-checkpoint env 1)
    (let [nb (open env)
          page #(:body (web/respond nb {:actor "seb"} "t0ken" {:method "GET" :path "/" :query nil}))
          tool #(mcp/call-tool nb {:actor "claude"} "kajero_checkpoint" {})]
      (is (str/includes? (page) "warming up"))
      (is (= :warming (get-in (tool) [:warm-up :state])))
      (is (= :pending (:missing (tool))))
      (is (= 503 (:status (web/respond nb {:actor "seb"} "t0ken"
                                       {:method "GET" :path "/view" :query "selector=missing-records"}))))
      (is (= 503 (:status (web/respond nb {:actor "seb"} "t0ken"
                                       {:method "GET" :path "/view.edn" :query "selector=missing-records"}))))
      (is (= :kajero/warming-up (refused #(mcp/call-tool nb {:actor "claude"} "kajero_search"
                                                         {"selector" "missing-records"}))))
      (k/warm-up! nb)
      (is (not (str/includes? (page) "warming up")))
      (is (str/includes? (page) "covering 2 of 2 records"))
      (let [t (tool)]
        (is (= :ready (get-in t [:warm-up :state])))
        (is (number? (get-in t [:warm-up :seconds])))
        (is (zero? (:missing t)))))))

;; ------------------------------------------------------------- signing

(deftest no-checkpoint-is-signed-until-the-tree-is-complete
  (when-let [env (setup)]
    (signed-checkpoint env 2)
    (let [before (count (checkpoint-files env))
          nb (open env)]
      (write-n! nb (:key env) 1 "during ")
      (is (nil? (k/sign-checkpoint! nb)))
      (k/keep-checkpointing! nb)
      (Thread/sleep 300)
      (is (= before (count (checkpoint-files env))) "keep-checkpointing! waits for the warm-up")
      (k/warm-up! nb)
      (let [until (+ (System/currentTimeMillis) 20000)]
        (while (and (= before (count (checkpoint-files env))) (< (System/currentTimeMillis) until))
          (Thread/sleep 50)))
      (is (= (inc before) (count (checkpoint-files env))) "the warm-up asks for the first checkpoint")
      (is (= 6 (get-in (k/checkpoint-status nb) [:latest :leaves])))
      (k/close! nb))))

(deftest a-shutdown-during-the-warm-up-signs-nothing
  (when-let [env (setup)]
    (signed-checkpoint env 2)
    (testing "close! before the warm-up started"
      (let [before (count (checkpoint-files env))
            nb (k/keep-checkpointing! (open env))]
        (write-n! nb (:key env) 1 "unsigned ")
        (k/close! nb)
        (is (= before (count (checkpoint-files env))))))
    (testing "close! while the warm-up judges"
      (let [before (count (checkpoint-files env))
            entered (CountDownLatch. 1)
            release (CountDownLatch. 1)
            check (fn [& args]
                    (.countDown entered)
                    (.await release 20 TimeUnit/SECONDS)
                    (apply verify/check-signature args))
            nb (k/keep-checkpointing! (open env {:check check}))
            warming (future (k/warm-up! nb))]
        (is (.await entered 20 TimeUnit/SECONDS) "the warm-up is judging a record")
        (k/close! nb)
        (.countDown release)
        @warming
        (Thread/sleep 200)
        (is (= before (count (checkpoint-files env))) "neither close! nor the end of the warm-up signed")))))

(deftest a-failed-warm-up-is-reported-and-tried-again-when-signing
  (when-let [env (setup)]
    (let [nb0 (k/notebook {:dir (:dir env) :actors (:actors env)})]
      (write-n! nb0 (:key env) 2))
    (let [failures (atom 1)
          check (fn [& args]
                  (when-not (neg? (swap! failures dec)) (throw (ex-info "the disk went away" {})))
                  (apply verify/check-signature args))
          nb (open env {:check check})]
      (is (thrown? Exception (k/warm-up! nb)))
      (let [status (k/checkpoint-status nb)]
        (is (= :failed (get-in status [:warm-up :state])))
        (is (str/includes? (get-in status [:warm-up :error]) "the disk went away"))
        (is (= :pending (:missing status))))
      (is (str/includes? (:body (web/respond nb {:actor "seb"} "t0ken" {:method "GET" :path "/" :query nil}))
                         "warm-up failed"))
      (is (some? (k/sign-checkpoint! nb)))
      (is (= :ready (get-in (k/checkpoint-status nb) [:warm-up :state])))
      (is (= 4 (get-in (k/checkpoint-status nb) [:latest :leaves]))))))

;; ------------------------------------------------- writes during warm-up

(defn- slow-check
  "A signature check that sleeps up to `max-ms`, so a warm-up overlaps
   the writes."
  [^Random rnd max-ms]
  (fn [& args]
    (Thread/sleep (long (locking rnd (.nextInt rnd (int (inc max-ms))))))
    (apply verify/check-signature args)))

(deftest writes-during-the-warm-up-give-the-root-built-from-scratch
  (when-let [base (setup)]
    (doseq [seed (range 6)]
      (let [rnd (Random. seed)
            env (assoc base :dir (str (temp/temp-dir) "/notes"))
            env (assoc-in env [:cfg :dir] (str (:dir env) ".checkpoints"))
            _ (when (even? seed) (signed-checkpoint env (+ 2 (.nextInt rnd 4))))
            _ (when (odd? seed) (write-n! (k/notebook {:dir (:dir env) :actors (:actors env)}) (:key env) 3))
            nb (open env {:check (slow-check rnd 3)})
            other (k/notebook {:dir (:dir env) :actors (:actors env)})
            writers (vec (for [w (range 3)]
                           (future (write-n! (if (zero? w) other nb) (:key env) (+ 2 (.nextInt rnd 4))
                                             (str "seed " seed " writer " w " ")))))
            refresher (future (dotimes [_ 20] (index/refresh! (:store nb)) (Thread/sleep 5)))
            warming (future (Thread/sleep (long (.nextInt rnd 30))) (k/warm-up! nb))]
        (run! deref writers)
        @refresher
        @warming
        (index/refresh! (:store nb))
        (let [fresh (open-warm env)]
          (is (:ready? (index/tree (:store nb))) (str "seed " seed))
          (is (= (merkle/size (:merkle (index/tree (:store fresh))))
                 (merkle/size (:merkle (index/tree (:store nb)))))
              (str "seed " seed))
          (let [a (set (merkle/leaves (:merkle (index/tree (:store fresh)))))
                b (set (merkle/leaves (:merkle (index/tree (:store nb)))))]
            (is (= (tree-digest fresh) (tree-digest nb))
                (str "seed " seed ": only from scratch " (pr-str (remove b a)) ", only after warm-up " (pr-str (remove a b)))))
          (is (= (:root (k/checkpoint-status fresh)) (:root (k/checkpoint-status nb))) (str "seed " seed)))))))

(deftest a-rebuild-during-writes-gives-the-root-built-from-scratch
  (when-let [env (setup)]
    (let [rnd (Random. 7)
          nb (open-warm env)
          _ (write-n! nb (:key env) 3)
          writers (vec (for [w (range 2)]
                         (future (write-n! nb (:key env) 4 (str "writer " w " ")))))
          rebuilding (future (dotimes [_ 3] (Thread/sleep (long (.nextInt rnd 20))) (index/rebuild-tree! (:store nb))))]
      (run! deref writers)
      @rebuilding
      (is (:ready? (index/tree (:store nb))))
      (is (= (tree-digest (open-warm env)) (tree-digest nb))))))
