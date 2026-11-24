;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.test-temp-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.test-temp :as temp]))

(use-fixtures :each temp/each)

(defn- gone? [dir] (not (.exists (io/file dir))))

(deftest every-temp-dir-lives-under-the-run-root
  (let [dir (temp/temp-dir)
        root (str (temp/root))]
    (is (str/starts-with? dir (str root "/")))
    (is (str/starts-with? root (System/getProperty "java.io.tmpdir")))))

(deftest a-test-that-throws-leaves-no-notebook-behind
  (let [made (atom [])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"deliberate"
                          (temp/each (fn []
                                       (let [dir (temp/temp-dir)]
                                         (swap! made conj dir)
                                         (k/write! (k/notebook {:dir (str dir "/notes")})
                                                   {:title "t" :labels {"kind" "fact"} :actor "claude"})
                                         (throw (ex-info "deliberate" {})))))))
    (is (= 1 (count @made)))
    (is (every? gone? @made))))

(deftest a-test-that-passes-leaves-no-notebook-behind
  (let [made (atom nil)]
    (temp/each (fn [] (reset! made (temp/temp-dir))
                 (k/write! (k/notebook {:path (str @made "/notes.db")})
                           {:title "t" :labels {"kind" "fact"} :actor "claude"})))
    (is (gone? @made))))

(def ^:private failing-run
  "A run with one failing and one throwing test that exits non-zero, as a
   red suite does, with its temp dirs and their notebooks still on disk."
  "(ns throwaway-test
     (:require [clojure.test :refer [deftest is use-fixtures run-tests]]
               [kajero.core :as k]
               [kajero.test-temp :as temp]))
   (use-fixtures :each temp/each)
   (defn- notebook [] (k/notebook {:dir (str (temp/temp-dir) \"/notes\")}))
   (deftest fails (k/write! (notebook) {:title \"t\" :labels {\"kind\" \"fact\"} :actor \"claude\"}) (is (= 1 2)))
   (deftest throws (notebook) (throw (ex-info \"deliberate\" {})))
   (def kept (k/notebook {:dir (str (temp/temp-dir) \"/notes\")}))
   (let [{:keys [fail error]} (run-tests)]
     (System/exit (+ fail error)))")

(deftest a-failing-run-leaves-its-tmpdir-as-it-found-it
  (let [tmp (temp/temp-dir)
        java (str (System/getProperty "java.home") "/bin/java")
        {:keys [exit out err]} (sh/sh java (str "-Djava.io.tmpdir=" tmp) "--enable-native-access=ALL-UNNAMED"
                                      "-cp" (System/getProperty "java.class.path")
                                      "clojure.main" "-e" failing-run)]
    (testing "the run is red"
      (is (= 2 exit) (str out err)))
    (is (= [] (vec (.list (io/file tmp)))))))

(deftest test-namespaces-take-temp-dirs-from-the-shared-helper
  (let [direct (for [^java.io.File f (file-seq (io/file "test"))
                     :when (and (.isFile f) (str/ends-with? (.getName f) ".clj")
                                (not= "test_temp.clj" (.getName f))
                                (not= "test_temp_test.clj" (.getName f)))
                     :when (re-find #"createTemp(Directory|File)|File/createTempFile|mktemp" (slurp f))]
                 (.getPath f))]
    (is (empty? direct))))
