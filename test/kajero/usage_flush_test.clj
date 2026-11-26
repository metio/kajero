;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.usage-flush-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.test-temp :as temp]
            [kajero.usage :as usage])
  (:import (java.time Instant)
           (java.util.concurrent CountDownLatch)))

(use-fixtures :each temp/each)

(def ^:private ^Instant t0 (Instant/parse "2026-11-25T10:00:00Z"))

(defn- write! [nb title]
  (:entry (k/write! nb {:title title :labels {"kind" "fact"} :actor "claude"})))

(defn- logged
  "The counts the notebook's log holds, without those still in memory."
  [nb]
  (usage/snapshot (:log (:usage nb))))

(defn- wait-for [f]
  (loop [n 0] (cond (f) true (< 500 n) false :else (do (Thread/sleep 10) (recur (inc n))))))

(deftest counts-are-flushed-on-an-interval
  (let [root (temp/temp-dir)
        nb (k/notebook {:dir (str root "/notes")})
        a (write! nb "CoreDNS caches answers")
        f (io/file root "notes.usage.edn")]
    (binding [k/*now* t0] (k/search! nb nil "coredns"))
    (is (not (.exists f)) "nothing is written on the request path")
    (usage/start-flushing! (:usage nb) 20)
    (try
      (is (wait-for #(.exists f)) "the flusher wrote the file")
      (is (= {:returned 1 :opened 0 :opened-after-return 0}
             (get-in (edn/read-string (slurp f)) [:days a (usage/day t0)])))
      (is (= {} @(:pending (:usage nb))) "the flushed counts left memory")
      (finally (k/close! nb)))))

(deftest close-stops-the-interval-and-writes-what-is-left
  (let [nb (k/notebook {:path (str (temp/temp-dir) "/notes.db")})
        a (write! nb "CoreDNS caches answers")]
    (usage/start-flushing! (:usage nb) 3600000)
    (binding [k/*now* t0] (k/search! nb nil "coredns"))
    (is (= {} (:days (logged nb))))
    (k/close! nb)
    (is (= {:returned 1 :opened 0 :opened-after-return 0} (get-in (logged nb) [:days a (usage/day t0)])))
    (is (.isCancelled ^java.util.concurrent.Future @(:flusher (:usage nb))))))

(deftest a-report-reads-counts-not-yet-flushed
  (let [nb (k/notebook {:path (str (temp/temp-dir) "/notes.db")})
        a (write! nb "CoreDNS caches answers")
        b (write! nb "Velero runs at two")]
    (binding [k/*now* t0] (k/search! nb nil "coredns"))
    (is (= #{b} (set (map :entry (k/search nb "never-returned")))))
    (k/flush! nb)
    (binding [k/*now* t0] (k/search! nb nil "coredns"))
    (is (= 2 (usage/total (usage/of-entry (usage/counts (:usage nb)) a) :returned))
        "the report adds what is held to what is written, once each")))

(deftest no-count-is-lost-while-flushes-run
  (doseq [[backend nb] [["sqlite" (k/notebook {:path (str (temp/temp-dir) "/notes.db")})]
                        ["files" (k/notebook {:dir (str (temp/temp-dir) "/notes")})]]]
    (testing backend
      (let [a (write! nb "CoreDNS caches answers")
            threads 8
            per-thread 200
            start (CountDownLatch. 1)]
        (usage/start-flushing! (:usage nb) 1)
        (let [workers (doall (for [_ (range threads)]
                               (future (.await start)
                                       (dotimes [_ per-thread]
                                         (usage/searched! (:usage nb) nil [a] t0)
                                         (usage/opened! (:usage nb) nil a t0)))))]
          (.countDown start)
          (run! deref workers))
        (k/close! nb)
        (let [c (get-in (logged nb) [:days a (usage/day t0)])]
          (is (= (* threads per-thread) (:returned c)))
          (is (= (* threads per-thread) (:opened c))))))))

(deftest counts-beyond-the-bound-are-dropped-and-logged
  (let [nb (k/notebook {:path (str (temp/temp-dir) "/notes.db")})
        es (mapv #(write! nb (str "entry " %)) (range 3))
        lines (atom [])]
    (with-redefs [usage/max-pending 2
                  usage/log! (fn [& parts] (swap! lines conj (str/join " " parts)))]
      (doseq [e es] (usage/opened! (:usage nb) nil e t0))
      (usage/opened! (:usage nb) nil (first es) t0))
    (is (= 2 (count (get @(:pending (:usage nb)) (usage/day t0)))))
    (is (= 2 (get-in @(:pending (:usage nb)) [(usage/day t0) (first es) :opened]))
        "an entry already held still counts")
    (is (= ["kajero: usage not recorded for a read - the counts not yet flushed are at their bound"] @lines))))

(deftest counts-are-flushed-when-the-jvm-shuts-down
  (let [root (temp/temp-dir)
        dir (str root "/notes")
        nb (k/notebook {:dir dir})
        a (write! nb "CoreDNS caches answers")
        script (str "(require '[kajero.core :as k])"
                    "(let [nb (k/notebook {:dir " (pr-str dir) "})]"
                    "  (k/keep-flushing! nb)"
                    "  (k/search! nb nil \"coredns\")"
                    "  (System/exit 0))")
        {:keys [exit out err]} (sh/sh "java" "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" script)]
    (is (zero? exit) (str out err))
    (is (= 1 (usage/total (usage/of-entry (edn/read-string (slurp (io/file root "notes.usage.edn"))) a) :returned))
        "the shutdown hook wrote the count the process held")))
