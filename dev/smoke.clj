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
   - the stdio MCP server answers initialize;
   - the command line runs search and labels, and an argument beginning
     with -D or -XX: reaches it instead of the runtime;
   - a SQLite notebook takes a signed write from the command line, checks
     its tik: citation with `tik status` (a stub on PATH) and refuses one
     tik does not know;
   - a revision changed on disk after it was signed reads as bad-signature;
   - serve signs a checkpoint with a key of its own and reports it over
     MCP, a SIGTERM ends it with 143 after its shutdown hooks signed a last
     checkpoint, and the command line reports the changed revision's signed
     bytes as a missing record.

   It exits 0 when every check held and 1 naming the first that failed."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- words [msg] (remove #(or (nil? %) (str/blank? (str %))) msg))

(defn- fail! [& msg]
  (binding [*out* *err*] (apply println "smoke: FAILED:" (words msg)))
  (System/exit 1))

(defn- check! [ok? & msg]
  (if ok? (apply println "smoke: ok:" (words msg)) (apply fail! msg)))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn- keypair! [dir actor]
  (let [key (str (fs/path dir actor))]
    (p/shell {:out :string :err :string} "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" actor "-f" key)
    {:actor actor :key key :public (str/trim (slurp (str key ".pub")))}))

(defn- wait-for [url deadline-ms]
  (let [until (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (let [status (try (:status (http/get url {:throw false :timeout 1000}))
                        (catch Exception _ nil))]
        (cond
          (= 200 status) true
          (> (System/currentTimeMillis) until) false
          :else (do (Thread/sleep 100) (recur)))))))

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

(defn- tool [url token session id name args]
  (let [{:keys [body]} (rpc url token session {:jsonrpc "2.0" :id id :method "tools/call"
                                               :params {:name name :arguments args}})
        result (:result body)]
    (when (or (nil? result) (:isError result))
      (fail! name "was refused:" (pr-str body)))
    (json/parse-string (get-in result [:content 0 :text]) true)))

(defn -main [& command]
  (let [;; kajero runs in the temporary directory, so a relative path to
        ;; the program or its jar is resolved against the directory smoke
        ;; started in
        command (map #(if (fs/exists? %) (str (fs/absolutize %)) %) (or (seq command) ["target/kajero"]))
        tmp (fs/create-temp-dir {:prefix "kajero-smoke"})
        notes (str (fs/path tmp "notes"))
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
        run (fn [opts & args] (apply p/process (merge {:dir (str tmp) :extra-env {} :env env} opts) (concat command args)))]
    (spit actors (str (:actor human) " " (:public human) "\n" (:actor agent) " " (:public agent) "\n"))
    (spit token-file token)
    (try
      ;; serve, with the pages and MCP on /mcp
      (let [server (run {:env (assoc env "KAJERO_PORT" (str port) "KAJERO_MCP_TOKEN_FILE" token-file
                                     "KAJERO_MCP_ACTOR" (:actor agent) "KAJERO_MCP_KEY" (:key agent)
                                     "KAJERO_CHECKPOINT_KEY" (:key verifier))
                         :out :inherit :err :inherit}
                        "serve")
            entry (try
                    (check! (wait-for (str base "/") 60000) "serve answered GET / with 200")
                    (let [url (str base "/mcp")
                          {:keys [session body]} (rpc url token nil {:jsonrpc "2.0" :id 1 :method "initialize"
                                                                     :params {:protocolVersion "2025-06-18"}})
                          _ (check! (= "kajero" (get-in body [:result :serverInfo :name])) "MCP initialize over HTTP")
                          tools (get-in (rpc url token session {:jsonrpc "2.0" :id 2 :method "tools/list"}) [:body :result :tools])
                          _ (check! (some #(= "kajero_write" (:name %)) tools) "MCP tools/list names kajero_write")
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
                      (let [status (tool url token session 6 "kajero_checkpoint" {})]
                        (check! (and (:enabled? status) (:signing? status)) "MCP kajero_checkpoint reports checkpoints signed"
                                (pr-str status)))
                      id)
                    (finally
                      (p/destroy server)
                      (check! (= 143 (:exit @server)) "serve stopped on SIGTERM with 143")))]
        (let [{:keys [exit out err]} @(run {:env (assoc env "KAJERO_CHECKPOINT_PUBKEY" (:public verifier))
                                            :out :string :err :string}
                                           "checkpoint")
              latest (when (zero? exit) (:latest (edn/read-string out)))]
          (check! (= 1 (:leaves latest)) "serve ran its shutdown hooks on SIGTERM: its last checkpoint holds the entry"
                  (pr-str latest) err))
        ;; the stdio MCP server
        (let [mcp (run {:in (str (json/generate-string {:jsonrpc "2.0" :id 1 :method "initialize"
                                                        :params {:protocolVersion "2025-06-18"}})
                                 "\n")
                        :out :string :err :string}
                       "mcp")
              {:keys [exit out err]} (deref mcp 60000 nil)
              answer (some-> out str/split-lines first (json/parse-string true))]
          (check! (and (zero? exit) (= "kajero" (get-in answer [:result :serverInfo :name])))
                  "the stdio MCP server answered initialize and ended with stdin" (when-not (zero? exit) err)))
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
        ;; line: a stub tik on PATH knows one ticket and no other
        (let [bin (fs/create-dirs (fs/path tmp "bin"))
              stub (fs/path bin "tik")
              known "00000000-0000-4000-8000-000000000001"
              unknown "00000000-0000-4000-8000-000000000002"
              sqlite (-> env
                         (dissoc "KAJERO_DIR")
                         (assoc "KAJERO_DB" (str (fs/path tmp "notes.db"))
                                "KAJERO_TIK_ROOT" (str tmp)
                                "PATH" (str bin java.io.File/pathSeparator (System/getenv "PATH"))))
              write (fn [ticket]
                      @(run {:env sqlite :out :string :err :string}
                            "write" "SQLite smoke entry" "--body" "On SQLite." "--label" "kind=fact"
                            "--label" "system=smoke" "--evidence" (str "tik:" ticket)))]
          (spit (str stub) (str "#!/bin/sh\n"
                                "[ \"$2\" = " known " ] || exit 1\n"
                                "echo '{:title \"Smoke ticket\" :current [:open] :rules {:process :change}}'\n"))
          (fs/set-posix-file-permissions stub "rwx------")
          (let [{:keys [exit out err]} (write known)
                id (when (zero? exit) (:entry (edn/read-string out)))]
            (check! (string? id) "the CLI wrote to SQLite citing a ticket tik knows" err)
            (let [{:keys [out err]} @(run {:env sqlite :out :string :err :string} "get" id)
                  got (edn/read-string out)]
              (check! (= :verified (keyword (name (:signature got)))) "it reads from SQLite as verified" err)
              (check! (= ["Smoke ticket"] (map :title (:tickets got))) "the entry shows the ticket tik status read")))
          (let [{:keys [exit err]} (write unknown)]
            (check! (and (= 1 exit) (str/includes? err unknown)) "a citation of a ticket tik does not know is refused")))
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
      (println "smoke: every check held")
      (finally (fs/delete-tree tmp)))))

(apply -main *command-line-args*)
