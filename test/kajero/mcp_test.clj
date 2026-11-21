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
