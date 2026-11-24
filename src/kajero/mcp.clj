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
            [clojure.string :as str]
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
    :description "Entries a selector picks, as summaries. With text terms the best match comes first: a match in the title counts most, then labels, body and data, and each summary carries its score and, per term, the field it matched in with the text around it, so you can judge a hit without reading the entry. Results are ordered by rank = score + min(citations, 3), where citations counts the entries whose current revision names the result as [[<id>]] in its body or links to it by see-also (supersedes and contradicts links, self-references and retired or superseded citers do not count); cite the entries your write built on as [[<id>]]. Without text terms, most recently changed first. Selector terms, all of which must hold, each optionally prefixed `not`: label:key, label:key=value, data:field, data:field=value (a dotted field walks the entry's data through maps and into every map of a list, as in data:hosts.domain=x; terms about one list must hold in the same element, and each result carries the deepest matching elements as rows), actor=name, ticket=<tik ticket id>, path=<repository path> (the title, body or evidence names that file; one path ending with the other at a segment boundary counts, so path=helm-charts/x/templates/a.tpl finds an entry citing templates/a.tpl), link=<entry id> (linked to that entry in either direction), link:<type>=<entry id> (linked by supersedes, contradicts or see-also), schema-revision=<hash>, outdated-schema, disputed, stale, unsigned, superseded, retired, missed (entries whose current revision a search missed: they need better titles or labels), coverage (kind=coverage entries: which questions were asked, over what population, how and when - search coverage with the subject's labels before concluding from an empty result that nothing is known), ~text. Join terms with | to accept any of them, as in calico|coredns or label:kind=gotcha|label:kind=fact: use it when the wording is a guess, because two separate words must both match; the entries holding more of the alternatives rank first, and a data: term cannot be an alternative. Retired entries are left out unless the selector names retired. An empty selector picks every entry that is not retired."
    :inputSchema {:type "object" :properties {:selector {:type "string"}}}}
   {:name "kajero_get"
    :description "One entry: its current revision, body, labels, data (also as data_edn), evidence, standing, links, the observations a later one corrected (corrections), and the tik tickets its records cite with their current stages. A schema entry also lists the entries using it (used_by)."
    :inputSchema {:type "object" :properties entry-arg :required ["entry"]}}
   {:name "kajero_history"
    :description "Every revision, observation and link of an entry, oldest first, with hashes and authors."
    :inputSchema {:type "object" :properties entry-arg :required ["entry"]}}
   {:name "kajero_labels"
    :description "Every label in use with its count. Read it before writing, to reuse the labels others chose."
    :inputSchema {:type "object" :properties {}}}
   {:name "kajero_write"
    :description "Write a new entry about one topic. Returns its id, warnings for labels nobody used before, and existing entries that look the same - revise one of those instead of keeping a duplicate. After a survey, write a kind=coverage entry with the subject's labels whose data names :question, :population, :method and :asked (YYYY-MM-DD), even when it found nothing."
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
    :description "Record that you checked an entry's revision (the current one by default) and found it held or failed. Base it on a fresh check, never on having read the entry. A failed observation needs evidence and disputes the revision until a new revision answers it. To correct an earlier observation whose evidence or reasoning turned out wrong, pass its hash (from kajero_history) as corrects, with evidence saying what was wrong: the new outcome replaces its verdict, or withdrawn takes it back without a new one. The earlier observation stays in the history, marked corrected, and kajero_get lists it under corrections. Record missed when the entry was right but a search that should have found it did not, and a later route reached it: pass the selector that was run. A missed observation leaves held, failed and disputed alone; kajero_get lists it under missed, and the selector term missed picks entries whose current revision has one."
    :inputSchema {:type "object"
                  :properties (merge entry-arg
                                     {:outcome {:type "string" :enum ["held" "failed" "missed" "withdrawn"]}
                                      :selector {:type "string" :description "for missed: the selector that did not find the entry"}
                                      :revision {:type "string"}
                                      :corrects {:type "string" :description "the hash of the earlier observation this one corrects"}}
                                     evidence-arg)
                  :required ["entry" "outcome"]}}
   {:name "kajero_observe_many"
    :description "Record several observations in one call, as kajero_observe does for one: each item names its entry (a short id is fine), its outcome and its evidence, and may name a revision or the observation it corrects. Use it to feed a book run or a survey back as standing: one call for every entry the run confirmed or contradicted. Each item is judged on its own, so a refused one leaves the others recorded; the result lists, in order, the observation and new standing of each recorded item and the reason for each refused one. At most 500 items."
    :inputSchema {:type "object"
                  :properties {:observations
                               {:type "array"
                                :items {:type "object"
                                        :properties (merge entry-arg
                                                           {:outcome {:type "string" :enum ["held" "failed" "missed" "withdrawn"]}
                                                            :selector {:type "string"}
                                                            :revision {:type "string"}
                                                            :corrects {:type "string"}}
                                                           evidence-arg)
                                        :required ["entry" "outcome"]}}}
                  :required ["observations"]}}
   {:name "kajero_retire"
    :description "Retire an entry written by mistake, as a test, or as a duplicate: a signed revision on top of base that keeps its content and history and records the reason. Search, labels and the similar-entry check leave it out unless the selector names `retired`; kajero_get still reads it. kajero_restore lifts it. When a better entry replaces it, link supersedes instead."
    :inputSchema {:type "object"
                  :properties (merge entry-arg {:base {:type "string"} :reason {:type "string"}} evidence-arg)
                  :required ["entry" "base" "reason"]}}
   {:name "kajero_restore"
    :description "Restore a retired entry: a revision on top of base that lifts the retirement and leaves the content as it was."
    :inputSchema {:type "object"
                  :properties (merge entry-arg {:base {:type "string"}} evidence-arg)
                  :required ["entry" "base"]}}
   {:name "kajero_link"
    :description "Link two entries: source supersedes, contradicts or is see-also target."
    :inputSchema {:type "object"
                  :properties {:source {:type "string"}
                               :type {:type "string" :enum ["supersedes" "contradicts" "see-also"]}
                               :target {:type "string"}}
                  :required ["source" "type" "target"]}}
   {:name "kajero_migrate"
    :description "Run a migration entry (labelled kind=migration, data {:from schema :to schema :steps [...]}): every entry using the :from schema gets a new revision with converted data on the :to schema. Nothing is written when any entry fails to convert or to fit the :to schema; an entry somebody revises meanwhile is reported as stale and keeps its schema. Use dry_run first to see the result."
    :inputSchema {:type "object"
                  :properties {:migration {:type "string" :description "the migration entry"}
                               :dry_run {:type "boolean"}}
                  :required ["migration"]}}])

