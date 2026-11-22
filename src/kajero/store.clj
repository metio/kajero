;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: 0BSD
(ns kajero.store
  "The SQLite database behind a notebook. Every table is append-only: a
   revision, an observation and a link are written once and never changed,
   so the history of an entry is its rows in order."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.sql Connection DriverManager PreparedStatement ResultSet)))

(def ^:private schema
  ["CREATE TABLE IF NOT EXISTS revisions (
      hash TEXT PRIMARY KEY, entry TEXT NOT NULL, seq INTEGER NOT NULL,
      record TEXT NOT NULL, sig BLOB, UNIQUE (entry, seq))"
   "CREATE TABLE IF NOT EXISTS observations (
      hash TEXT PRIMARY KEY, entry TEXT NOT NULL, revision TEXT NOT NULL,
      record TEXT NOT NULL, sig BLOB)"
   "CREATE TABLE IF NOT EXISTS links (
      hash TEXT PRIMARY KEY, source TEXT NOT NULL, target TEXT NOT NULL,
      record TEXT NOT NULL, sig BLOB)"
   "CREATE INDEX IF NOT EXISTS observations_by_entry ON observations (entry)"
   "CREATE INDEX IF NOT EXISTS links_by_source ON links (source)"
   "CREATE INDEX IF NOT EXISTS links_by_target ON links (target)"])

(defn open
  "Opens, and creates when missing, the notebook database at `path`."
  ^Connection [path]
  (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
    (with-open [st (.createStatement conn)]
      (doseq [ddl schema] (.execute st ddl)))
    conn))

(defn- bind!
  ^PreparedStatement [^PreparedStatement ps params]
  (doseq [[i p] (map-indexed vector params)]
    (.setObject ps (int (inc i)) p))
  ps)

(defn execute!
  [^Connection conn sql & params]
  (with-open [ps (bind! (.prepareStatement conn sql) params)]
    (.executeUpdate ps)))

(defn query
  "The rows of `sql` as maps of lower-case column keywords."
  [^Connection conn sql & params]
  (with-open [ps (bind! (.prepareStatement conn sql) params)
              ^ResultSet rs (.executeQuery ps)]
    (let [md (.getMetaData rs)
          cols (mapv #(keyword (str/lower-case (.getColumnLabel md (int %))))
                     (range 1 (inc (.getColumnCount md))))]
      (loop [rows []]
        (if (.next rs)
          (recur (conj rows (zipmap cols (map #(.getObject rs (int %)) (range 1 (inc (count cols)))))))
          rows)))))

(defn in-transaction
  "Runs `f` inside one transaction, so a write either lands whole or not at all."
  [^Connection conn f]
  (.setAutoCommit conn false)
  (try
    (let [result (f)]
      (.commit conn)
      result)
    ;; Any failure, an Error included, has to roll back before it travels
    ;; on; it is rethrown unchanged.
    #_{:splint/disable [lint/catch-throwable]}
    (catch Throwable t
      (.rollback conn)
      (throw t))
    (finally
      (.setAutoCommit conn true))))

(defn read-record
  [row]
  (assoc (edn/read-string (:record row)) :sig (:sig row)))
