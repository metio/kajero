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

(defprotocol Feed
  (changes [store cursor]
    "The records appended since `cursor`, by this process or any other, as
     {:cursor c :records [[kind record] ...]}. A nil cursor asks for every
     record; pass the returned :cursor to the next call. Records come in
     the order they were appended where the store knows it, and a record
     may be reported again, so a reader keeps each one once by its hash."))

(defprotocol Summary
  (head [store entry] "The entry's current revision, or nil.")
  (authorship [store entry]
    "{:actors #{...} :evidence [...]}: the actors of every revision of the
     entry, and the evidence items of every revision, each once, in the
     order the revisions and their evidence name them."))

(defn ordered-evidence
  "The items of `evidence-lists`, given oldest revision first, each once
   in the order they first appear."
  [evidence-lists]
  (into [] (comp cat (distinct)) evidence-lists))

(defn summarize
  "The authorship of `revisions`, oldest first, as Summary/authorship
   answers it."
  [revisions]
  {:actors (into #{} (map :actor) revisions)
   :evidence (ordered-evidence (map :evidence revisions))})

(extend-protocol Summary
  Object
  (head [store entry] (peek (revisions store entry)))
  (authorship [store entry] (summarize (revisions store entry))))

(defprotocol Generation
  (generation [store]
    "A number that grows whenever the records the store answers with
     change, so a value derived from all of them can be kept until it
     does; nil for a store that does not count its changes."))

(extend-protocol Generation
  Object
  (generation [_] nil))

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

;; One connection serves every thread of the process. Each statement holds
;; the connection's monitor while it runs, the same monitor kajero.usage
;; holds for a transaction, so a record is never written inside somebody
;; else's transaction and no two statements interleave on the connection.

(defn- execute!
  [^Connection conn sql & params]
  (locking conn
    (with-open [ps (bind! (.prepareStatement conn sql) params)]
      (.executeUpdate ps))))

(defn- query
  [^Connection conn sql & params]
  (locking conn
    (with-open [ps (bind! (.prepareStatement conn sql) params)
                ^ResultSet rs (.executeQuery ps)]
      (loop [rows []]
        (if (.next rs)
          (recur (conj rows {:record (.getString rs 1) :sig (when (> (.getColumnCount (.getMetaData rs)) 1) (.getBytes rs 2))}))
          rows)))))

(defn- query-since
  "Rows of `table` whose rowid is above `after`, as [rowid record sig]. Rows
   are only ever inserted, so the rowid grows with every append."
  [^Connection conn table after]
  (locking conn
    (with-open [ps (bind! (.prepareStatement conn (str "SELECT rowid, record, sig FROM " table
                                                       " WHERE rowid > ? ORDER BY rowid"))
                          [after])
                ^ResultSet rs (.executeQuery ps)]
      (loop [rows []]
        (if (.next rs)
          (recur (conj rows [(.getLong rs 1) (.getString rs 2) (.getBytes rs 3)]))
          rows)))))

(def ^:private tables
  {:revision "revisions" :observation "observations" :link "links" :page "pages"})

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
      hash))
  Feed
  (changes [_ cursor]
    (reduce (fn [acc [kind table]]
              (let [rows (query-since conn table (get cursor kind 0))]
                (-> acc
                    (update :records into (map (fn [[_ record sig]] [kind (assoc (edn/read-string record) :sig sig)])) rows)
                    (assoc-in [:cursor kind] (if (seq rows) (first (peek rows)) (get cursor kind 0))))))
            {:cursor {} :records []}
            tables)))

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

(def ^:private sig-grace-ms
  "How long a record file read without a signature beside it is watched
   for one. A writer creates the record and then its .sig, and a checkout
   may write them in either order, so a reader can find the record first."
  60000)

(defn- names
  [^File dir]
  (sort (filter #(str/ends-with? % ".edn") (.list dir))))

(defn- record-files
  "[kind path] of every record file below `dir`, the path relative to it."
  [^File dir]
  (concat (for [e (sort (or (.list (io/file dir "entries")) (make-array String 0)))
                [kind sub] [[:revision "revisions"] [:observation "observations"]]
                :let [d (io/file dir "entries" e sub)]
                :when (.isDirectory d)
                n (names d)]
            [kind (str "entries/" e "/" sub "/" n)])
          (for [[kind sub] [[:link "links"] [:page "pages"]]
                :let [d (io/file dir sub)]
                :when (.isDirectory d)
                n (names d)]
            [kind (str sub "/" n)])))

(defn- file-changes
  "The records below `dir` that `cursor` does not hold yet, and the cursor
   holding them. The cursor is {:known #{path} :unsigned {path [kind
   first-seen-ms]}}. A record read without its signature is read again once
   the signature appears, for sig-grace-ms after it was first seen; a reader
   keeps the copy with the signature."
  [^File dir cursor now-ms]
  (let [{:keys [known unsigned] :or {known #{} unsigned {}}} cursor
        read-new (fn [acc [kind rel]]
                   (let [f (io/file dir rel)]
                     (if (or (contains? known rel) (not (.isFile f)))
                       acc
                       (let [rec (read-file f)]
                         (cond-> (-> acc
                                     (update :records conj [kind rec])
                                     (update-in [:cursor :known] conj rel))
                           (nil? (:sig rec)) (assoc-in [:cursor :unsigned rel] [kind now-ms]))))))
        recheck (fn [acc [rel [kind seen]]]
                  (let [f (io/file dir rel)]
                    (cond
                      (.isFile (io/file (str f ".sig")))
                      (-> acc
                          (update :records conj [kind (read-file f)])
                          (update-in [:cursor :unsigned] dissoc rel))

                      (< (long sig-grace-ms) (- (long now-ms) (long seen)))
                      (update-in acc [:cursor :unsigned] dissoc rel)

                      :else acc)))
        start {:records [] :cursor {:known known :unsigned unsigned}}]
    (reduce read-new (reduce recheck start unsigned) (record-files dir))))

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
      hash))
  Feed
  (changes [_ cursor]
    (file-changes dir cursor (System/currentTimeMillis))))

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
