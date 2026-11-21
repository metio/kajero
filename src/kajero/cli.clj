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
     KAJERO_TIK_ROOT the tik store whose tickets evidence cites as tik:<id>"
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [kajero.core :as k])
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
  link SOURCE supersedes|see-also|contradicts TARGET
  migrate MIGRATION-ID [dry-run]          move entries onto the migration's schema

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

(defn- read-edn
  [s]
  (try (edn/read-string s)
       (catch Exception e
         (throw (ex-info (str "the data is not EDN: " (ex-message e)) {:reason :kajero/bad-edn})))))

(defn- data-of [flags]
  (when (contains? flags :data) {:data (read-edn (one flags :data))}))

(defn- writer []
  {:actor (env "KAJERO_ACTOR") :key (env "KAJERO_KEY")})

(defn- run-command
  [nb [cmd & args]]
  (let [[pos flags] (options args)]
    (case cmd
      "search" (k/search nb (str/join " " pos))
      "get" (k/entry nb (first pos))
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
                                                            :evidence (get flags :evidence)}))
      "link" (let [[source type target] pos]
               (k/link! nb (merge (writer) {:source source :type type :target target})))
      "migrate" (k/migrate! nb (first pos) (assoc (writer) :dry-run? (= "dry-run" (second pos))))
      (throw (ex-info usage {:reason :kajero/usage})))))

(defn -main
  [& args]
  (try
    (let [nb (k/notebook {:dir (env "KAJERO_DIR")
                          :path (or (env "KAJERO_DB") "notes.db")
                          :signing (keyword (or (env "KAJERO_SIGNING") "optional"))
                          :actors (env "KAJERO_ACTORS")
                          :tik-root (env "KAJERO_TIK_ROOT")})]
      (pp/pprint (run-command nb args))
      (shutdown-agents))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (ex-message e))
        (when-let [current (:current (ex-data e))] (println "current revision:" current)))
      (System/exit 1))))
