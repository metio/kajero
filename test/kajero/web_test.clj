;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.web-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kajero.core :as k]
            [kajero.web :as web])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- notebook []
  (k/notebook {:path (str (Files/createTempDirectory "kajero" (make-array FileAttribute 0)) "/notes.db")}))

(def ^:private writer {:actor "seb"})

(defn- get-page [nb path & [query]]
  (web/respond nb writer "t0ken" {:method "GET" :path path :query query}))

(defn- post [nb path form & [cross-site?]]
  (web/respond nb writer "t0ken" {:method "POST" :path path :form form :cross-site? cross-site?}))

(deftest every-page-renders
  (let [nb (notebook)
        {:keys [entry]} (k/write! nb {:title "CoreDNS caches for 30s" :body "| ttl | 30 |\n|---|---|"
                                      :labels {"kind" "fact"} :actor "claude"})]
    (k/save-page! nb {:name "dns" :selector "label:kind=fact" :group-by "kind" :actor "seb"})
    (doseq [[path query] [["/"] ["/view" "selector=label%3Akind%3Dfact&group-by=kind"] ["/labels"]
                          ["/new"] [(str "/e/" entry)] ["/p/dns"]]]
      (let [{:keys [status body]} (get-page nb path query)]
        (is (= 200 status) path)
        (when-not (#{"/labels" "/new"} path)
          (is (str/includes? body "CoreDNS caches") path))))
    (is (str/includes? (:body (get-page nb (str "/e/" entry))) "<table>") "Markdown tables render")
    (is (nil? (get-page nb "/nowhere")))))

(deftest markdown-can-not-inject-markup
  (let [nb (notebook)
        {:keys [entry]} (k/write! nb {:title "<script>alert(1)</script>" :body "<img src=x onerror=alert(1)>\n\n[x](javascript:alert(1))"
                                      :labels {"kind" "fact"} :actor "claude"})
        body (:body (get-page nb (str "/e/" entry)))]
    (is (not (str/includes? body "<script>alert")))
    (is (not (str/includes? body "<img src=x")))
    (is (not (str/includes? body "href=\"javascript:")))))

(deftest forms-write-as-the-local-human
  (let [nb (notebook)
        {:keys [status headers]} (post nb "/new" {"token" "t0ken" "title" "Velero runs at 02:00"
                                                  "labels" "kind=fact\nsystem=velero" "evidence" "kubectl get schedules"})
        id (subs (get headers "Location") 3)]
    (is (= 303 status))
    (is (= "seb" (:actor (k/entry nb id))))
    (is (= {"kind" "fact" "system" "velero"} (:labels (k/entry nb id))))
    (post nb (str "/e/" id "/observe") {"token" "t0ken" "outcome" "held" "evidence" "checked"
                                        "revision" (:revision (k/entry nb id))})
    (is (= 1 (get-in (k/entry nb id) [:standing :held])))
    (testing "a revision from an outdated form is refused"
      (let [old (:revision (k/entry nb id))]
        (post nb (str "/e/" id "/revise") {"token" "t0ken" "base" old "body" "first"})
        (is (= 400 (:status (post nb (str "/e/" id "/revise") {"token" "t0ken" "base" old "body" "second"}))))))))

(deftest a-foreign-form-can-not-write
  (let [nb (notebook)]
    (is (= 400 (:status (post nb "/new" {"token" "guess" "title" "x" "labels" "kind=fact"}))))
    (is (= 403 (:status (post nb "/new" {"token" "t0ken" "title" "x" "labels" "kind=fact"} true))))
    (is (empty? (k/search nb "")))))

(deftest data-renders-as-a-sortable-table-in-schema-order
  (let [nb (notebook)
        {schema :entry} (k/write! nb {:title "Nodes" :labels {"kind" "schema"}
                                      :data [:vector [:map [:name :string] [:cpus :int]]] :actor "claude"})
        {:keys [entry]} (k/write! nb {:title "Cluster nodes" :labels {"kind" "fact" "schema" schema}
                                      :data [{:cpus 8 :name "w-1"}] :actor "claude"})
        body (:body (get-page nb (str "/e/" entry)))]
    (is (str/includes? body "<table class=\"grid-data\">"))
    (is (re-find #"<th>name</th><th>cpus</th>" body) "columns follow the schema")
    (is (str/includes? (:body (get-page nb (str "/e/" schema))) "malli schema"))))

(deftest tools-read-the-same-data-as-edn
  (let [nb (notebook)
        {:keys [entry]} (k/write! nb {:title "Cluster nodes" :labels {"kind" "fact"} :data [{:name "w-1"}] :actor "claude"})
        read (fn [path & [query]] (get-page nb path query))
        one (read (str "/e/" entry ".edn"))]
    (is (= "application/edn; charset=utf-8" (get-in one [:headers "Content-Type"])))
    (is (= [{:name "w-1"}] (:data (edn/read-string (:body one)))))
    (is (= [entry] (map :entry (edn/read-string (:body (read "/view.edn" "selector=label%3Akind%3Dfact"))))))
    (is (= {"kind" {"fact" 1}} (edn/read-string (:body (read "/labels.edn")))))
    (is (= 1 (count (edn/read-string (:body (read (str "/e/" entry "/history.edn")))))))
    (is (nil? (read "/e/no-such-entry.edn")))
    (is (= 400 (:status (read "/view.edn" "selector=bogus%3D"))))))

(def ^:private mcp-config {:token "s3cret" :writer {:actor "claude"} :port 7270})

(defn- mcp-call [nb request]
  (web/mcp-endpoint nb mcp-config (merge {:method "POST" :authorization "Bearer s3cret"} request)))

(deftest mcp-over-http-writes-as-the-mcp-writer
  (let [nb (notebook)
        init (mcp-call nb {:body "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}"})
        write (mcp-call nb {:body (str "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                                       "{\"name\":\"kajero_write\",\"arguments\":{\"title\":\"t\",\"labels\":{\"kind\":\"fact\"}}}}")})]
    (is (= 200 (:status init)))
    (is (str/includes? (:body init) "2025-06-18") "answers in the client's revision")
    (is (= 200 (:status write)))
    (is (= ["claude"] (map #(:actor (k/entry nb (:entry %))) (k/search nb "label:kind=fact")))
        "the pages' writer is seb; MCP writes are claude's")
    (is (= 202 (:status (mcp-call nb {:body "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"}))))))

(deftest mcp-over-http-is-guarded
  (let [nb (notebook)
        body "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"]
    (is (= 401 (:status (mcp-call nb {:body body :authorization nil}))))
    (is (= 401 (:status (mcp-call nb {:body body :authorization "Bearer wrong"}))))
    (is (= 403 (:status (mcp-call nb {:body body :origin "http://evil.example"}))))
    (is (= 200 (:status (mcp-call nb {:body body :origin "http://127.0.0.1:7270"}))))
    (is (= 405 (:status (mcp-call nb {:method "GET"}))))
    (is (nil? (web/mcp-endpoint nb {:writer {:actor "claude"} :port 7270} {:method "POST" :body body}))
        "without a token there is no MCP endpoint")))

(deftest surfaces-are-chosen-explicitly-or-follow-the-token
  (is (= #{"http"} (web/surfaces nil false)))
  (is (= #{"http" "mcp"} (web/surfaces nil true)))
  (is (= #{"mcp"} (web/surfaces "mcp" true)))
  (is (= #{"http"} (web/surfaces "http" true)))
  (is (thrown? clojure.lang.ExceptionInfo (web/surfaces "mcp" false)))
  (is (thrown? clojure.lang.ExceptionInfo (web/surfaces "web" true))))

(deftest schema-pages-show-their-users-and-migrations-their-schemas
  (let [nb (notebook)
        {s1 :entry s1-rev :revision} (k/write! nb {:title "Nodes v1" :labels {"kind" "schema"}
                                                   :data [:vector [:map [:name :string]]] :actor "claude"})
        {s2 :entry} (k/write! nb {:title "Nodes v2" :labels {"kind" "schema"}
                                  :data [:vector [:map [:host :string]]] :actor "claude"})
        {e :entry} (k/write! nb {:title "staging nodes" :labels {"kind" "fact" "schema" s1}
                                 :data [{:name "w-1"}] :actor "claude"})
        {m :entry} (k/write! nb {:title "v1 to v2" :labels {"kind" "migration"}
                                 :data {:from s1 :to s2 :steps [[:rename :name :host]]} :actor "claude"})]
    (is (str/includes? (:body (get-page nb (str "/e/" s1))) "used by 1 entry"))
    (is (str/includes? (:body (get-page nb (str "/e/" m))) "migration from"))
    (is (str/includes? (:body (get-page nb (str "/e/" m))) "Nodes v2"))
    (k/revise! nb s1 {:base s1-rev :data [:vector [:map [:name :string] [:zone {:optional true} :string]]] :actor "claude"})
    (is (str/includes? (:body (get-page nb (str "/e/" e))) "in an older revision"))
    (is (str/includes? (:body (get-page nb (str "/e/" s1))) "(older revision)"))))

(deftest requests-must-name-this-server
  (let [ok {:host "127.0.0.1:7270" :method "GET" :path "/"}]
    (is (nil? (web/request-guard 7270 ok)))
    (is (nil? (web/request-guard 7270 (assoc ok :host "localhost:7270"))))
    (is (= 421 (:status (web/request-guard 7270 (assoc ok :host "rebound.example:7270"))))
        "a rebinding page sends its own host name")
    (is (= 421 (:status (web/request-guard 7270 (dissoc ok :host)))))
    (is (= 421 (:status (web/request-guard 7270 (assoc ok :host "127.0.0.1:7271")))))))

(deftest forms-must-come-from-this-servers-pages
  (let [post {:host "127.0.0.1:7270" :method "POST" :path "/new"}]
    (is (nil? (web/request-guard 7270 (assoc post :origin "http://127.0.0.1:7270"))))
    (is (= 403 (:status (web/request-guard 7270 post))) "a POST without an Origin is no form of ours")
    (is (= 403 (:status (web/request-guard 7270 (assoc post :origin "http://evil.example")))))
    (is (nil? (web/request-guard 7270 (assoc post :path "/mcp"))) "MCP clients authenticate with their token")))

(deftest an-oversized-body-is-refused
  (is (= 413 (:status (web/request-guard 7270 {:host "127.0.0.1:7270" :method "POST" :path "/mcp" :too-large? true})))))

(deftest a-short-mcp-token-is-refused
  (is (= (str/join (repeat 32 "a")) (web/mcp-token (str (str/join (repeat 32 "a")) "\n"))))
  (is (thrown? clojure.lang.ExceptionInfo (web/mcp-token "")))
  (is (thrown? clojure.lang.ExceptionInfo (web/mcp-token "short"))))
