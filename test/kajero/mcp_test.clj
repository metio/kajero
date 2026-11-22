;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.mcp-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [kajero.core :as k]
            [kajero.mcp :as mcp])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- notebook []
  (k/notebook {:path (str (Files/createTempDirectory "kajero" (make-array FileAttribute 0)) "/notes.db")}))

(defn- call
  [nb tool args]
  (let [response (mcp/handle nb {:actor "claude"}
                             (json/generate-string {:jsonrpc "2.0" :id 1 :method "tools/call"
                                                    :params {:name tool :arguments args}}))
        result (:result response)]
    {:error? (:isError result)
     :value (json/parse-string (get-in result [:content 0 :text]))}))

(deftest the-server-introduces-itself-and-lists-its-tools
  (let [nb (notebook)]
    (is (= "kajero" (get-in (mcp/handle nb {} "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")
                            [:result :serverInfo :name])))
    (is (= (count mcp/tools)
           (count (get-in (mcp/handle nb {} "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                          [:result :tools]))))
    (is (nil? (mcp/handle nb {} "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")))))

(deftest an-agent-writes-observes-and-finds-an-entry
  (let [nb (notebook)
        {{:strs [entry revision]} :value} (call nb "kajero_write" {:title "CoreDNS caches for 30s"
                                                                   :labels {"kind" "fact" "system" "dns"}
                                                                   :evidence ["kubectl get cm coredns"]})]
    (is (string? entry))
    (is (false? (:error? (call nb "kajero_observe" {:entry entry :outcome "held" :evidence ["dig twice"]}))))
    (is (= [entry] (map #(get % "entry") (:value (call nb "kajero_search" {:selector "label:system=dns"})))))
    (is (= "claude" (get (:value (call nb "kajero_get" {:entry entry})) "actor")))
    (testing "a stale base is refused with the current revision"
      (call nb "kajero_revise" {:entry entry :base revision :body "60s since the upgrade"})
      (let [{:keys [error? value]} (call nb "kajero_revise" {:entry entry :base revision :body "lost"})]
        (is (true? error?))
        (is (= "stale-base" (get value "refused")))
        (is (string? (get value "current")))))))

(deftest a-broken-line-answers-with-an-error-and-the-session-goes-on
  (is (= -32700 (get-in (mcp/handle (notebook) {} "not json") [:error :code]))))

(deftest data-travels-as-edn
  (let [nb (notebook)
        {{:strs [entry]} :value} (call nb "kajero_write" {:title "Nodes" :labels {"kind" "fact"}
                                                          :data_edn "[{:name \"w-1\" :cpus 8}]"})]
    (is (= "[{:name \"w-1\", :cpus 8}]" (get (:value (call nb "kajero_get" {:entry entry})) "data_edn")))
    (is (= "bad-edn" (get-in (call nb "kajero_write" {:title "x" :labels {"kind" "fact"} :data_edn "[unclosed"})
                             [:value "refused"])))))

(deftest migrations-run-over-mcp
  (let [nb (notebook)
        write (fn [args] (get-in (call nb "kajero_write" args) [:value "entry"]))
        s1 (write {:title "v1" :labels {"kind" "schema"} :data_edn "[:vector [:map [:name :string]]]"})
        s2 (write {:title "v2" :labels {"kind" "schema"} :data_edn "[:vector [:map [:host :string]]]"})
        e (write {:title "nodes" :labels {"kind" "fact" "schema" s1} :data_edn "[{:name \"w-1\"}]"})
        m (write {:title "v1 to v2" :labels {"kind" "migration"}
                  :data_edn (pr-str {:from s1 :to s2 :steps [[:rename :name :host]]})})]
    (is (= [e] (map #(get % "entry") (get-in (call nb "kajero_get" {:entry s1}) [:value "used_by"]))))
    (is (= "[{:host \"w-1\"}]" (do (call nb "kajero_migrate" {:migration m})
                                   (get-in (call nb "kajero_get" {:entry e}) [:value "data_edn"]))))))

(deftest an-unambiguous-id-prefix-reads-the-entry
  (let [nb (notebook)
        {{:strs [entry revision]} :value} (call nb "kajero_write" {:title "Short ids" :labels {"kind" "fact"}})
        short (subs entry 0 8)]
    (testing "every tool that takes an entry resolves the eight characters prose quotes"
      (is (= entry (get (:value (call nb "kajero_get" {:entry short})) "entry")))
      (is (seq (:value (call nb "kajero_history" {:entry short}))))
      (is (false? (:error? (call nb "kajero_revise" {:entry short :base revision :body "b"}))))
      (is (false? (:error? (call nb "kajero_observe" {:entry short :outcome "held"})))))
    (testing "a prefix below four characters is not an id"
      (is (= "no-entry" (get-in (call nb "kajero_get" {:entry (subs entry 0 3)}) [:value "refused"]))))))

(deftest an-id-resolves-among-the-entries
  (let [ids ["e8da4903-1111-4000-8000-000000000000"
             "e8da4903-2222-4000-8000-000000000000"
             "f0ff7bc3-54d4-4a0e-a7bc-466b6a7b141e"]]
    (is (= (nth ids 2) (k/resolve-among ids "f0ff7bc3")) "a unique prefix")
    (is (= (nth ids 2) (k/resolve-among ids (nth ids 2))) "the full id")
    (is (nil? (k/resolve-among ids "0123abcd")) "a prefix nobody has")
    (is (nil? (k/resolve-among ids "f0f")) "fewer than four characters")
    (let [e (try (k/resolve-among ids "e8da4903") (catch clojure.lang.ExceptionInfo e e))]
      (is (= :kajero/ambiguous-id (:reason (ex-data e))))
      (is (= (set (take 2 ids)) (set (:candidates (ex-data e)))))
      (is (re-find #"e8da4903-1111" (ex-message e))))))

(deftest a-refused-call-names-the-parameter
  (let [nb (notebook)
        a (get-in (call nb "kajero_write" {:title "a" :labels {"kind" "fact"}}) [:value "entry"])
        b (get-in (call nb "kajero_write" {:title "b" :labels {"kind" "fact"}}) [:value "entry"])]
    (testing "an unexpected parameter is named, with the ones the tool takes"
      (let [{:keys [error? value]} (call nb "kajero_link" {:source a :kind "see-also" :target b})]
        (is error?)
        (is (= "unknown-parameter" (get value "refused")))
        (is (re-find #"\bkind\b" (get value "message")))
        (is (re-find #"source, type, target" (get value "message")))))
    (testing "a missing required parameter is named"
      (let [{:keys [error? value]} (call nb "kajero_link" {:source a :target b})]
        (is error?)
        (is (= "missing-parameter" (get value "refused")))
        (is (re-find #"\btype\b" (get value "message")))))
    (testing "a well-formed call still links"
      (is (false? (:error? (call nb "kajero_link" {:source a :type "see-also" :target b})))))))

(deftest an-observation-is-corrected-over-mcp
  (let [nb (notebook)
        entry (get-in (call nb "kajero_write" {:title "Issuer" :labels {"kind" "fact"}}) [:value "entry"])
        wrong (get-in (call nb "kajero_observe" {:entry entry :outcome "held" :evidence ["ConfigMap missing"]})
                      [:value "observation"])
        {:keys [error?]} (call nb "kajero_observe" {:entry entry :outcome "withdrawn" :corrects wrong
                                                    :evidence ["the cause was environment=prod"]})
        got (:value (call nb "kajero_get" {:entry entry}))]
    (is (false? error?))
    (is (zero? (get-in got ["standing" "held"])))
    (is (= [[wrong "withdrawn"]] (map (juxt #(get % "observation") #(get % "correction")) (get got "corrections"))))))
