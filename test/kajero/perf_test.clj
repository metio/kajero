;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.perf-test
  "Timing bounds over a generated notebook the size of the team's, about
   1300 entries. The bounds are generous, ten times what the index takes
   on a laptop, so they fail on a return to reading the store per entry
   and not on a slow machine."
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.record :as record]
            [kajero.store :as store]
            [kajero.test-temp :as temp]))

(use-fixtures :each temp/each)

(def ^:private entries 1300)

(def ^:private words ["calico" "coredns" "velero" "gateway" "flux" "kyverno" "postgres" "redis" "cilium" "harbor"])

(defn- generate!
  "Writes a notebook of `entries` entries, two revisions each for every
   other one, an observation on every tenth and a link from every
   sixth, straight into the store."
  [s]
  (let [ids (vec (repeatedly entries #(str (random-uuid))))]
    (doseq [[i id] (map-indexed vector ids)
            :let [w (words (mod i (count words)))
                  r1 {:entry id :seq 1 :base nil :title (str "Entry " i " about " w)
                      :body (str "Notes on " w " in templates/" w "/values.yaml.\n" (str/join " " (repeat 40 w)))
                      :labels {"kind" (if (even? i) "fact" "gotcha") "component" w "project" "metio"}
                      :evidence [(str "kubectl get " w) "tik:5084203b-b89a-479e-9075-02fdf34806d9"]
                      :data (when (zero? (mod i 5)) [{:host (str "h-" i) :cpus (mod i 16)}])
                      :actor "claude" :at (str "2026-11-" (format "%02d" (inc (mod i 25))) "T10:00:00Z")}]]
      (store/append! s :revision r1 nil)
      (when (even? i)
        (store/append! s :revision (assoc r1 :seq 2 :base (record/hash-of r1) :body (str (:body r1) " revised") :actor "seb") nil))
      (when (zero? (mod i 10))
        (store/append! s :observation {:entry id :revision "sha256-x" :outcome "held" :actor "seb"
                                       :evidence [] :at "2026-11-26T10:00:00Z"} nil))
      (when (and (pos? i) (zero? (mod i 6)))
        (store/append! s :link {:source id :type "see-also" :target (ids (dec i)) :actor "claude"
                                :at "2026-11-26T10:00:00Z"} nil)))
    ids))

(defn- ms [f]
  (let [start (System/nanoTime)]
    (f)
    (/ (- (System/nanoTime) start) 1e6)))

(deftest a-search-over-1300-entries-stays-fast
  (doseq [[backend where] [["files" {:dir (str (temp/temp-dir) "/notes")}]
                           ["sqlite" {:path (str (temp/temp-dir) "/notes.db")}]]]
    (testing backend
      (let [ids (generate! (store/open where))
            nb (k/notebook where)]
        (is (= entries (count (k/search nb ""))))
        ;; the first search fills the caches of derived values
        (k/search nb "~coredns")
        (let [times {:empty (ms #(k/search nb ""))
                     :text (ms #(k/search nb "~coredns gateway|velero"))
                     :label (ms #(k/search nb "label:component=flux not superseded"))
                     :labels (ms #(k/labels nb))
                     :entry (ms #(k/entry nb (ids 12)))}]
          (println "kajero.perf-test" backend (update-vals times math/round))
          (is (< (:empty times) 3000) (pr-str times))
          (is (< (:text times) 3000) (pr-str times))
          (is (< (:label times) 3000) (pr-str times))
          (is (< (:labels times) 500) (pr-str times))
          (is (< (:entry times) 500) (pr-str times)))))))
