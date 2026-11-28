;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns smoke
  "A smoke test of a built kajero: bb dev/smoke.clj [COMMAND...]

   COMMAND is how to start kajero, target/kajero by default, or for example
   `java -jar target/kajero.jar`. Everything runs against a notebook in a
   temporary directory, with ed25519 keys and an actors file minted for the
   run, so it touches no notebook, key or port of the host but the free
   port it picks. It checks that

   - serve answers GET / and, over MCP on /mcp, initialize, tools/list,
     kajero_write signed as the MCP actor, kajero_search and kajero_get,
     which marks the written revision verified;
   - serve and the stdio MCP server serve the kajero skill through the MCP
     Skills extension, to a client of the initialize handshake and to one of
     revision 2026-07-28: initialize and server/discover declare it and name
     the SKILL.md URI,
     skills/list lists it with a manifest signed by the MCP actor, and every
     file read with resources/read is the repository's skills/kajero byte
     for byte, with the digest and size of the manifest; `kajero skill
     verify` accepts the signed listing and the repository's files;
   - the stdio MCP server answers initialize;
   - the command line runs search and labels, and an argument beginning
     with -D or -XX: reaches it instead of the runtime;
   - a SQLite notebook takes a signed write from the command line, checks
     its tik: citation against a file-backed and a SQLite-backed tik store
     (copies of dev/smoke-tik), shows the ticket's verified stage, and
     refuses a citation of a ticket the store does not hold;
   - a revision changed on disk after it was signed reads as bad-signature;
   - serve signs a checkpoint with a key of its own and reports it over
     MCP, a SIGTERM ends it with 143 after its shutdown hooks signed a last
     checkpoint, and the command line reports the changed revision's signed
     bytes as a missing record.

   It exits 0 when every check held and 1 naming the first that failed.
   Whatever ends the run - a check that failed, a kajero that died, an
   exception, or SIGTERM or SIGINT to smoke itself - the processes it
   started are stopped and its temporary directory is deleted.
   KAJERO_SMOKE_TMPDIR names the directory the temporary one is made in,
   the system's default otherwise."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- words [msg] (remove #(or (nil? %) (str/blank? (str %))) msg))

(defn- fail!
  "Ends the run as failed: throws, so the cleanup of -main runs before
   smoke exits 1."
  [& msg]
  (throw (ex-info (str/join " " (map str (words msg))) {::failed true})))

(defn- check! [ok? & msg]
  (if ok? (apply println "smoke: ok:" (words msg)) (apply fail! msg)))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn- keypair! [dir actor]
  (let [key (str (fs/path dir actor))]
    (p/shell {:out :string :err :string} "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" actor "-f" key)
    {:actor actor :key key :public (str/trim (slurp (str key ".pub")))}))

(defn- wait-for
  "Whether `url` answers 200 within `deadline-ms`; fails at once when the
   process `proc` serving it has ended."
  [proc url deadline-ms]
  (let [until (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (let [status (try (:status (http/get url {:throw false :timeout 1000}))
                        (catch Exception _ nil))]
        (cond
          (= 200 status) true
          (not (p/alive? proc)) (fail! "serve ended with" (:exit @proc) "before it answered")
          (> (System/currentTimeMillis) until) false
          :else (do (Thread/sleep 100) (recur)))))))

(defn- stop!
  "Stops every process of `procs` still running and waits for it, so none
   writes into the temporary directory while it is deleted."
  [procs]
  (doseq [proc procs :when (p/alive? proc)]
    (p/destroy-tree proc))
  (doseq [proc procs]
    (deref proc 10000 nil)))

(defn- cleanup!
  "Stops the processes and deletes the temporary directory, once."
  [{:keys [done procs tmp]}]
  (when (compare-and-set! done false true)
    (try (stop! @procs)
         (finally (fs/delete-tree tmp)))))

(defn- rpc [url token session body]
  (let [response (http/post url {:throw false
                                 :headers (cond-> {"Authorization" (str "Bearer " token)
                                                   "Content-Type" "application/json"}
                                            session (assoc "Mcp-Session-Id" session))
                                 :body (json/generate-string body)})]
    (when-not (= 200 (:status response))
      (fail! "MCP" (:method body) "answered" (:status response) (:body response)))
    {:session (get-in response [:headers "mcp-session-id"])
     :body (json/parse-string (:body response) true)}))

(def ^:private modern-meta
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientInfo" {"name" "smoke" "version" "1"}
   "io.modelcontextprotocol/clientCapabilities" {}})

