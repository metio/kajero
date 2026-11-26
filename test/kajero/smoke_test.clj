;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.smoke-test
  "dev/smoke.clj leaves no temporary directory and no process behind,
   whether a check fails, the kajero it starts dies, or smoke itself is
   stopped. The kajero here is a shell script standing in for one, so
   these tests need bb and sh but no build."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.test-temp :as temp])
  (:import (java.io File)
           (java.util.concurrent TimeUnit)))

(use-fixtures :each temp/each)

(defn- bb? []
  (try (zero? (:exit (sh/sh "bb" "--version"))) (catch Exception _ false)))

(defn- stub!
  "An executable shell script in `dir` with the body `body`."
  [dir body]
  (let [f (io/file dir "kajero-stub")]
    (spit f (str "#!/bin/sh\n" body "\n"))
    (.setExecutable f true)
    (str f)))

(defn- smoke
  "A ProcessBuilder running dev/smoke.clj with `stub` as kajero, making its
   temporary directory in `parent`."
  ^ProcessBuilder [parent stub]
  (doto (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["bb" "dev/smoke.clj" (str stub)]))
    (.redirectErrorStream true)
    (.redirectOutput (io/file parent "smoke.log"))
    (-> .environment (.put "KAJERO_SMOKE_TMPDIR" (str parent)))))

(defn- smoke-dirs [parent]
  (filter #(str/starts-with? (.getName ^File %) "kajero-smoke") (.listFiles (io/file parent))))

(defn- alive? [pid]
  (.isPresent (java.lang.ProcessHandle/of (long pid))))

(deftest a-failing-smoke-run-leaves-nothing-behind
  (when (bb?)
    (testing "serve dies before it answers"
      (let [parent (temp/temp-dir)
            scripts (temp/temp-dir)
            p (.start (smoke parent (stub! scripts "exit 3")))]
        (is (.waitFor p 60 TimeUnit/SECONDS))
        (is (= 1 (.exitValue p)))
        (is (str/includes? (slurp (io/file parent "smoke.log")) "serve ended with 3"))
        (is (empty? (smoke-dirs parent)) "the temporary directory is gone")))
    (testing "serve hangs and smoke is stopped with SIGTERM"
      (let [parent (temp/temp-dir)
            scripts (temp/temp-dir)
            ;; the stub runs in smoke's temporary directory and says so
            p (.start (smoke parent (stub! scripts (str "echo $$ > " scripts "/pid; pwd > " scripts "/cwd; exec sleep 300"))))
            pid-file (io/file scripts "pid")
            until (+ (System/currentTimeMillis) 30000)]
        (while (and (not (.exists pid-file)) (< (System/currentTimeMillis) until)) (Thread/sleep 50))
        (Thread/sleep 200)
        (let [pid (parse-long (str/trim (slurp pid-file)))
              cwd (str/trim (slurp (io/file scripts "cwd")))]
          (is (str/includes? cwd "kajero-smoke") "the stub ran in smoke's temporary directory")
          (is (seq (smoke-dirs parent)))
          (.destroy p)
          (is (.waitFor p 30 TimeUnit/SECONDS))
          (let [until (+ (System/currentTimeMillis) 10000)]
            (while (and (alive? pid) (< (System/currentTimeMillis) until)) (Thread/sleep 50)))
          (is (not (alive? pid)) "the process smoke started is stopped")
          (is (empty? (smoke-dirs parent)) "the temporary directory is gone"))))))
