;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.print-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.print :as kp]
            [kajero.record :as record]
            [kajero.store :as store]
            [kajero.test-temp :as temp]))

(use-fixtures :each temp/each)

(def ^:private namespaced
  {:entry "e-1" :seq 1 :actor "claude" :at "2026-11-26T00:00:00Z" :title "t"
   :labels {"kind" "fact"}
   :data {:a/x 1 :a/y 2}})

(def ^:private nested
  (assoc namespaced :data {:hosts [{:host/name "w-1" :host/cpus 8}
                                   {:host/name "w-2" :host/cpus 4 :host/tags #{:t/a :t/b}}]
                           :deep {:x/y {:x/z {:q/r (vec (range 20))}}}}))

(defn- under
  "Calls f with every print var bound to a setting a caller might use."
  [f]
  {:clojure-main (binding [*print-namespace-maps* true] (f))
   :http-thread (binding [*print-namespace-maps* false] (f))
   :length (binding [*print-length* 2] (f))
   :level (binding [*print-level* 1] (f))
   :meta (binding [*print-meta* true] (f))
   :dup (binding [*print-dup* true] (f))
   :unreadable (binding [*print-readably* false] (f))
   :all (binding [*print-namespace-maps* true *print-length* 1 *print-level* 1
                  *print-meta* true *print-readably* false] (f))})

(deftest edn-str-ignores-the-callers-print-settings
  (doseq [x [{:a/x 1 :a/y 2} (:data nested) (with-meta {:a/x "s"} {:m 1}) ["a \"q\"" \c 1.5M 1/3]]]
    (let [results (under #(kp/edn-str x))]
      (is (= 1 (count (set (vals results)))) (pr-str results))
      (is (= x (edn/read-string (:all results)))))))

(deftest namespaced-maps-print-in-full
  (is (= "{:a/x 1, :a/y 2}" (binding [*print-namespace-maps* true] (kp/edn-str {:a/x 1 :a/y 2}))))
  (is (= "#:a{:x 1, :y 2}" (kp/edn-str {:namespace-maps true} {:a/x 1 :a/y 2}))))

(deftest a-record-hashes-the-same-under-every-print-setting
  (doseq [rec [namespaced nested]]
    (let [hashes (under #(record/hash-of rec))]
      (is (= 1 (count (set (vals hashes)))) (pr-str hashes)))))

(deftest the-canonical-bytes-are-the-http-threads-rendering
  (testing "every record hashed by the HTTP service keeps its hash"
    (is (= (binding [*print-namespace-maps* false] (pr-str (into (sorted-map) (update namespaced :data #(into (sorted-map) %)))))
           (String. (record/->bytes namespaced) "UTF-8")))))

(deftest legacy-bytes-exist-only-where-the-renderings-differ
  (is (nil? (record/legacy-bytes (dissoc namespaced :data))))
  (is (nil? (record/legacy-bytes (assoc namespaced :data {:a/x 1 :b/y 2}))) "keys of two namespaces print alike")
  (is (= "#:a{:x 1, :y 2}"
         (re-find #"#:a\{[^}]*\}" (String. ^bytes (record/legacy-bytes namespaced) "UTF-8")))))

(deftest stores-write-the-whole-record-whatever-the-print-settings
  (doseq [[backend s] [[:files (store/files (str (temp/temp-dir) "/notes"))]
                       [:sqlite (store/sqlite (str (temp/temp-dir) "/notes.db"))]]]
    (testing (name backend)
      (let [hash (binding [*print-length* 1 *print-level* 1 *print-namespace-maps* true]
                   (store/append! s :revision nested nil))
            back (dissoc (first (store/revisions s "e-1")) :sig)]
        (is (= nested back))
        (is (= hash (record/hash-of back)))))))

(deftest flat-files-hold-namespaced-maps-in-full
  (let [dir (str (temp/temp-dir) "/notes")
        s (store/files dir)]
    (binding [*print-namespace-maps* true] (store/append! s :revision namespaced nil))
    (is (re-find #"\{:a/x 1, :a/y 2\}" (slurp (io/file dir "entries" "e-1" "revisions" "00000001.edn"))))))

(deftest a-notebook-written-on-either-thread-reads-the-same-revision
  (let [nb (k/notebook {:dir (str (temp/temp-dir) "/notes")})
        {:keys [entry revision]} (binding [*print-namespace-maps* true]
                                   (k/write! nb {:title "t" :labels {"kind" "fact"} :actor "claude"
                                                 :data {:a/x 1 :a/y 2}}))]
    (is (= revision (binding [*print-namespace-maps* false] (:revision (k/entry nb entry)))))
    (is (= revision (binding [*print-namespace-maps* true] (:revision (k/entry nb entry)))))))
