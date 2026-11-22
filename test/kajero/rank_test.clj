;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.rank-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kajero.core :as k]
            [kajero.rank :as rank]
            [kajero.select :as select])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)))

(defn- notebook []
  (k/notebook {:path (str (Files/createTempDirectory "kajero" (make-array FileAttribute 0)) "/notes.db")}))

(defmacro ^:private at [instant & body]
  `(binding [k/*now* (Instant/parse ~instant)] (let [res# (do ~@body)] res#)))

;; --- the pure half ----------------------------------------------------------------

(deftest text-terms-are-the-positive-words
  (is (= ["ingress" "nginx"] (select/text-terms "ingress ~nginx")))
  (is (= ["nginx"] (select/text-terms "label:system=dns not ~legacy nginx not disputed")))
  (is (= ["jumphost"] (select/text-terms "~JumpHost")) "lower-cased")
  (is (= [] (select/text-terms "label:kind actor=seb ticket=x disputed stale unsigned superseded retired outdated-schema")))
  (is (= [] (select/text-terms "")))
  (is (= [] (select/text-terms nil))))

(deftest a-title-match-outweighs-a-body-full-of-repeats
  (let [title {:title "Ingress nginx timeouts" :labels "" :body "" :data ""}
        body {:title "Proxy notes" :labels ""
              :body (str/join " " (repeat 40 "ingress")) :data ""}]
    (is (> (rank/score title ["ingress"]) (rank/score body ["ingress"])))))

(deftest every-field-counts-by-its-weight
  (is (= 12 (rank/score {:title "dns" :labels "" :body "" :data ""} ["dns"])))
  (is (= 6 (rank/score {:title "" :labels "system=dns" :body "" :data ""} ["dns"])))
  (is (= 2 (rank/score {:title "" :labels "" :body "dns" :data ""} ["dns"])))
  (is (= 1 (rank/score {:title "" :labels "" :body "" :data "{:dns 1}"} ["dns"])))
  (is (= 10 (rank/score {:title "" :labels "" :body "dns dns dns dns dns dns dns" :data ""} ["dns"]))
      "repeats count up to five")
  (is (zero? (rank/score {:title "x" :labels "" :body "" :data ""} ["dns"])))
  (is (= 24 (rank/score {:title "dns cache" :labels "" :body "" :data ""} ["dns" "cache"])) "terms add up"))

(deftest matching-ignores-case-and-reads-the-term-literally
  (is (= 12 (rank/score {:title "CoreDNS" :labels "" :body "" :data ""} ["dns"])))
  (is (zero? (rank/score {:title "a.b" :labels "" :body "" :data ""} ["a*b"])) "no regex in a term")
  (is (= 12 (rank/score {:title "a*b" :labels "" :body "" :data ""} ["a*b"]))))

(deftest a-match-names-its-field-and-the-text-around-it
  (let [body (str (str/join " " (repeat 30 "filler")) " reach it through the jumphost at 10.0.0.1 "
                  (str/join " " (repeat 30 "padding")))
        [m] (rank/matches {:title "Network notes" :labels "kind=howto" :body body :data ""} ["jumphost"])]
    (is (= "jumphost" (:term m)))
    (is (= "body" (:field m)))
    (is (str/includes? (:snippet m) "reach it through the jumphost at 10.0.0.1"))
    (is (str/starts-with? (:snippet m) "…"))
    (is (str/ends-with? (:snippet m) "…"))
    (is (<= (count (:snippet m)) (+ 2 (count "jumphost") 120)))
    (is (not (re-find #"…\S*fille[^r]" (:snippet m))) "cut at a word boundary")))

(deftest the-most-telling-field-wins-and-a-missing-term-is-left-out
  (is (= [{:term "dns" :field "title" :snippet "CoreDNS caches"}]
         (rank/matches {:title "CoreDNS caches" :labels "system=dns" :body "dns" :data ""} ["dns" "absent"]))))

(deftest a-snippet-keeps-short-text-whole-and-on-one-line
  (is (= "first line second line"
         (:snippet (first (rank/matches {:title "" :labels "" :body "first line\nsecond line" :data ""} ["second"]))))))

;; --- search ------------------------------------------------------------------------

(deftest search-ranks-by-score-then-newest
  (let [nb (notebook)
        {body-only :entry} (at "2026-11-01T00:00:00Z"
                               (k/write! nb {:title "Proxy notes" :body "the ingress controller restarts"
                                             :labels {"kind" "fact"} :actor "claude"}))
        {in-title :entry} (at "2026-10-01T00:00:00Z"
                              (k/write! nb {:title "Ingress nginx timeouts" :body "raise proxy-read-timeout"
                                            :labels {"kind" "gotcha"} :actor "claude"}))
        {newer-tie :entry} (at "2026-11-05T00:00:00Z"
                               (k/write! nb {:title "Gateway notes" :body "an ingress is replaced by a route"
                                             :labels {"kind" "fact"} :actor "claude"}))]
    (is (= [in-title newer-tie body-only] (mapv :entry (k/search nb "ingress"))))
    (is (= [12 2 2] (mapv :score (k/search nb "ingress"))))
    (testing "without text the order stays newest first and carries no score"
      (is (= [newer-tie body-only in-title] (mapv :entry (k/search nb ""))))
      (is (every? #(not (contains? % :score)) (k/search nb "label:kind")))
      (is (every? #(not (contains? % :matches)) (k/search nb "label:kind"))))))

(deftest a-search-result-says-where-each-term-matched
  (let [nb (notebook)
        _ (k/write! nb {:title "Network notes"
                        :body (str "Overview.\n\n" (str/join " " (repeat 20 "detail"))
                                   " reach the nodes through the jumphost only")
                        :labels {"kind" "howto"} :data [{:host "bastion-1"}] :actor "claude"})
        [r] (k/search nb "jumphost bastion")]
    (is (= "Overview." (:summary r)) "the summary is still the first line")
    (is (= [{:term "jumphost" :field "body"} {:term "bastion" :field "data"}]
           (mapv #(select-keys % [:term :field]) (:matches r))))
    (is (str/includes? (:snippet (first (:matches r))) "through the jumphost only"))))

(deftest negated-text-does-not-rank-or-match
  (let [nb (notebook)
        _ (k/write! nb {:title "nginx" :body "" :labels {"kind" "fact"} :actor "claude"})
        [r] (k/search nb "nginx not legacy")]
    (is (= ["nginx"] (map :term (:matches r))))
    (is (= 12 (:score r)))))
