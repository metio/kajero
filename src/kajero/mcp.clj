;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.mcp
  "An MCP server over stdio: the notebook's reads and writes as tools.

   Writes name the actor of KAJERO_ACTOR and, with KAJERO_KEY set, are signed
   with that key, so every write an agent makes through the server is its
   own record. A refusal answers as a tool error with its reason, and a line
   the server can not read answers as a JSON-RPC error, so the session
   survives whatever the client sends."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [kajero.core :as k]))

(def ^:private entry-arg {:entry {:type "string" :description "The entry id"}})

(def ^:private labels-arg
  {:labels {:type "object" :additionalProperties {:type "string"}
            :description "Labels as key/value pairs, for example {\"kind\": \"gotcha\", \"system\": \"calico\"}"}})

(def ^:private evidence-arg
  {:evidence {:type "array" :items {:type "string"}
              :description "What backs the record: a command and its output, a commit, a URL, or tik:<ticket id> for a tik ticket, which is checked against the tik store"}})

(def ^:private data-arg
  {:data_edn {:type "string"
              :description "Structured data as EDN, for example [{:host \"w-1\" :cpus 8}]. A list of maps shows as a table on the pages. An entry labelled kind=schema holds a malli schema here, such as [:vector [:map [:host :string] [:cpus :int]]]; label an entry schema=<that entry> to have its data checked against it."}})

(def tools
  [{:name "kajero_search"
    :description "Entries a selector picks, as summaries, most recently changed first. Selector terms, all of which must hold, each optionally prefixed `not`: label:key, label:key=value, actor=name, ticket=<tik ticket id>, schema-revision=<hash>, outdated-schema, disputed, stale, unsigned, superseded, ~text. An empty selector picks every entry."
    :inputSchema {:type "object" :properties {:selector {:type "string"}}}}
   {:name "kajero_get"
    :description "One entry: its current revision, body, labels, data (also as data_edn), evidence, standing, links, and the tik tickets its records cite with their current stages. A schema entry also lists the entries using it (used_by)."
    :inputSchema {:type "object" :properties entry-arg :required ["entry"]}}
   {:name "kajero_history"
    :description "Every revision, observation and link of an entry, oldest first, with hashes and authors."
    :inputSchema {:type "object" :properties entry-arg :required ["entry"]}}
   {:name "kajero_labels"
    :description "Every label in use with its count. Read it before writing, to reuse the labels others chose."
    :inputSchema {:type "object" :properties {}}}
   {:name "kajero_write"
    :description "Write a new entry about one topic. Returns its id, warnings for labels nobody used before, and existing entries that look the same - revise one of those instead of keeping a duplicate."
    :inputSchema {:type "object"
                  :properties (merge {:title {:type "string"} :body {:type "string" :description "Markdown"}}
                                     labels-arg evidence-arg data-arg)
                  :required ["title" "labels"]}}
   {:name "kajero_revise"
    :description "Write a new revision of an entry. base must be its current revision; a stale base is refused with the current one, so re-read and retry. What is left out stays as it was."
    :inputSchema {:type "object"
                  :properties (merge entry-arg
                                     {:base {:type "string"} :title {:type "string"} :body {:type "string"}}
                                     labels-arg evidence-arg data-arg)
                  :required ["entry" "base"]}}
   {:name "kajero_observe"
    :description "Record that you checked an entry's revision (the current one by default) and found it held or failed. Base it on a fresh check, never on having read the entry. A failed observation needs evidence and disputes the revision until a new revision answers it."
    :inputSchema {:type "object"
                  :properties (merge entry-arg
                                     {:outcome {:type "string" :enum ["held" "failed"]}
                                      :revision {:type "string"}}
                                     evidence-arg)
                  :required ["entry" "outcome"]}}
   {:name "kajero_link"
    :description "Link two entries: source supersedes, contradicts or is see-also target."
    :inputSchema {:type "object"
                  :properties {:source {:type "string"}
                               :type {:type "string" :enum ["supersedes" "contradicts" "see-also"]}
                               :target {:type "string"}}
                  :required ["source" "type" "target"]}}
   {:name "kajero_migrate"
    :description "Run a migration entry (labelled kind=migration, data {:from schema :to schema :steps [...]}): every entry using the :from schema gets a new revision with converted data on the :to schema. Nothing is written when any entry fails to convert. Use dry_run first to see the result."
    :inputSchema {:type "object"
                  :properties {:migration {:type "string" :description "the migration entry"}
                               :dry_run {:type "boolean"}}
                  :required ["migration"]}}])

