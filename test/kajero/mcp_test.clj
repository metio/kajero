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
  "The skill as a client of one era sees it through `rpc`; the modern era
   starts with server/discover instead of initialize."
  [rpc actors & [era]]
  (let [call (fn [id method & [params]]
               (keywordized (rpc (cond-> {:jsonrpc "2.0" :id id :method method} params (assoc :params params)))))
        init (if (= :modern era)
               (:result (call 1 "server/discover" {}))
               (:result (call 1 "initialize" {:protocolVersion "2025-06-18" :capabilities {}})))]
    (when (= :modern era)
      (testing "server/discover names the revision and the server"
        (is (= ["2026-07-28"] (:supportedVersions init)))
        (is (= "complete" (:resultType init)))
        (is (= "kajero" (get-in init [:_meta :io.modelcontextprotocol/serverInfo :name])))))
    (testing "the server declares resources and the extension next to them, and names the SKILL.md URI"
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

;; ---------------------------------------------------- modern revisions

(def ^:private modern-meta
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientInfo" {"name" "test" "version" "1"}
   "io.modelcontextprotocol/clientCapabilities" {}})

(defn- modern-message [msg]
  (update msg :params #(assoc (or % {}) :_meta modern-meta)))

(defn- mirrored-headers
  "The headers a modern client sends for `msg`."
  [{:keys [method params]}]
  (cond-> {"mcp-protocol-version" ["2026-07-28"] "mcp-method" [method]}
    (#{"tools/call"} method) (assoc "mcp-name" [(:name params)])
    (#{"resources/read"} method) (assoc "mcp-name" [(:uri params)])))

(defn- http-response
  "The HTTP response of /mcp to `msg` with `headers`."
  [nb writer msg headers]
  (let [r (web/mcp-endpoint nb {:token "s3cret" :writer writer :port 7270}
                            {:method "POST" :authorization "Bearer s3cret" :mcp-headers headers
                             :body (json/generate-string msg)})]
    (assoc r :json (some-> (:body r) not-empty (json/parse-string true)))))

(deftest skills-are-served-over-stdio-to-a-modern-client
  (let [nb (notebook)
        catalog (staged-catalog)
        {:keys [key actors]} (with-actors-for-manifests)
        writer {:actor "claude" :key key}]
    (check-skills-protocol (fn [msg] (mcp/handle nb writer nil (json/generate-string (modern-message msg)) {:skills catalog}))
                           actors :modern)))

(deftest skills-are-served-over-http-to-a-modern-client
  (let [nb (notebook)
        catalog (staged-catalog)
        {:keys [key actors]} (with-actors-for-manifests)
        writer {:actor "claude" :key key}]
    (with-redefs [skills/served (delay catalog)]
      (check-skills-protocol (fn [msg]
                               (let [m (modern-message msg)
                                     {:keys [status json headers]} (http-response nb writer m (mirrored-headers m))]
                                 (is (nil? (get headers "Mcp-Session-Id")) "a modern request mints no session")
                                 (when (:error json)
                                   (is (= 200 status) "an invalid skill URI answers 200 with the error"))
                                 json))
                             actors :modern))))

(deftest a-modern-request-is-checked-before-it-is-answered
  (let [nb (notebook)
        catalog (staged-catalog)
        writer {:actor "claude"}
        tools-list (modern-message {:jsonrpc "2.0" :id 1 :method "tools/list"})
        read-skill (modern-message {:jsonrpc "2.0" :id 2 :method "resources/read" :params {:uri "skill://kajero/SKILL.md"}})]
    (with-redefs [skills/served (delay catalog)]
      (testing "the tools answer a modern client"
        (let [{:keys [status json]} (http-response nb writer tools-list (mirrored-headers tools-list))]
          (is (= 200 status))
          (is (= (count mcp/tools) (count (get-in json [:result :tools]))))
          (is (= "complete" (get-in json [:result :resultType])))))
      (testing "an unsupported revision is refused with the supported ones"
        (let [m (assoc-in tools-list [:params :_meta "io.modelcontextprotocol/protocolVersion"] "2099-01-01")
              {:keys [status json]} (http-response nb writer m (assoc (mirrored-headers m) "mcp-protocol-version" ["2099-01-01"]))]
          (is (= 400 status))
          (is (= -32022 (get-in json [:error :code])))
          (is (= {:supported ["2026-07-28"] :requested "2099-01-01"} (get-in json [:error :data])))))
      (testing "a request without the client's capabilities is malformed"
        (let [m (update-in tools-list [:params :_meta] dissoc "io.modelcontextprotocol/clientCapabilities")
              {:keys [status json]} (http-response nb writer m (mirrored-headers m))]
          (is (= 400 status))
          (is (= -32602 (get-in json [:error :code])))))
      (testing "server/discover without a revision is malformed"
        (let [{:keys [status json]} (http-response nb writer {:jsonrpc "2.0" :id 3 :method "server/discover" :params {}}
                                                   {"mcp-method" ["server/discover"]})]
          (is (= 400 status))
          (is (= -32602 (get-in json [:error :code])))))
      (testing "headers that differ from the body are refused"
        (doseq [[why headers] [["no version header" (dissoc (mirrored-headers read-skill) "mcp-protocol-version")]
                               ["another version" (assoc (mirrored-headers read-skill) "mcp-protocol-version" ["2025-06-18"])]
                               ["another method" (assoc (mirrored-headers read-skill) "mcp-method" ["tools/list"])]
                               ["no name" (dissoc (mirrored-headers read-skill) "mcp-name")]
                               ["another name" (assoc (mirrored-headers read-skill) "mcp-name" ["skill://kajero/other.md"])]
                               ["a name sent twice" (assoc (mirrored-headers read-skill) "mcp-name" ["skill://kajero/SKILL.md" "skill://kajero/SKILL.md"])]
                               ["a bad base64 name" (assoc (mirrored-headers read-skill) "mcp-name" ["=?base64?!!!?="])]]]
          (let [{:keys [status json]} (http-response nb writer read-skill headers)]
            (is (= 400 status) why)
            (is (= -32020 (get-in json [:error :code])) why))))
      (testing "a name in the Base64 sentinel form is decoded before it is compared"
        (let [encoded (str "=?base64?" (.encodeToString (java.util.Base64/getEncoder) (.getBytes "skill://kajero/SKILL.md" "UTF-8")) "?=")
              {:keys [status json]} (http-response nb writer read-skill (assoc (mirrored-headers read-skill) "mcp-name" [encoded]))]
          (is (= 200 status))
          (is (string? (get-in json [:result :contents 0 :text])))))
      (testing "an unknown method is not found"
        (let [m (modern-message {:jsonrpc "2.0" :id 4 :method "prompts/list"})
              {:keys [status json]} (http-response nb writer m (mirrored-headers m))]
          (is (= 404 status))
          (is (= -32601 (get-in json [:error :code])))))
      (testing "a legacy client on the same endpoint is unaffected"
        (let [{:keys [status json headers]} (http-response nb writer {:jsonrpc "2.0" :id 5 :method "initialize"
                                                                      :params {:protocolVersion "2025-06-18"}} {})]
          (is (= 200 status))
          (is (= "kajero" (get-in json [:result :serverInfo :name])))
          (is (string? (get headers "Mcp-Session-Id")) "the handshake still mints a session"))))))

(deftest a-modern-search-over-http-counts-for-no-session
  (let [nb (notebook)
        session (k/start-session! nb)
        seen (atom [])
        search (fn [m headers]
                 (with-redefs [k/search! (fn [_ s _] (swap! seen conj s) [])]
                   (web/mcp-endpoint nb {:token "s3cret" :writer {:actor "claude"} :port 7270}
                                     {:method "POST" :authorization "Bearer s3cret" :session session
                                      :mcp-headers headers :body (json/generate-string m)})))
        legacy {:jsonrpc "2.0" :id 1 :method "tools/call" :params {:name "kajero_search" :arguments {:selector "~x"}}}
        modern (modern-message legacy)]
    (is (= 200 (:status (search legacy {}))))
    (is (= 200 (:status (search modern (mirrored-headers modern)))))
    (is (= [session nil] @seen) "the handshake session counts; a modern request carrying the header counts for none")))

(deftest a-modern-header-without-meta-is-malformed
  (let [nb (notebook)
        m {:jsonrpc "2.0" :id 1 :method "tools/list"}
        {:keys [status json]} (http-response nb {:actor "claude"} m (mirrored-headers m))]
    (is (= 400 status))
    (is (= -32602 (get-in json [:error :code])))
    (testing "without the header the same request is the handshake era"
      (let [{:keys [status json]} (http-response nb {:actor "claude"} m {})]
        (is (= 200 status))
        (is (= (count mcp/tools) (count (get-in json [:result :tools]))))))))

(deftest a-notification-runs-nothing
  (let [nb (notebook)
        write {:jsonrpc "2.0" :method "tools/call"
               :params {:name "kajero_write" :arguments {:title "written by a notification" :labels {"kind" "fact"}}}}]
    (testing "over stdio"
      (is (nil? (mcp/handle nb {:actor "claude"} (json/generate-string write)))))
    (testing "over HTTP, accepted with 202"
      (let [{:keys [status body]} (http-response nb {:actor "claude"} write {})]
        (is (= 202 status))
        (is (= "" body))))
    (testing "a modern notification that fails its checks carries the refusal's status"
      (let [m (modern-message write)]
        (is (= 400 (:status (http-response nb {:actor "claude"} m (dissoc (mirrored-headers m) "mcp-name")))))))
    (is (empty? (k/search nb "")) "no notification wrote an entry")))

(deftest a-call-the-protocol-cannot-route-is-an-invalid-params-error
  (let [nb (notebook)
        answer (fn [params] (mcp/handle nb {:actor "claude"}
                                        (json/generate-string {:jsonrpc "2.0" :id 7 :method "tools/call" :params params})))]
    (testing "an unknown tool"
      (let [r (answer {:name "kajero_delete" :arguments {}})]
        (is (= -32602 (get-in r [:error :code])))
        (is (= 7 (:id r)))
        (is (nil? (:result r)))))
    (testing "arguments that are not an object"
      (is (= -32602 (get-in (answer {:name "kajero_search" :arguments ["~x"]}) [:error :code]))))
    (testing "absent arguments are an empty object, which the tool judges"
      (is (false? (get-in (answer {:name "kajero_labels"}) [:result :isError]))))
    (testing "a refusal of a known tool is still a tool error"
      (let [r (answer {:name "kajero_get" :arguments {:entry "nope"}})]
        (is (true? (get-in r [:result :isError])))
        (is (= "no-entry" (get (json/parse-string (get-in r [:result :content 0 :text])) "refused")))))))

(deftest an-unknown-legacy-method-is-not-found
  (let [nb (notebook)
        r (mcp/handle nb {} "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"prompts/list\"}")]
    (is (= -32601 (get-in r [:error :code])))
    (is (= 3 (:id r)))))
