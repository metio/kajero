;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.web-test
  (:require [clojure.string :as str]
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