(defn- read-edn
  [s]
  (try (edn/read-string s)
       (catch Exception e
         (throw (ex-info (str "the data is not EDN: " (ex-message e)) {:reason :kajero/bad-edn})))))

(defn call-tool
  "Runs one tool; returns the result, or throws ex-info for a refusal."
  [nb writer tool {:strs [selector entry title body labels evidence base outcome revision source type target data_edn migration dry_run]}]
  (case tool
    "kajero_search" (k/search nb selector)
    "kajero_get" (let [e (or (k/entry nb entry) (throw (ex-info (str "no entry " entry) {:reason :kajero/no-entry})))]
                   (cond-> e
                     (contains? e :data) (assoc :data_edn (pr-str (:data e)))
                     (contains? e :used-by) (-> (assoc :used_by (:used-by e)) (dissoc :used-by))))
    "kajero_history" (k/history nb entry)
    "kajero_labels" (k/labels nb)
    "kajero_write" (k/write! nb (merge writer {:title title :body body :labels labels :evidence evidence}
                                       (when data_edn {:data (read-edn data_edn)})))
    "kajero_revise" (k/revise! nb entry (merge writer {:base base :title title :body body
                                                       :labels (not-empty labels) :evidence evidence}
                                               (when data_edn {:data (read-edn data_edn)})))
    "kajero_observe" (k/observe! nb entry (merge writer {:outcome outcome :revision revision :evidence evidence}))
    "kajero_link" (k/link! nb (merge writer {:source source :type type :target target}))
    "kajero_migrate" (k/migrate! nb migration (assoc writer :dry-run? (true? dry_run)))
    (throw (ex-info (str "unknown tool " tool) {:reason :kajero/unknown-tool}))))

(defn- refusal-text
  [e]
  (let [{:keys [reason current]} (ex-data e)]
    (json/generate-string (cond-> {:refused (some-> reason name) :message (ex-message e)}
                            current (assoc :current current)))))

(def ^:private protocol-versions
  "The MCP revisions this server speaks; it answers an initialize in the
   client's revision when it is one of them, else in the newest."
  ["2025-06-18" "2025-03-26" "2024-11-05"])

(defn handle
  "The response to one request line, or nil for a notification."
  [nb writer line]
  (let [msg (try (json/parse-string line) (catch Exception _ ::unreadable))]
    (if-not (map? msg)
      {:jsonrpc "2.0" :id nil :error {:code -32700 :message "parse error: not a JSON object"}}
      (let [{:strs [id method params]} msg
            respond (fn [result] {:jsonrpc "2.0" :id id :result result})]
        (try
          (case method
            "initialize" (respond {:protocolVersion (let [asked (get params "protocolVersion")]
                                                      (if (some #{asked} protocol-versions) asked (first protocol-versions)))
                                   :capabilities {:tools {}}
                                   :serverInfo {:name "kajero" :version "1"}})
            "tools/list" (respond {:tools tools})
            "tools/call" (let [args (get params "arguments")]
                           (try
                             (respond {:content [{:type "text"
                                                  :text (json/generate-string
                                                         (call-tool nb writer (get params "name")
                                                                    (if (map? args) args {})))}]
                                       :isError false})
                             (catch clojure.lang.ExceptionInfo e
                               (respond {:content [{:type "text" :text (refusal-text e)}] :isError true}))))
            (when id (respond {})))
          ;; The protocol boundary: any failure, an Error included, becomes a
          ;; JSON-RPC error answer instead of ending the session.
          #_{:splint/disable [lint/catch-throwable]}
          (catch Throwable e
            {:jsonrpc "2.0" :id id
             :error {:code -32603 :message (str "internal error: " (or (ex-message e) (.getName (class e))))}}))))))

(defn -main
  [& _]
  (let [env #(not-empty (System/getenv %))
        nb (k/notebook {:dir (env "KAJERO_DIR")
                        :path (or (env "KAJERO_DB") "notes.db")
                        :signing (keyword (or (env "KAJERO_SIGNING") "optional"))
                        :actors (env "KAJERO_ACTORS")
                        :tik-root (env "KAJERO_TIK_ROOT")})
        writer {:actor (env "KAJERO_ACTOR") :key (env "KAJERO_KEY")}]
    (loop []
      (when-let [line (read-line)]
        (when-let [response (handle nb writer line)]
          (println (json/generate-string response))
          (flush))
        (recur)))))