(defn- read-edn
  [s]
  (try (edn/read-string s)
       (catch Exception e
         (throw (ex-info (str "the data is not EDN: " (ex-message e)) {:reason :kajero/bad-edn})))))

(defn- refuse!
  [reason message data]
  (throw (ex-info message (assoc data :reason reason))))

(defn check-arguments!
  "Refuses a call whose arguments the tool does not take, or that leaves
   out one it requires, naming the parameter and the ones the tool takes:
   a refusal about the value of a misspelt parameter would describe the
   wrong mistake."
  [tool args]
  (when-let [{{:keys [properties required]} :inputSchema} (some #(when (= tool (:name %)) %) tools)]
    (let [known (mapv name (keys properties))
          takes (if (seq known) (str "; it takes " (str/join ", " known)) "; it takes none")]
      (doseq [k (sort (keys args))
              :when (not-any? #{k} known)]
        (refuse! :kajero/unknown-parameter (str tool " has no parameter " k takes)
                 {:tool tool :parameter k}))
      (doseq [k required
              :when (nil? (get args k))]
        (refuse! :kajero/missing-parameter (str tool " needs the parameter " k takes)
                 {:tool tool :parameter k})))))

(defn- resolve-ids
  "The arguments with every entry id resolved, so the eight-character form
   prose quotes names its entry - see kajero.core/resolve-id."
  [nb args]
  (reduce (fn [a k] (cond-> a (string? (get a k)) (update k #(k/resolve-id nb %))))
          args ["entry" "source" "target" "migration"]))

(def ^:private observation-keys #{"entry" "outcome" "revision" "evidence" "corrects" "selector"})

(defn- observe-many
  "Observes each item; an item naming a parameter an observation does not
   take is refused alone, as a misspelt outcome would otherwise be read as
   a missing one."
  [nb writer items]
  (when-not (sequential? items)
    (refuse! :kajero/no-observations "observations is a list of observations" {}))
  (let [bad (fn [item] (first (remove observation-keys (keys item))))
        checked (mapv (fn [item]
                        (if-let [k (and (map? item) (bad item))]
                          {::refused {:entry (get item "entry") :refused "unknown-parameter"
                                      :message (str "an observation has no parameter " k "; it takes "
                                                    (str/join ", " (sort observation-keys)))}}
                          (into {} (map (fn [[k v]] [(keyword k) v])) (when (map? item) item))))
                      items)
        recorded (when-let [ok (seq (remove ::refused checked))]
                   (k/observe-many! nb (assoc writer :observations (vec ok))))]
    (loop [[c & more] checked rs recorded acc []]
      (cond
        (nil? c) acc
        (::refused c) (recur more rs (conj acc (::refused c)))
        :else (recur more (rest rs) (conj acc (first rs)))))))

(defn- call-checked
  [nb writer tool {:strs [selector entry title body labels evidence base outcome revision corrects source type target data_edn migration dry_run reason observations]}]
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
    "kajero_observe" (k/observe! nb entry (merge writer {:outcome outcome :revision revision :evidence evidence
                                                         :corrects corrects :selector selector}))
    "kajero_observe_many" (observe-many nb writer observations)
    "kajero_retire" (k/retire! nb entry (merge writer {:base base :reason reason :evidence evidence}))
    "kajero_restore" (k/restore! nb entry (merge writer {:base base :evidence evidence}))
    "kajero_link" (k/link! nb (merge writer {:source source :type type :target target}))
    "kajero_migrate" (k/migrate! nb migration (assoc writer :dry-run? (true? dry_run)))
    (throw (ex-info (str "unknown tool " tool) {:reason :kajero/unknown-tool}))))

(defn call-tool
  "Runs one tool; returns the result, or throws ex-info for a refusal."
  [nb writer tool args]
  (check-arguments! tool args)
  (call-checked nb writer tool (resolve-ids nb args)))

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
