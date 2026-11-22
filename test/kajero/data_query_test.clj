;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.data-query-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.mcp :as mcp]
            [kajero.select :as select])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "kajero" (make-array FileAttribute 0))))

(def ^:private ^:dynamic *backend* :sqlite)

(use-fixtures :each (fn [run-test] (doseq [backend [:sqlite :files]]
                                     (binding [*backend* backend]
                                       (testing (name backend) (run-test))))))

(defn- notebook []
  (k/notebook (if (= :files *backend*)
                {:dir (str (temp-dir) "/notes")}
                {:path (str (temp-dir) "/notes.db")})))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(def ^:private readings
  [{:domain "lms.example.org" :record :a :ttl 300 :ns "ns1.example.net"}
   {:domain "lms.example.org" :record :aaaa :ttl 300 :ns "ns2.example.net"}
   {:domain "pad.example.org" :record :a :ttl 3600 :ns "ns1.example.net"
    :owner {:team "dns" :route "pad-route"}}])

(defn- fixture [nb]
  {:dns (:entry (k/write! nb {:title "DNS readings 2026-11-22" :labels {"kind" "fact" "system" "dns"}
                              :data readings :actor "claude"}))
   :host (:entry (k/write! nb {:title "One host" :labels {"kind" "fact"}
                               :data {"domain" "mail.example.org" "ready" true} :actor "claude"}))
   :plain (:entry (k/write! nb {:title "No data" :labels {"kind" "fact"} :actor "claude"}))})

(defn- ids [results] (set (map :entry results)))

(deftest a-field-value-picks-entries-and-returns-the-matching-rows
  (let [nb (notebook)
        {:keys [dns]} (fixture nb)
        [r :as results] (k/search nb "data:domain=lms.example.org")]
    (is (= #{dns} (ids results)))
    (is (= (subvec readings 0 2) (:rows r)) "only the rows that match, in order")))

(deftest a-field-alone-asks-for-presence
  (let [nb (notebook)
        {:keys [dns host]} (fixture nb)]
    (is (= #{dns host} (ids (k/search nb "data:domain"))))
    (is (= #{dns} (ids (k/search nb "data:owner"))))
    (is (= [(nth readings 2)] (:rows (first (k/search nb "data:owner")))))))

(deftest values-compare-in-printed-form
  (let [nb (notebook)
        {:keys [dns host]} (fixture nb)]
    (is (= #{dns} (ids (k/search nb "data:ttl=3600"))) "a number")
    (is (= #{dns} (ids (k/search nb "data:record=aaaa"))) "a keyword without its colon")
    (is (= #{host} (ids (k/search nb "data:ready=true"))) "a boolean")
    (is (empty? (ids (k/search nb "data:ttl=36"))) "the whole value, not a substring")))

(deftest a-map-of-data-is-one-row-and-string-keys-match
  (let [nb (notebook)
        {:keys [host]} (fixture nb)]
    (is (= #{host} (ids (k/search nb "data:domain=mail.example.org"))))
    (is (= [{"domain" "mail.example.org" "ready" true}]
           (:rows (first (k/search nb "data:domain=mail.example.org")))))))

(deftest a-dotted-field-follows-nested-maps
  (let [nb (notebook)
        {:keys [dns]} (fixture nb)]
    (is (= #{dns} (ids (k/search nb "data:owner.team=dns"))))
    (is (empty? (ids (k/search nb "data:owner.team=web"))))
    (is (empty? (ids (k/search nb "data:domain.team"))) "a scalar has no nested field")))

(deftest data-terms-hold-in-the-same-row
  (let [nb (notebook)
        {:keys [dns]} (fixture nb)]
    (is (= #{dns} (ids (k/search nb "data:domain=pad.example.org data:ttl=3600"))))
    (is (empty? (ids (k/search nb "data:domain=lms.example.org data:ttl=3600")))
        "each term holds in some row, but no row holds both")
    (is (= [(first readings)]
           (:rows (first (k/search nb "data:domain=lms.example.org data:record=a")))))))

(deftest a-negated-data-term-means-no-row-matches
  (let [nb (notebook)
        {:keys [dns host plain]} (fixture nb)]
    (is (= #{host plain} (ids (k/search nb "not data:ns=ns1.example.net"))))
    (is (= #{dns} (ids (k/search nb "data:domain not data:ready"))))
    (is (every? #(not (contains? % :rows)) (k/search nb "not data:ns=ns1.example.net"))
        "only positive data terms return rows")))

(deftest data-terms-compose-with-the-other-terms
  (let [nb (notebook)
        {:keys [dns]} (fixture nb)]
    (is (= #{dns} (ids (k/search nb "label:system=dns data:ttl=300"))))
    (is (empty? (ids (k/search nb "label:system=web data:ttl=300"))))
    (is (= #{dns} (ids (k/search nb "readings data:ttl=300"))))
    (is (pos? (:score (first (k/search nb "readings data:ttl=300")))) "text still ranks")))

(deftest a-search-without-data-terms-returns-no-rows
  (let [nb (notebook)]
    (fixture nb)
    (is (every? #(not (contains? % :rows)) (k/search nb "")))
    (is (every? #(not (contains? % :rows)) (k/search nb "label:kind")))))

(deftest a-data-term-needs-a-field
  (let [nb (notebook)]
    (is (= :select/bad-term (refusal #(k/search nb "data:"))))
    (is (= :select/bad-term (refusal #(k/search nb "data:=x"))))
    (is (= :select/bad-term (refusal #(select/compile "not data:"))))))

(deftest data-terms-over-mcp
  (let [nb (notebook)
        {:keys [dns]} (fixture nb)
        [r] (mcp/call-tool nb {:actor "claude"} "kajero_search" {"selector" "data:domain=pad.example.org"})]
    (is (= dns (:entry r)))
    (is (= [(nth readings 2)] (:rows r)))))
