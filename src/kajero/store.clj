;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.store
  "Where a notebook keeps its records. Every store is append-only: a revision,
   an observation, a link and a page version are written once and never
   changed, so the history of an entry is its records in order.

   Two stores implement the same protocol: one SQLite database, and a
   directory of EDN files that can be read, diffed and grepped without
   kajero. A record is a map; its signature, when it has one, travels beside
   it as :sig."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kajero.print :as kp]
            [kajero.record :as record])
  (:import (java.io File)
           (java.nio.file FileAlreadyExistsException Files OpenOption StandardOpenOption)
           (java.sql Connection DriverManager PreparedStatement ResultSet)))

(defprotocol Store
  (revisions [store entry] "The entry's revisions, oldest first.")
  (observations [store entry] "The observations of the entry's revisions.")
  (links [store entry] "The links whose source or target is the entry.")
  (entries [store] "The id of every entry.")
  (page-versions [store] "Every version of every page.")
  (append! [store kind rec sig]
    "Appends a record of kind :revision, :observation, :link or :page. A
     revision whose entry already has one with its :seq is refused with
     ex-info {:reason :kajero/stale-base}, so two writers building on the
     same revision can not both land."))

(defn- seq-taken!
  [rec]
  (throw (ex-info "the base is not the current revision"
                  {:reason :kajero/stale-base :entry (:entry rec)})))

;; ------------------------------------------------------------------ SQLite

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
   "CREATE TABLE IF NOT EXISTS pages (
      hash TEXT PRIMARY KEY, name TEXT NOT NULL, record TEXT NOT NULL, sig BLOB)"
   "CREATE INDEX IF NOT EXISTS observations_by_entry ON observations (entry)"
   "CREATE INDEX IF NOT EXISTS links_by_source ON links (source)"
   "CREATE INDEX IF NOT EXISTS links_by_target ON links (target)"])

(defn- bind!
  ^PreparedStatement [^PreparedStatement ps params]
  (doseq [[i p] (map-indexed vector params)]
    (.setObject ps (int (inc i)) p))
  ps)

(defn- execute!
  [^Connection conn sql & params]
  (with-open [ps (bind! (.prepareStatement conn sql) params)]
    (.executeUpdate ps)))

(defn- query
  [^Connection conn sql & params]
  (with-open [ps (bind! (.prepareStatement conn sql) params)
              ^ResultSet rs (.executeQuery ps)]
    (loop [rows []]
      (if (.next rs)
        (recur (conj rows {:record (.getString rs 1) :sig (when (> (.getColumnCount (.getMetaData rs)) 1) (.getBytes rs 2))}))
        rows))))

(defn- read-rows
  [rows]
  (mapv (fn [{:keys [record sig]}] (assoc (edn/read-string record) :sig sig)) rows))

