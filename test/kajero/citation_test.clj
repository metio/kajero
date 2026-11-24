;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.citation-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.cite :as cite]
            [kajero.core :as k]
            [kajero.test-temp :as temp])
  (:import (java.time Instant)))

(def ^:private ^:dynamic *backend* :sqlite)

(use-fixtures :each temp/each (fn [run-test] (doseq [backend [:sqlite :files]]
                                               (binding [*backend* backend]
                                                 (testing (name backend) (run-test))))))

(defn- notebook []
  (k/notebook (if (= :files *backend*)
                {:dir (str (temp/temp-dir) "/notes")}
                {:path (str (temp/temp-dir) "/notes.db")})))

(defmacro ^:private at [instant & body]
  `(binding [k/*now* (Instant/parse ~instant)] (let [res# (do ~@body)] res#)))

(defn- write! [nb title body]
  (:entry (k/write! nb {:title title :body body :labels {"kind" "fact"} :actor "claude"})))

(defn- revise! [nb id body]
  (k/revise! nb id {:base (:revision (k/entry nb id)) :body body :actor "claude"}))

(defn- link! [nb source type target]
  (k/link! nb {:source source :type type :target target :actor "claude"}))

(defn- short-id [id] (subs id 0 8))

(defn- result [nb selector id]
  (some #(when (= id (:entry %)) %) (k/search nb selector)))

;; --- the pure half ----------------------------------------------------------------

(deftest a-body-reference-is-an-id-or-prefix-in-double-brackets
  (is (= #{"8b8f9e64" "0acbbdeb-1111-2222-3333-444455556666"}
         (cite/references "see [[8b8f9e64]] and [[0acbbdeb-1111-2222-3333-444455556666]], again [[8b8f9e64]]")))
  (is (= #{} (cite/references "[[abc]] is too short, [[not-an-id]] is prose, [8b8f9e64] is one bracket")))
  (is (= #{} (cite/references nil))))

(deftest the-boost-is-bounded-below-a-label-match
  (is (zero? (cite/boost 0)))
  (is (= 1 (cite/boost 1)))
  (is (= cite/max-boost (cite/boost 1000)))
  (is (< cite/max-boost (- 6 2)) "a citation boost never makes up for a term matching in the labels instead of the body"))

;; --- search ------------------------------------------------------------------------

(deftest a-cited-entry-ranks-above-an-equally-matching-uncited-one
  (let [nb (notebook)
        cited (at "2026-10-01T00:00:00Z" (write! nb "Ingress timeouts" "raise proxy-read-timeout"))
        uncited (at "2026-11-01T00:00:00Z" (write! nb "Ingress timeouts" "raise proxy-read-timeout"))]
    (testing "uncited, the newer of two equal matches comes first"
      (is (= [uncited cited] (mapv :entry (k/search nb "ingress")))))
    (at "2026-11-02T00:00:00Z" (write! nb "Gateway rollout" (str "the timeout fix in [[" (short-id cited) "]] applied")))
    (testing "a body reference makes the older one come first"
      (is (= [cited uncited] (mapv :entry (k/search nb "ingress"))) "body reference")
      (is (= 1 (:citations (result nb "ingress" cited))))
      (is (zero? (:citations (result nb "ingress" uncited))))
      (is (= (:score (result nb "ingress" cited)) (:score (result nb "ingress" uncited)))
          ":score stays the term score")
      (is (= (inc (:score (result nb "ingress" cited))) (:rank (result nb "ingress" cited)))))))

(deftest a-see-also-link-cites-its-target
  (let [nb (notebook)
        cited (at "2026-10-01T00:00:00Z" (write! nb "Ingress timeouts" ""))
        uncited (at "2026-11-01T00:00:00Z" (write! nb "Ingress timeouts" ""))
        citer (write! nb "Gateway rollout" "")]
    (link! nb citer "see-also" cited)
    (is (= [cited uncited] (mapv :entry (k/search nb "ingress"))))
    (testing "a link counts once with a body reference from the same entry"
      (revise! nb citer (str "see [[" (short-id cited) "]] and [[" cited "]]"))
      (is (= 1 (:citations (result nb "ingress" cited)))))))

(deftest supersedes-and-contradicts-are-not-endorsement
  (let [nb (notebook)
        target (at "2026-10-01T00:00:00Z" (write! nb "Ingress timeouts" ""))
        other (at "2026-11-01T00:00:00Z" (write! nb "Ingress timeouts" ""))
        replacement (write! nb "Gateway rollout" "")
        dispute (write! nb "Gateway notes" "")]
    (link! nb replacement "supersedes" target)
    (link! nb dispute "contradicts" target)
    (is (zero? (:citations (result nb "ingress" target))))
    (is (= [other target] (mapv :entry (k/search nb "ingress"))))))

(deftest an-entry-citing-itself-does-not-count
  (let [nb (notebook)
        self (at "2026-10-01T00:00:00Z" (write! nb "Ingress timeouts" ""))
        other (at "2026-11-01T00:00:00Z" (write! nb "Ingress timeouts" ""))]
    (at "2026-10-02T00:00:00Z" (revise! nb self (str "as [[" (short-id self) "]] said")))
    (at "2026-10-03T00:00:00Z" (revise! nb self (str "as [[" self "]] said, again")))
    (link! nb self "see-also" self)
    (is (zero? (:citations (result nb "ingress" self))))
    (is (= [other self] (mapv :entry (k/search nb "ingress"))) "the newer other one still comes first")))

(deftest only-the-current-revision-of-a-citing-entry-counts
  (let [nb (notebook)
        cited (at "2026-10-01T00:00:00Z" (write! nb "Ingress timeouts" ""))
        _ (at "2026-11-01T00:00:00Z" (write! nb "Ingress timeouts" ""))
        citer (write! nb "Gateway rollout" (str "see [[" (short-id cited) "]]"))]
    (is (= 1 (:citations (result nb "ingress" cited))))
    (revise! nb citer "the reference is gone")
    (is (zero? (:citations (result nb "ingress" cited))))))

(deftest retired-and-superseded-citers-do-not-count
  (let [nb (notebook)
        cited (write! nb "Ingress timeouts" "")
        retired (write! nb "Gateway rollout" (str "see [[" (short-id cited) "]]"))
        old (write! nb "Gateway notes" (str "see [[" (short-id cited) "]]"))
        new (write! nb "Gateway notes v2" "")]
    (is (= 2 (:citations (result nb "ingress" cited))))
    (k/retire! nb retired {:base (:revision (k/entry nb retired)) :reason "written twice" :actor "claude"})
    (link! nb new "supersedes" old)
    (is (zero? (:citations (result nb "ingress" cited))))))

(deftest citations-are-counted-across-the-whole-notebook-not-only-the-results
  (let [nb (notebook)
        cited (write! nb "Ingress timeouts" "")
        _ (write! nb "Unrelated" (str "see [[" (short-id cited) "]]"))]
    (is (= 1 (:citations (result nb "ingress" cited))))))

(deftest a-heavily-cited-weak-match-does-not-bury-the-exact-match
  (let [nb (notebook)
        exact (at "2026-10-01T00:00:00Z" (write! nb "Ingress timeouts" ""))
        labelled (at "2026-09-01T00:00:00Z"
                     (:entry (k/write! nb {:title "Proxy timeouts" :body "" :actor "claude"
                                           :labels {"kind" "fact" "system" "ingress"}})))
        weak (at "2026-11-01T00:00:00Z" (write! nb "Proxy notes" "the ingress restarts"))]
    (dotimes [i 20]
      (write! nb (str "Citer " i) (str "see [[" (short-id weak) "]]")))
    (is (= 20 (:citations (result nb "ingress" weak))))
    (is (= [exact labelled weak] (take 3 (mapv :entry (k/search nb "ingress")))))))

(deftest an-unknown-reference-counts-for-nothing
  (let [nb (notebook)
        cited (write! nb "Ingress timeouts" "")]
    (write! nb "Gateway" "see [[ffffffff]] and [[0000]]")
    (is (zero? (:citations (result nb "ingress" cited))))))

(deftest a-search-without-text-carries-no-citations
  (let [nb (notebook)
        _ (write! nb "Ingress timeouts" "")]
    (is (every? #(not (contains? % :citations)) (k/search nb "label:kind")))))
