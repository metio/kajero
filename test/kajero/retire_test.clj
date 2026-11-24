;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.retire-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.mcp :as mcp]
            [kajero.test-temp :as temp]))

(def ^:private ^:dynamic *backend* :sqlite)

(use-fixtures :each temp/each (fn [run-test] (doseq [backend [:sqlite :files]]
                                               (binding [*backend* backend]
                                                 (testing (name backend) (run-test))))))

(defn- notebook []
  (k/notebook (if (= :files *backend*)
                {:dir (str (temp/temp-dir) "/notes")}
                {:path (str (temp/temp-dir) "/notes.db")})))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(def ^:private probe
  {:title "probe write, please ignore" :body "testing the write path"
   :labels {"kind" "fact" "system" "scratch"} :actor "claude"})

(def ^:private keeper
  {:title "Calico v3 is served by CRDs" :body "Read the SERVICE column."
   :labels {"kind" "gotcha" "system" "calico"} :actor "claude"})

(defn- ids [results] (set (map :entry results)))

(deftest a-retired-entry-leaves-search-but-stays-readable
  (let [nb (notebook)
        {p :entry rev :revision} (k/write! nb probe)
        {kp :entry} (k/write! nb keeper)
        r (k/retire! nb p {:base rev :reason "a probe of the write path" :actor "claude"})]
    (is (= #{kp} (ids (k/search nb ""))))
    (is (empty? (ids (k/search nb "~probe"))) "a text match does not bring it back")
    (is (= #{kp} (ids (k/search nb "label:kind"))) "nor does a label every entry carries")
    (is (= #{p} (ids (k/search nb "retired"))))
    (is (= #{p} (ids (k/search nb "retired ~probe"))))
    (is (= #{kp} (ids (k/search nb "not retired"))))
    (let [e (k/entry nb p)]
      (is (= {:reason "a probe of the write path"} (:retired e)))
      (is (= "probe write, please ignore" (:title e)) "content unchanged")
      (is (= (:labels probe) (:labels e)))
      (is (= (:revision r) (:revision e))))
    (is (not (contains? (k/entry nb kp) :retired)) "an entry that is not retired carries no key")
    (is (= {:reason "a probe of the write path"} (:retired (first (k/search nb "retired"))))
        "a search that asks for retired entries says why")
    (is (not (contains? (first (k/search nb "")) :retired)))))

(deftest retiring-is-a-revision-in-the-history
  (let [nb (notebook)
        {p :entry rev :revision} (k/write! nb probe)
        _ (k/retire! nb p {:base rev :reason "a probe of the write path" :actor "seb"
                           :evidence ["tik:abc"]})
        [first-rev retirement] (k/history nb p)]
    (is (= 2 (count (k/history nb p))))
    (is (= rev (:hash first-rev)))
    (is (= rev (:base retirement)) "the retirement sits on the revision it retires")
    (is (= 2 (:seq retirement)))
    (is (= "seb" (:actor retirement)))
    (is (= ["tik:abc"] (:evidence retirement)))
    (is (= {:reason "a probe of the write path"} (:retired retirement)))))

(deftest a-retirement-needs-a-reason-the-current-base-and-an-actor
  (let [nb (notebook)
        {p :entry rev :revision} (k/write! nb probe)]
    (is (= :kajero/no-reason (refusal #(k/retire! nb p {:base rev :reason "  " :actor "claude"}))))
    (is (= :kajero/no-reason (refusal #(k/retire! nb p {:base rev :actor "claude"}))))
    (is (= :kajero/stale-base (refusal #(k/retire! nb p {:base "sha256-0" :reason "why not" :actor "claude"}))))
    (is (= :kajero/no-actor (refusal #(k/retire! nb p {:base rev :reason "why not"}))))
    (is (= :kajero/no-entry (refusal #(k/retire! nb "no-such" {:base rev :reason "why not" :actor "claude"}))))
    (is (= #{p} (ids (k/search nb ""))) "every refusal left the entry live")))

(deftest an-entry-retires-once
  (let [nb (notebook)
        {p :entry rev :revision} (k/write! nb probe)
        {rev2 :revision} (k/retire! nb p {:base rev :reason "a probe" :actor "claude"})]
    (is (= :kajero/retired (refusal #(k/retire! nb p {:base rev2 :reason "again" :actor "claude"}))))))

(deftest restoring-lifts-the-retirement-and-keeps-the-content
  (let [nb (notebook)
        {p :entry rev :revision} (k/write! nb probe)
        {rev2 :revision} (k/retire! nb p {:base rev :reason "a probe" :actor "claude"})
        {rev3 :revision} (k/restore! nb p {:base rev2 :actor "seb"})]
    (is (= #{p} (ids (k/search nb ""))))
    (is (empty? (ids (k/search nb "retired"))))
    (is (not (contains? (k/entry nb p) :retired)))
    (is (= rev3 (:revision (k/entry nb p))))
    (is (= "testing the write path" (:body (k/entry nb p))))
    (is (= 3 (count (k/history nb p))))
    (testing "and the entry can retire again"
      (k/retire! nb p {:base rev3 :reason "a probe after all" :actor "claude"})
      (is (empty? (ids (k/search nb "")))))))

(deftest only-a-retired-entry-restores
  (let [nb (notebook)
        {p :entry rev :revision} (k/write! nb probe)
        {rev2 :revision} (k/retire! nb p {:base rev :reason "a probe" :actor "claude"})]
    (is (= :kajero/not-retired (refusal #(k/restore! nb (:entry (k/write! nb keeper))
                                                     {:base rev :actor "claude"}))))
    (is (= :kajero/stale-base (refusal #(k/restore! nb p {:base rev :actor "claude"}))))
    (is (= :kajero/no-entry (refusal #(k/restore! nb "no-such" {:base rev2 :actor "claude"}))))))

(deftest a-revision-keeps-the-entry-retired
  (let [nb (notebook)
        {p :entry rev :revision} (k/write! nb probe)
        {rev2 :revision} (k/retire! nb p {:base rev :reason "a probe" :actor "claude"})]
    (k/revise! nb p {:base rev2 :body "corrected while retired" :actor "claude"})
    (is (= {:reason "a probe"} (:retired (k/entry nb p))))
    (is (= "corrected while retired" (:body (k/entry nb p))))
    (is (empty? (ids (k/search nb ""))))))

(deftest a-retired-entry-leaves-the-label-counts-and-the-similar-check
  (let [nb (notebook)
        {p :entry rev :revision} (k/write! nb probe)]
    (is (= 1 (get-in (k/labels nb) ["system" "scratch"])))
    (k/retire! nb p {:base rev :reason "a probe" :actor "claude"})
    (is (nil? (get-in (k/labels nb) ["system" "scratch"])))
    (is (empty? (:similar (k/write! nb probe))) "a retired twin is not offered for revision")))

(deftest retire-and-restore-over-mcp
  (let [nb (notebook)
        {p :entry rev :revision} (k/write! nb probe)
        writer {:actor "claude"}
        {rev2 :revision} (mcp/call-tool nb writer "kajero_retire" {"entry" p "base" rev "reason" "a probe"})]
    (is (empty? (mcp/call-tool nb writer "kajero_search" {"selector" ""})))
    (is (= 1 (count (mcp/call-tool nb writer "kajero_search" {"selector" "retired"}))))
    (mcp/call-tool nb writer "kajero_restore" {"entry" p "base" rev2})
    (is (= #{p} (ids (mcp/call-tool nb writer "kajero_search" {"selector" ""}))))
    (is (some #{"kajero_retire"} (map :name mcp/tools)))
    (is (some #{"kajero_restore"} (map :name mcp/tools)))))