(defn- modern [msg]
  (update msg :params #(assoc (or % {}) :_meta modern-meta)))

(defn- modern-rpc
  "A request of MCP revision 2026-07-28 on /mcp, with the headers it
   mirrors; the response body."
  [url token msg]
  (let [{:keys [method params] :as msg} (modern msg)
        response (http/post url {:throw false
                                 :headers (cond-> {"Authorization" (str "Bearer " token)
                                                   "Content-Type" "application/json"
                                                   "Accept" "application/json, text/event-stream"
                                                   "MCP-Protocol-Version" "2026-07-28"
                                                   "Mcp-Method" method}
                                            (:uri params) (assoc "Mcp-Name" (:uri params)))
                                 :body (json/generate-string msg)})]
    (when-not (= 200 (:status response))
      (fail! "MCP 2026-07-28" method "answered" (:status response) (:body response)))
    (when (get-in response [:headers "mcp-session-id"])
      (fail! "MCP 2026-07-28" method "minted a session"))
    (json/parse-string (:body response) true)))

(defn- tool [url token session id name args]
  (let [{:keys [body]} (rpc url token session {:jsonrpc "2.0" :id id :method "tools/call"
                                               :params {:name name :arguments args}})
        result (:result body)]
    (when (or (nil? result) (:isError result))
      (fail! name "was refused:" (pr-str body)))
    (json/parse-string (get-in result [:content 0 :text]) true)))

(defn- sha256 [^bytes b]
  (str "sha256:" (apply str (map #(format "%02x" %) (.digest (java.security.MessageDigest/getInstance "SHA-256") b)))))

(defn- check-skills!
  "Checks the skill a transport serves. `init` is the initialize result,
   `call` sends a request map and returns the response body, `skills-dir`
   is the repository's skills/ and `signer` the actor whose key signs the
   manifest. Returns the skills/list result."
  [transport init call skills-dir signer]
  (check! (contains? (get-in init [:capabilities :extensions]) :io.modelcontextprotocol/skills)
          transport "the server declares the skills extension")
  (check! (str/includes? (str (:instructions init)) "skill://kajero/SKILL.md")
          transport "the server instructions name skill://kajero/SKILL.md")
  (let [listed (:result (call {:jsonrpc "2.0" :id 101 :method "skills/list" :params {}}))
        [entry :as entries] (:skills listed)
        repo (->> (fs/glob (fs/path skills-dir "kajero") "**")
                  (filter fs/regular-file?)
                  (map #(str (fs/relativize (fs/path skills-dir "kajero") %)))
                  sort)
        meta (get-in entry [:_meta :wtf.metio/skill-manifest])]
    (check! (= ["skill://kajero/SKILL.md"] (map :uri entries)) transport "skills/list lists the kajero skill")
    (check! (= (map #(str "skill://kajero/" %) repo) (sort (map :uri (:resources entry))))
            transport "the manifest lists every file of skills/kajero")
    (check! (and (true? (:signed meta)) (= signer (:actor meta)) (= "kajero-skill-manifest" (:namespace meta)))
            transport "the manifest is signed by" signer (pr-str meta))
    (doseq [{:keys [uri digest size]} (:resources entry)]
      (let [read (:result (call {:jsonrpc "2.0" :id 102 :method "resources/read" :params {:uri uri}}))
            served (.getBytes ^String (get-in read [:contents 0 :text]) "UTF-8")
            file (fs/read-all-bytes (fs/path skills-dir "kajero" (subs uri (count "skill://kajero/"))))]
        (check! (java.util.Arrays/equals served file) transport uri "is the repository's file byte for byte")
        (check! (and (= size (alength served)) (= digest (sha256 served))) transport uri "matches its digest and size")))
    listed))

(defn- run-checks
  "Every check, run with `command` in the temporary directory `tmp`;
   each process started is added to `procs`."
  [command tmp procs skills-dir]
  (let [notes (str (fs/path tmp "notes"))
        human (keypair! tmp "human")
        agent (keypair! tmp "agent")
        verifier (keypair! tmp "checkpoint")
        actors (str (fs/path tmp "allowed_signers"))
        token-file (str (fs/path tmp "mcp-token"))
        token (apply str (repeatedly 48 #(rand-nth "0123456789abcdef")))
        port (free-port)
        base (str "http://127.0.0.1:" port)
        env (merge (into {} (System/getenv))
                   {"KAJERO_DIR" notes "KAJERO_SIGNING" "required" "KAJERO_ACTORS" actors
                    "KAJERO_ACTOR" (:actor human) "KAJERO_KEY" (:key human)})
        run (fn [opts & args]
              (let [proc (apply p/process (merge {:dir (str tmp) :extra-env {} :env env} opts) (concat command args))]
                (swap! procs conj proc)
                proc))]
    (spit actors (str (:actor human) " " (:public human) "\n" (:actor agent) " " (:public agent) "\n"))
    (spit token-file token)
    ;; serve, with the pages and MCP on /mcp
    (let [server (run {:env (assoc env "KAJERO_PORT" (str port) "KAJERO_MCP_TOKEN_FILE" token-file
                                   "KAJERO_MCP_ACTOR" (:actor agent) "KAJERO_MCP_KEY" (:key agent)
                                   "KAJERO_CHECKPOINT_KEY" (:key verifier))
                       :out :inherit :err :inherit}
                      "serve")
          entry (do
                  (check! (wait-for server (str base "/") 60000) "serve answered GET / with 200")
                  (let [url (str base "/mcp")
                        {:keys [session body]} (rpc url token nil {:jsonrpc "2.0" :id 1 :method "initialize"
                                                                   :params {:protocolVersion "2025-06-18"}})
                        _ (check! (= "kajero" (get-in body [:result :serverInfo :name])) "MCP initialize over HTTP")
                        tools (get-in (rpc url token session {:jsonrpc "2.0" :id 2 :method "tools/list"}) [:body :result :tools])
                        _ (check! (some #(= "kajero_write" (:name %)) tools) "MCP tools/list names kajero_write")
                        listed (check-skills! "MCP over HTTP:" (:result body)
                                              (fn [msg] (:body (rpc url token session msg)))
                                              skills-dir (:actor agent))
                        _ (check-skills! "MCP 2026-07-28 over HTTP:"
                                         (:result (modern-rpc url token {:jsonrpc "2.0" :id 100 :method "server/discover"}))
                                         (fn [msg] (modern-rpc url token msg))
                                         skills-dir (:actor agent))
                        listing (str (fs/path tmp "skills-list.json"))
                        _ (spit listing (json/generate-string listed))
                        verified @(run {:out :string :err :string} "skill" "verify" listing "--root" skills-dir)
                        _ (check! (zero? (:exit verified)) "kajero skill verify accepts the served listing and the repository's files"
                                  (:out verified) (:err verified))
                        written (tool url token session 3 "kajero_write"
                                      {:title "Smoke test entry" :body "Written by dev/smoke.clj."
                                       :labels {:kind "fact" :system "smoke"}
                                       :evidence ["dev/smoke.clj"]
                                       :data_edn "[{:host \"w-1\" :cpus 8}]"})
                        id (:entry written)
                        _ (check! (string? id) "MCP kajero_write wrote entry" id)
                        found (tool url token session 4 "kajero_search" {:selector "label:system=smoke ~smoke"})
                        _ (check! (= [id] (map :entry found)) "MCP kajero_search found it")
                        got (tool url token session 5 "kajero_get" {:entry id})]
                    (check! (and (= "verified" (:signature got)) (= (:actor agent) (:actor got)))
                            "MCP kajero_get reads it as verified, written by" (:actor got))
                    (check! (= [{:host "w-1" :cpus 8}] (edn/read-string (:data_edn got))) "the data came back as written")
                    (check! (= 200 (:status (http/get (str base "/e/" id) {:throw false}))) "the entry page renders")
                    (let [until (+ (System/currentTimeMillis) 30000)
                          status (loop [i 6]
                                   (let [status (tool url token session i "kajero_checkpoint" {})]
                                     (if (or (= "ready" (get-in status [:warm-up :state]))
                                             (> (System/currentTimeMillis) until))
                                       status
                                       (do (Thread/sleep 100) (recur (inc i))))))]
                      (check! (and (:enabled? status) (:signing? status)) "MCP kajero_checkpoint reports checkpoints signed"
                              (pr-str status))
                      (check! (= "ready" (get-in status [:warm-up :state])) "the checkpoint warm-up ended"
                              (pr-str status)))
                    id))
          ;; a check that failed above leaves serve to the cleanup
          _ (p/destroy server)
          _ (check! (= 143 (:exit @server)) "serve stopped on SIGTERM with 143")]
      (let [{:keys [exit out err]} @(run {:env (assoc env "KAJERO_CHECKPOINT_PUBKEY" (:public verifier))
                                          :out :string :err :string}
                                         "checkpoint")
            latest (when (zero? exit) (:latest (edn/read-string out)))]
        (check! (= 1 (:leaves latest)) "serve ran its shutdown hooks on SIGTERM: its last checkpoint holds the entry"
                (pr-str latest) err))
        ;; the stdio MCP server
      (let [requests (concat [{:jsonrpc "2.0" :id 1 :method "initialize" :params {:protocolVersion "2025-06-18"}}
                              {:jsonrpc "2.0" :id 101 :method "skills/list" :params {}}]
                             (for [f (->> (fs/glob (fs/path skills-dir "kajero") "**") (filter fs/regular-file?))]
                               {:jsonrpc "2.0" :id 102 :method "resources/read"
                                :params {:uri (str "skill://kajero/" (fs/relativize (fs/path skills-dir "kajero") f))}}))
            mcp (run {:in (str/join (map #(str (json/generate-string %) "\n") requests))
                      :out :string :err :string}
                     "mcp")
            {:keys [exit out err]} (deref mcp 60000 nil)
            answers (some->> out str/split-lines (mapv #(json/parse-string % true)))
            answer (first answers)
            ;; the answers come in the order of the requests
            replies (atom (rest answers))
            call (fn [msg]
                   (let [r (first @replies)]
                     (swap! replies rest)
                     (when-not (= (:id msg) (:id r)) (fail! "stdio MCP answered" (pr-str r) "to" (pr-str msg)))
                     r))]
        (check! (and (zero? exit) (= "kajero" (get-in answer [:result :serverInfo :name])))
                "the stdio MCP server answered initialize and ended with stdin" (when-not (zero? exit) err))
        (check-skills! "MCP over stdio:" (:result answer) call skills-dir (:actor human)))
      (let [requests (map modern
                          (concat [{:jsonrpc "2.0" :id 100 :method "server/discover"}
                                   {:jsonrpc "2.0" :id 101 :method "skills/list" :params {}}]
                                  (for [f (->> (fs/glob (fs/path skills-dir "kajero") "**") (filter fs/regular-file?))]
                                    {:jsonrpc "2.0" :id 102 :method "resources/read"
                                     :params {:uri (str "skill://kajero/" (fs/relativize (fs/path skills-dir "kajero") f))}})))
            {:keys [exit out err]} (deref (run {:in (str/join (map #(str (json/generate-string %) "\n") requests))
                                                :out :string :err :string}
                                               "mcp")
                                          60000 nil)
            answers (some->> out str/split-lines (mapv #(json/parse-string % true)))
            replies (atom (rest answers))
            call (fn [msg]
                   (let [r (first @replies)]
                     (swap! replies rest)
                     (when-not (= (:id msg) (:id r)) (fail! "stdio MCP answered" (pr-str r) "to" (pr-str msg)))
                     r))]
        (check! (and (zero? exit) (= ["2026-07-28"] (get-in (first answers) [:result :supportedVersions])))
                "the stdio MCP server answered server/discover" (when-not (zero? exit) err))
        (check-skills! "MCP 2026-07-28 over stdio:" (:result (first answers)) call skills-dir (:actor human)))
        ;; the command line
      (let [{:keys [exit out err]} @(run {:out :string :err :string} "search" "label:system=smoke")
            found (when (zero? exit) (edn/read-string out))]
        (check! (= [entry] (map :entry found)) "the CLI search found the entry" err))
        ;; a native executable would take these for itself unless it is
        ;; built not to parse runtime options
      (let [title "-Dorg.sqlite.lib.path=/nonexistent -XX:+PrintGC"
            {:keys [exit out err]} @(run {:out :string :err :string} "write" title "--label" "kind=fact"
                                         "--label" "system=smoke-args")
            id (when (zero? exit) (:entry (edn/read-string out)))
            got (when id (edn/read-string (:out @(run {:out :string :err :string} "get" id))))]
        (check! (= title (:title got)) "an argument beginning with -D reached the CLI as the title" err))
      (let [{:keys [exit out err]} @(run {:out :string :err :string} "labels")]
        (check! (and (zero? exit) (str/includes? out "smoke")) "the CLI labels lists system=smoke" err))
        ;; a SQLite notebook bound to a tik store, written from the command
        ;; line: copies of the tik stores in dev/smoke-tik, one file-backed
        ;; and one SQLite-backed, each holding one signed ticket at :done
      (doseq [[backend known] [["file" "42dc530c-ef7d-41e4-a14c-45c55e3b29e6"]
                               ["sqlite" "6eaaeb75-4581-4447-a636-1f57f1c7ba15"]]]
        (let [store (fs/path tmp (str "tik-" backend))
              _ (fs/copy-tree (fs/path "dev" "smoke-tik" backend) store)
              unknown "00000000-0000-4000-8000-000000000002"
              sqlite (-> env
                         (dissoc "KAJERO_DIR")
                         (assoc "KAJERO_DB" (str (fs/path tmp (str "notes-" backend ".db")))
                                "KAJERO_TIK_ROOT" (str store)))
              write (fn [ticket]
                      @(run {:env sqlite :out :string :err :string}
                            "write" "SQLite smoke entry" "--body" "On SQLite." "--label" "kind=fact"
                            "--label" "system=smoke" "--evidence" (str "tik:" ticket)))
              what (str "(" backend " tik store)")]
          (let [{:keys [exit out err]} (write known)
                id (when (zero? exit) (:entry (edn/read-string out)))]
            (check! (string? id) "the CLI wrote to SQLite citing a ticket tik knows" what err)
            (let [{:keys [out err]} @(run {:env sqlite :out :string :err :string} "get" id)
                  got (edn/read-string out)]
              (check! (= :verified (keyword (name (:signature got)))) "it reads from SQLite as verified" what err)
              (check! (= [{:ticket known :title "Smoke ticket" :current ["done"] :process "citation"}] (:tickets got))
                      "the entry shows the ticket's title and the stage tik derives, verified" what (pr-str (:tickets got)))))
          (let [{:keys [exit err]} (write unknown)]
            (check! (and (= 1 exit) (str/includes? err unknown)) "a citation of a ticket tik does not know is refused" what))))
        ;; a revision changed after it was signed
      (let [file (fs/file notes "entries" entry "revisions" "00000001.edn")]
        (spit file (str/replace (slurp file) "Smoke test entry" "Tampered entry"))
        (let [{:keys [exit out err]} @(run {:out :string :err :string} "get" entry)
              got (when (zero? exit) (edn/read-string out))]
          (check! (= "Tampered entry" (:title got)) "the CLI reads the changed revision" err)
          (check! (= :bad-signature (keyword (name (:signature got)))) "it reads as bad-signature"))
        (let [pinned (assoc env "KAJERO_CHECKPOINT_PUBKEY" (:public verifier))
              {:keys [exit out err]} @(run {:env pinned :out :string :err :string} "checkpoint")
              status (when (zero? exit) (edn/read-string out))
              found @(run {:env pinned :out :string :err :string} "search" "missing-records")]
          (check! (and (:latest status) (= 1 (:missing status))) "the CLI reports the signed revision as missing" err)
          (check! (= [entry] (map :entry (edn/read-string (:out found)))) "missing-records finds its entry" (:err found)))))
    (println "smoke: every check held")))

(defn -main [& command]
  (let [;; kajero runs in the temporary directory, so a relative path to
        ;; the program or its jar is resolved against the directory smoke
        ;; started in
        command (map #(if (fs/exists? %) (str (fs/absolutize %)) %) (or (seq command) ["target/kajero"]))
        parent (System/getenv "KAJERO_SMOKE_TMPDIR")
        tmp (fs/create-temp-dir (cond-> {:prefix "kajero-smoke"} parent (assoc :dir parent)))
        run-state {:done (atom false) :procs (atom []) :tmp tmp}]
    ;; SIGTERM and SIGINT end smoke through the shutdown hooks
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (cleanup! run-state))))
    (try
      (run-checks command tmp (:procs run-state) (str (fs/absolutize "skills")))
      0
      (catch Exception e
        (binding [*out* *err*]
          (println "smoke: FAILED:" (or (ex-message e) (str (class e)))))
        1)
      (finally (cleanup! run-state)))))

(System/exit (apply -main *command-line-args*))
