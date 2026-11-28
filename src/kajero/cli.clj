;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.cli
  "The command line. Every command prints EDN on stdout; a refusal prints its
   reason on stderr and exits 1.

   The environment names the notebook and the writer:
     KAJERO_DIR      a directory of flat files, for local testing
     KAJERO_DB       the database file, when KAJERO_DIR is unset (default notes.db)
     KAJERO_SIGNING  required or optional (default optional)
     KAJERO_ACTORS   the allowed-signers file signatures are checked against
     KAJERO_ACTOR    the actor a write names
     KAJERO_KEY      the private key a write is signed with
     KAJERO_TIK_ROOT the tik store whose tickets evidence cites as tik:<id>
     KAJERO_CHECKPOINT_PUBKEY, KAJERO_CHECKPOINT_DIR
                     the pinned checkpoint key and where checkpoints live -
                     see kajero.checkpoint/settings; the command line reads
                     checkpoints and never signs one"
  (:require [briko.json :as bj]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [kajero.checkpoint :as checkpoint]
            [kajero.core :as k]
            [kajero.data :as data]
            [kajero.skills :as skills])
  (:gen-class))

(def ^:private usage
  "Usage: kajero <command> [args]

  search [SELECTOR]                       entries the selector picks
  get ID                                  one entry with its standing
  history ID                              every record of an entry
  labels                                  labels in use, with counts
  write TITLE [--body B] [--data EDN] --label K=V... [--evidence E...]
  revise ID --base REV [--title T] [--body B] [--data EDN] [--label K=V...] [--evidence E...]
  observe ID held|failed [--revision REV] --evidence E...
  observe ID missed --selector S [--evidence E...]   a search for S did not find the entry
  observe-many FILE                       several observations, an EDN list of
                                          {:entry :outcome :evidence [...]}; - reads stdin
  retire ID --base REV --reason R [--evidence E...]   hide an entry from search
  restore ID --base REV [--evidence E...]              lift a retirement
  link SOURCE supersedes|see-also|contradicts TARGET
  migrate MIGRATION-ID [dry-run]          move entries onto the migration's schema
  usage [--since DAY] [--min N]           entries no search returned (since DAY,
                                          YYYY-MM-DD) and entries returned at least
                                          N times (default 3) that nobody opened
  checkpoint                              the latest signed checkpoint: its root, age,
                                          the records it covers and those now missing
  skill manifest                          the skill entries the MCP server serves, signed
                                          as KAJERO_ACTOR with KAJERO_KEY when both are set
  skill verify [FILE] [--root DIR]        check the manifest signature of every skill entry
                                          in FILE (JSON or EDN: an entry, a skills/list or
                                          skills/get result or response; - or none reads
                                          stdin) against KAJERO_ACTORS, and with DIR, a
                                          directory of skill directories, each file's bytes;
                                          exits 1 unless every entry verifies

  A body or data of - reads it from stdin. Data is EDN; an entry labelled
  kind=schema holds a malli schema, and schema=ID checks data against one.
  An entry labelled kind=migration holds {:from SCHEMA :to SCHEMA :steps [...]}.")

(defn- env [k] (not-empty (System/getenv k)))

(defn- options
  "Positional args and repeatable --flags, as [positional {flag [values]}]."
  [args]
  (loop [[a & more] args pos [] flags {}]
    (cond
      (nil? a) [pos flags]
      (str/starts-with? a "--") (recur (rest more) pos (update flags (keyword (subs a 2)) (fnil conj []) (first more)))
      :else (recur more (conj pos a) flags))))

(defn- one [flags k]
  (let [v (last (get flags k))]
    (if (= "-" v) (slurp *in*) v)))

(defn- labels-of [flags]
  (when-let [ls (seq (get flags :label))]
    (into {} (map #(let [[k v] (str/split % #"=" 2)] [k (or v "")])) ls)))

(defn- data-of [flags]
  (when (contains? flags :data) {:data (data/read-edn (one flags :data))}))

(defn- writer []
  {:actor (env "KAJERO_ACTOR") :key (env "KAJERO_KEY")})

(defn- usage-report
  "The two lists of the usage counts, with the day the counting began."
  [nb since at-least]
  (let [summary #(select-keys % [:entry :title :labels])]
    {:since (k/usage-since nb)
     :never-returned (mapv summary (k/search nb (cond-> "never-returned" since (str "=" since))))
     :returned-unopened (mapv summary (k/search nb (cond-> "returned-unopened" at-least (str "=" at-least))))}))

(defn- skill-entries
  "The skill entries in a parsed skills/list or skills/get result, a
   JSON-RPC response carrying one, a list of entries, or one entry."
  [x]
  (let [x (if (and (map? x) (contains? x :result)) (:result x) x)]
    (cond
      (sequential? x) (vec x)
      (contains? x :skills) (vec (:skills x))
      (contains? x :skill) [(:skill x)]
      :else [x])))

(def ^:private listing-options
  "How a skills listing is read as JSON: within the bound kajero.data
   reads the same listing as EDN, so the format a listing comes in does
   not change what can be verified."
  {:max-bytes data/max-edn-bytes :keys :keyword})

(defn- read-entries [text]
  (skill-entries (try (bj/parse-string text listing-options)
                      (catch Exception _ (data/read-edn text)))))

(defn- skill-command
  "The result of a skill command, with :kajero/exit 1 in its metadata when
   an entry does not verify. `getenv` reads the environment."
  [getenv [sub & args]]
  (let [[pos flags] (options args)]
    (case sub
      "manifest" (let [catalog (or @skills/served (throw (ex-info "this build serves no skill" {:reason :kajero/no-skills})))]
                   (:skills (skills/list-result catalog (skills/signer {:actor (getenv "KAJERO_ACTOR") :key (getenv "KAJERO_KEY")}))))
      "verify" (let [f (first pos)
                     entries (read-entries (if (or (nil? f) (= "-" f)) (slurp *in*) (slurp f)))
                     root (one flags :root)
                     results (vec (for [entry entries
                                        :let [v (skills/verify-entry (getenv "KAJERO_ACTORS") entry)
                                              e (try (bj/parse-string (bj/write-string entry) listing-options)
                                                     (catch Exception _ nil))
                                              skill (some->> (:uri e) (re-find #"^skill://([^/]+)/") second)
                                              differ (when (and root skill)
                                                       (skills/verify-files e (fn [p]
                                                                                (let [file (io/file root skill p)]
                                                                                  (when (.isFile file)
                                                                                    (java.nio.file.Files/readAllBytes (.toPath file)))))))]]
                                    (cond-> (assoc v :uri (:uri e))
                                      root (assoc :files-differ (vec differ)))))]
                 (with-meta results
                   {:kajero/exit (if (and (seq results)
                                          (every? #(and (:verified? %) (empty? (:files-differ %))) results))
                                   0 1)}))
      (throw (ex-info usage {:reason :kajero/usage})))))

(defn- run-command
  [nb [cmd & args]]
  (let [[pos flags] (options args)]
    (case cmd
      "search" (k/search! nb nil (str/join " " pos))
      "get" (k/open! nb nil (first pos))
      "history" (k/history nb (first pos))
      "labels" (k/labels nb)
      "write" (k/write! nb (merge (writer) {:title (first pos) :body (one flags :body)
                                            :labels (labels-of flags) :evidence (get flags :evidence)}
                                  (data-of flags)))
      "revise" (k/revise! nb (first pos) (merge (writer) {:base (one flags :base) :title (one flags :title)
                                                          :body (one flags :body) :labels (labels-of flags)
                                                          :evidence (get flags :evidence)}
                                                (data-of flags)))
      "observe" (k/observe! nb (first pos) (merge (writer) {:outcome (second pos) :revision (one flags :revision)
                                                            :evidence (get flags :evidence)
                                                            :selector (one flags :selector)}))
      "observe-many" (let [f (first pos)
                           items (data/read-edn (if (or (nil? f) (= "-" f)) (slurp *in*) (slurp f)))]
                       (k/observe-many! nb (assoc (writer) :observations items)))
      "retire" (k/retire! nb (first pos) (merge (writer) {:base (one flags :base) :reason (one flags :reason)
                                                          :evidence (get flags :evidence)}))
      "restore" (k/restore! nb (first pos) (merge (writer) {:base (one flags :base) :evidence (get flags :evidence)}))
      "link" (let [[source type target] pos]
               (k/link! nb (merge (writer) {:source source :type type :target target})))
      "migrate" (k/migrate! nb (first pos) (assoc (writer) :dry-run? (= "dry-run" (second pos))))
      "usage" (usage-report nb (one flags :since) (one flags :min))
      "checkpoint" (k/checkpoint-status nb)
      (throw (ex-info usage {:reason :kajero/usage})))))

(defn -main
  [& args]
  (try
    (if (= "skill" (first args))
      ;; a skill command reads no notebook, so it opens none
      (let [result (skill-command env (rest args))]
        (pp/pprint result)
        (shutdown-agents)
        (when (= 1 (:kajero/exit (meta result)))
          (System/exit 1)))
      (let [store {:dir (env "KAJERO_DIR") :path (or (env "KAJERO_DB") "notes.db")}
            nb (k/notebook (assoc store
                                  :signing (keyword (or (env "KAJERO_SIGNING") "optional"))
                                  :actors (env "KAJERO_ACTORS")
                                  :tik-root (env "KAJERO_TIK_ROOT")
                                  ;; reading checkpoints needs no signing key
                                  :checkpoint (some-> (checkpoint/settings env store) (dissoc :key))))]
        (try (pp/pprint (run-command nb args))
             (finally (k/close! nb)))
        (shutdown-agents)))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (ex-message e))
        (when-let [current (:current (ex-data e))] (println "current revision:" current)))
      (System/exit 1))))
