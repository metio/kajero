;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: 0BSD
(ns kajero.cli
  "The command line. Every command prints EDN on stdout; a refusal prints its
   reason on stderr and exits 1.

   The environment names the notebook and the writer:
     KAJERO_DB       the database file (default notes.db)
     KAJERO_SIGNING  required or optional (default optional)
     KAJERO_ACTORS   the allowed-signers file signatures are checked against
     KAJERO_ACTOR    the actor a write names
     KAJERO_KEY      the private key a write is signed with"
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [kajero.core :as k])
  (:gen-class))

(def ^:private usage
  "Usage: kajero <command> [args]

  search [SELECTOR]                       entries the selector picks
  get ID                                  one entry with its standing
  history ID                              every record of an entry
  labels                                  labels in use, with counts
  write TITLE [--body B] --label K=V... [--evidence E...]
  revise ID --base REV [--title T] [--body B] [--label K=V...] [--evidence E...]
  observe ID held|failed [--revision REV] --evidence E...
  link SOURCE supersedes|see-also|contradicts TARGET

  A body of - reads it from stdin.")

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
                                            :labels (labels-of flags) :evidence (get flags :evidence)}))
      "revise" (k/revise! nb (first pos) (merge (writer) {:base (one flags :base) :title (one flags :title)
                                                          :body (one flags :body) :labels (labels-of flags)
                                                          :evidence (get flags :evidence)}))
      "observe" (k/observe! nb (first pos) (merge (writer) {:outcome (second pos) :revision (one flags :revision)
                                                            :evidence (get flags :evidence)}))
      "link" (let [[source type target] pos]
               (k/link! nb (merge (writer) {:source source :type type :target target})))
      (throw (ex-info usage {:reason :kajero/usage})))))

(defn -main
  [& args]
  (try
    (let [nb (k/notebook {:path (or (env "KAJERO_DB") "notes.db")
                          :signing (keyword (or (env "KAJERO_SIGNING") "optional"))
                          :actors (env "KAJERO_ACTORS")})]
      (pp/pprint (run-command nb args))
      (shutdown-agents))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (ex-message e))
        (when-let [current (:current (ex-data e))] (println "current revision:" current)))
      (System/exit 1))))
