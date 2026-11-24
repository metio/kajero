;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.data-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.data :as data]
            [kajero.test-temp :as temp]))

(use-fixtures :each temp/each)

(defn- notebook []
  (k/notebook {:dir (str (temp/temp-dir) "/notes")}))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(def ^:private node-schema
  [:vector [:map [:name :string] [:cpus :int] [:role [:enum "control-plane" "worker"]]]])

(def ^:private nodes
  [{:name "w-1" :cpus 8 :role "worker"} {:name "cp-1" :cpus 4 :role "control-plane"}])

(deftest a-schema-entry-holds-a-malli-schema
  (let [nb (notebook)]
    (is (string? (:entry (k/write! nb {:title "Nodes" :labels {"kind" "schema"} :data node-schema :actor "claude"}))))
    (is (= :kajero/bad-schema
           (refusal #(k/write! nb {:title "Broken" :labels {"kind" "schema"} :data [:no-such-type] :actor "claude"}))))))

(deftest a-schema-runs-no-code
  (is (= :kajero/bad-schema (refusal #(data/schema [:fn '(fn [_] (System/exit 1))])))))

(deftest data-is-checked-against-the-schema-its-label-names
  (let [nb (notebook)
        {schema :entry schema-rev :revision} (k/write! nb {:title "Nodes" :labels {"kind" "schema"} :data node-schema :actor "claude"})
        {:keys [entry revision]} (k/write! nb {:title "Cluster nodes" :labels {"kind" "fact" "schema" schema}
                                               :data nodes :actor "claude"})]
    (is (= nodes (:data (k/entry nb entry))))
    (is (= {:entry schema :revision schema-rev} (:schema (k/entry nb entry))) "the revision records which schema revision it fit")
    (testing "data that does not fit is refused with malli's errors"
      (is (= :kajero/bad-data
             (refusal #(k/revise! nb entry {:base revision :data [{:name "w-2" :cpus "eight" :role "worker"}] :actor "claude"})))))
    (testing "a revision that leaves the data out keeps it"
      (k/revise! nb entry {:base revision :body "measured with kubectl" :actor "claude"})
      (is (= nodes (:data (k/entry nb entry)))))
    (is (= :kajero/no-schema
           (refusal #(k/write! nb {:title "x" :labels {"kind" "fact" "schema" entry} :data nodes :actor "claude"}))))))

(deftest data-is-searchable
  (let [nb (notebook)
        {:keys [entry]} (k/write! nb {:title "Cluster nodes" :labels {"kind" "fact"} :data nodes :actor "claude"})]
    (is (= [entry] (map :entry (k/search nb "~cp-1"))))))

(deftest table-columns-follow-the-schema
  (is (= [:name :cpus :role :zone] (data/columns (conj nodes {:zone "a" :name "w-3"}) node-schema)))
  (is (= [:b :a] (data/columns [{:b 1} {:a 2}] nil)))
  (is (data/table? nodes))
  (is (not (data/table? {:a 1}))))
