;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.mcp-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.mcp :as mcp]
            [kajero.skill-files :as files]
            [kajero.skills :as skills]
            [kajero.test-keys :as tk]
            [kajero.test-temp :as temp]
            [kajero.web :as web])
  (:import (java.net URLClassLoader)))

(use-fixtures :each temp/each)

(defn- notebook []
  (k/notebook {:path (str (temp/temp-dir) "/notes.db")}))

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

(deftest several-entries-are-observed-in-one-call-over-mcp
  (let [nb (notebook)
        ids (vec (for [t ["one" "two" "three"]]
                   (get-in (call nb "kajero_write" {:title t :labels {"kind" "fact"}}) [:value "entry"])))
        {:keys [error? value]} (call nb "kajero_observe_many"
                                     {:observations [{:entry (subs (ids 0) 0 8) :outcome "held" :evidence ["book"]}
                                                     {:entry (ids 1) :outcome "held"}
                                                     {:entry (ids 2) :outcome "failed"}]})]
    (is (false? error?))
    (is (= ids (map #(get % "entry") value)))
    (is (= [1 1 nil] (map #(get-in % ["standing" "held"]) value)))
    (is (= "no-evidence" (get (nth value 2) "refused")))
    (testing "an observation naming a parameter it does not take is refused alone"
      (let [{[r] :value} (call nb "kajero_observe_many" {:observations [{:entry (ids 0) :outcom "held"}]})]
        (is (= "unknown-parameter" (get r "refused")))
        (is (re-find #"outcom" (get r "message")))))))

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

(deftest a-missed-search-is-observed-over-mcp
  (let [nb (notebook)
        entry (get-in (call nb "kajero_write" {:title "45.92.49.20 is our own HAProxy" :labels {"kind" "fact"}}) [:value "entry"])
        other (get-in (call nb "kajero_write" {:title "CoreDNS caches" :labels {"kind" "fact"}}) [:value "entry"])]
    (testing "missed needs the selector"
      (let [{:keys [error? value]} (call nb "kajero_observe" {:entry entry :outcome "missed"})]
        (is (true? error?))
        (is (= "no-selector" (get value "refused")))))
    (is (false? (:error? (call nb "kajero_observe" {:entry entry :outcome "missed" :selector "etherpad lernraum"}))))
    (let [{[r] :value} (call nb "kajero_observe_many" {:observations [{:entry other :outcome "missed" :selector "~wildcard"}]})]
      (is (string? (get r "observation"))))
    (let [got (:value (call nb "kajero_get" {:entry entry}))]
      (is (zero? (get-in got ["standing" "held"])))
      (is (false? (get-in got ["standing" "disputed?"])))
      (is (= ["etherpad lernraum"] (map #(get % "selector") (get got "missed")))))
    (is (= #{entry other} (set (map #(get % "entry") (:value (call nb "kajero_search" {:selector "missed"}))))))))

;; ------------------------------------------------------------ skills

(defn- staged-catalog
  "The catalog a build stages, read through a class loader as the jar is."
  []
  (let [classes (temp/temp-dir)]
    (files/stage! "skills" classes)
    (skills/from-classpath (URLClassLoader. (into-array java.net.URL [(.toURL (.toURI (io/file classes)))]) nil))))

(defn- http-transport
  "A JSON-RPC call through the streamable HTTP endpoint on /mcp."
  [nb writer]
  (fn [msg]
    (let [{:keys [status body]} (web/mcp-endpoint nb {:token "s3cret" :writer writer :port 7270}
                                                  {:method "POST" :authorization "Bearer s3cret"
                                                   :body (json/generate-string msg)})]
      (when (= 200 status) (json/parse-string body true)))))

(defn- keywordized [response]
  (json/parse-string (json/generate-string response) true))

(defn- with-actors-for-manifests
  "A key for claude and an actors file admitting it for manifests, or nil
   without ssh-keygen."
  []
  (when-let [{:keys [key actors]} (tk/keypair "claude")]
    (spit actors (str/replace (slurp actors) "namespaces=\"kajero\"" "namespaces=\"kajero,kajero-skill-manifest\""))
    {:key key :actors actors}))

(defn- check-skills-protocol
  [rpc actors]
  (let [call (fn [id method & [params]]
               (keywordized (rpc (cond-> {:jsonrpc "2.0" :id id :method method} params (assoc :params params)))))
        init (:result (call 1 "initialize" {:protocolVersion "2025-06-18" :capabilities {}}))]
    (testing "initialize declares resources and the extension next to them, and names the SKILL.md URI"
      (is (= {} (get-in init [:capabilities :resources])))
      (is (= {} (get-in init [:capabilities :extensions (keyword skills/extension-id)])))
      (is (str/includes? (:instructions init) "skill://kajero/SKILL.md")))
    (let [listed (:result (call 2 "skills/list" {}))
          [entry & more] (:skills listed)]
      (testing "skills/list"
        (is (= "complete" (:resultType listed)))
        (is (pos-int? (:ttlMs listed)))
        (is (= "public" (:cacheScope listed)))
        (is (empty? more))
        (is (= "skill://kajero/SKILL.md" (:uri entry)))
        (is (= "kajero" (get-in entry [:frontmatter :name])))
        (is (seq (get-in entry [:frontmatter :description])))
        (is (some #(= "skill://kajero/SKILL.md" (:uri %)) (:resources entry))))
      (testing "skills/get answers the same entry"
        (let [got (:result (call 3 "skills/get" {:uri "skill://kajero/SKILL.md"}))]
          (is (= "complete" (:resultType got)))
          (is (= entry (:skill got)))))
      (testing "every manifest file reads with its digest and size"
        (doseq [{:keys [uri digest size]} (:resources entry)
                :let [read (:result (call 4 "resources/read" {:uri uri}))
                      [{:keys [text mimeType] :as c}] (:contents read)
                      b (.getBytes ^String text "UTF-8")]]
          (is (= "complete" (:resultType read)))
          (is (= uri (:uri c)))
          (is (= "text/markdown" mimeType))
          (is (= size (alength b)))
          (is (= digest (skills/digest b)))))
      (testing "the manifest signature verifies against the actors file"
        (if actors
          (is (= {:verified? true :actor "claude"} (skills/verify-entry actors entry)))
          (is (false? (get-in entry [:_meta (keyword skills/meta-key) :signed]))))))
    (testing "an unknown skill or file is invalid params"
      (doseq [[method uri] [["skills/get" "skill://unknown/SKILL.md"]
                            ["skills/get" "skill://kajero/missing.md"]
                            ["resources/read" "skill://kajero/missing.md"]
                            ["resources/read" "skill://kajero/../kajero/SKILL.md"]
                            ["resources/read" "skill://kajero/%2e%2e/SKILL.md"]
                            ["resources/read" "skill://kajero/..%2fSKILL.md"]
                            ["resources/read" "file:///etc/passwd"]]]
        (is (= -32602 (get-in (call 5 method {:uri uri}) [:error :code])) (str method " " uri))))))

(deftest skills-are-served-over-stdio
  (let [nb (notebook)
        catalog (staged-catalog)
        {:keys [key actors]} (with-actors-for-manifests)
        writer {:actor "claude" :key key}]
    (check-skills-protocol (fn [msg] (mcp/handle nb writer nil (json/generate-string msg) {:skills catalog}))
                           actors)))

(deftest skills-are-served-over-http
  (let [nb (notebook)
        catalog (staged-catalog)
        {:keys [key actors]} (with-actors-for-manifests)]
    (with-redefs [skills/served (delay catalog)]
      (check-skills-protocol (http-transport nb {:actor "claude" :key key}) actors))))

(deftest a-server-without-a-key-marks-its-manifest-unsigned
  (let [nb (notebook)
        catalog (staged-catalog)]
    (doseq [rpc [(fn [msg] (mcp/handle nb {:actor "claude"} nil (json/generate-string msg) {:skills catalog}))
                 (fn [msg] (with-redefs [skills/served (delay catalog)] ((http-transport nb {:actor "claude"}) msg)))]]
      (let [m (get-in (keywordized (rpc {:jsonrpc "2.0" :id 1 :method "skills/list"}))
                      [:result :skills 0 :_meta (keyword skills/meta-key)])]
        (is (= {:format "skill-manifest-v1" :signed false :reason "the server has no signing key"} m))))))

(deftest a-client-without-the-extension-reads-the-skill-as-a-resource
  (let [nb (notebook)
        catalog (staged-catalog)
        call (fn [msg] (keywordized (mcp/handle nb {:actor "claude"} nil (json/generate-string msg) {:skills catalog})))
        listed (get-in (call {:jsonrpc "2.0" :id 1 :method "resources/list"}) [:result :resources])
        skill-md (some #(when (= "skill://kajero/SKILL.md" (:uri %)) %) listed)]
    (is (= "text/markdown" (:mimeType skill-md)))
    (is (= (alength (java.nio.file.Files/readAllBytes (.toPath (io/file "skills/kajero/SKILL.md")))) (:size skill-md)))
    (is (= (slurp "skills/kajero/SKILL.md")
           (get-in (call {:jsonrpc "2.0" :id 2 :method "resources/read" :params {:uri "skill://kajero/SKILL.md"}})
                   [:result :contents 0 :text])))
    (is (= [] (get-in (call {:jsonrpc "2.0" :id 3 :method "resources/templates/list"}) [:result :resourceTemplates])))
    (testing "the tools work as before"
      (is (= (count mcp/tools) (count (get-in (call {:jsonrpc "2.0" :id 4 :method "tools/list"}) [:result :tools]))))
      (is (false? (get-in (call {:jsonrpc "2.0" :id 5 :method "tools/call"
                                 :params {:name "kajero_labels" :arguments {}}})
                          [:result :isError]))))))

(deftest a-build-without-skills-declares-no-extension
  (let [nb (notebook)
        call (fn [msg] (keywordized (mcp/handle nb {} nil (json/generate-string msg) {:skills nil})))
        init (:result (call {:jsonrpc "2.0" :id 1 :method "initialize" :params {:protocolVersion "2025-06-18"}}))]
    (is (= {:tools {}} (:capabilities init)))
    (is (nil? (:instructions init)))
    (is (= -32601 (get-in (call {:jsonrpc "2.0" :id 2 :method "skills/list"}) [:error :code])))
    (is (= [] (get-in (call {:jsonrpc "2.0" :id 3 :method "resources/list"}) [:result :resources])))
    (is (= -32602 (get-in (call {:jsonrpc "2.0" :id 4 :method "resources/read" :params {:uri "skill://kajero/SKILL.md"}})
                          [:error :code])))))