(defrecord SqliteStore [^Connection conn]
  Store
  (revisions [_ entry]
    (read-rows (query conn "SELECT record, sig FROM revisions WHERE entry = ? ORDER BY seq" entry)))
  (observations [_ entry]
    (read-rows (query conn "SELECT record, sig FROM observations WHERE entry = ?" entry)))
  (links [_ entry]
    (read-rows (query conn "SELECT record, sig FROM links WHERE source = ? OR target = ?" entry entry)))
  (entries [_]
    (map :record (query conn "SELECT DISTINCT entry FROM revisions")))
  (page-versions [_]
    (read-rows (query conn "SELECT record, sig FROM pages")))
  (append! [_ kind rec sig]
    (let [hash (record/hash-of rec)
          text (kp/edn-str rec)]
      (try
        (case kind
          :revision (execute! conn "INSERT INTO revisions (hash, entry, seq, record, sig) VALUES (?, ?, ?, ?, ?)"
                              hash (:entry rec) (:seq rec) text sig)
          :observation (execute! conn "INSERT INTO observations (hash, entry, revision, record, sig) VALUES (?, ?, ?, ?, ?)"
                                 hash (:entry rec) (:revision rec) text sig)
          :link (execute! conn "INSERT INTO links (hash, source, target, record, sig) VALUES (?, ?, ?, ?, ?)"
                          hash (:source rec) (:target rec) text sig)
          :page (execute! conn "INSERT INTO pages (hash, name, record, sig) VALUES (?, ?, ?, ?)"
                          hash (:name rec) text sig))
        (catch java.sql.SQLException e
          (if (and (= :revision kind) (re-find #"UNIQUE" (str (ex-message e))))
            (seq-taken! rec)
            (throw e))))
      hash)))

(defn sqlite
  "The SQLite store at `path`, created when missing."
  [path]
  (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
    (with-open [st (.createStatement conn)]
      (doseq [ddl schema] (.execute st ddl)))
    (->SqliteStore conn)))

;; -------------------------------------------------------------- flat files
;;
;;   <dir>/entries/<entry>/revisions/<seq>.edn   one revision, :seq zero-padded
;;   <dir>/entries/<entry>/observations/<hash>.edn
;;   <dir>/links/<hash>.edn
;;   <dir>/pages/<hash>.edn
;;
;; A signature is the file beside its record with .sig appended. Files are
;; created with CREATE_NEW, so a revision whose :seq file exists is refused
;; by the filesystem itself, whichever process wrote it first.

(defn- create-new!
  [^File f ^bytes bytes]
  (io/make-parents f)
  (Files/write (.toPath f) bytes ^"[Ljava.nio.file.OpenOption;"
               (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE])))

(defn- read-file
  [^File f]
  (let [sig (io/file (str f ".sig"))]
    (assoc (edn/read-string (slurp f))
           :sig (when (.isFile sig) (Files/readAllBytes (.toPath sig))))))

(defn- edn-files
  [^File dir]
  (->> (.listFiles dir)
       (filter #(and (.isFile ^File %) (str/ends-with? (.getName ^File %) ".edn")))
       (sort-by #(.getName ^File %))))

(defn- file-name
  [kind rec hash]
  (if (= :revision kind) (format "%08d.edn" (:seq rec)) (str hash ".edn")))

(defrecord FileStore [^File dir]
  Store
  (revisions [_ entry]
    (mapv read-file (edn-files (io/file dir "entries" entry "revisions"))))
  (observations [_ entry]
    (mapv read-file (edn-files (io/file dir "entries" entry "observations"))))
  (links [_ entry]
    (->> (edn-files (io/file dir "links"))
         (map read-file)
         (filterv #(or (= entry (:source %)) (= entry (:target %))))))
  (entries [_]
    (->> (.listFiles (io/file dir "entries"))
         (filter #(.isDirectory ^File %))
         (map #(.getName ^File %))))
  (page-versions [_]
    (mapv read-file (edn-files (io/file dir "pages"))))
  (append! [_ kind rec sig]
    (let [hash (record/hash-of rec)
          home (case kind
                 :revision (io/file dir "entries" (:entry rec) "revisions")
                 :observation (io/file dir "entries" (:entry rec) "observations")
                 :link (io/file dir "links")
                 :page (io/file dir "pages"))
          f (io/file home (file-name kind rec hash))]
      (try
        (create-new! f (.getBytes ^String (kp/edn-str rec) "UTF-8"))
        (catch FileAlreadyExistsException e
          (if (= :revision kind) (seq-taken! rec) (throw e))))
      (when sig (create-new! (io/file (str f ".sig")) sig))
      hash)))

(defn files
  "The flat-file store in directory `dir`, created when missing."
  [dir]
  (let [d (io/file dir)]
    (.mkdirs d)
    (->FileStore d)))

(defn open
  "{:path \"notes.db\"} opens SQLite, {:dir \"notes/\"} a flat-file store."
  [{:keys [path dir]}]
  (if dir (files dir) (sqlite path)))
