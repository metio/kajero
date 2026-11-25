;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.usage
  "How often searches return an entry and how often it is opened, counted
   per entry and UTC day beside the notebook and never in it.

   The counts are a measurement of one server: unsigned, never part of the
   record set, and safe to delete. Per entry and day there are three:

     :returned             search results that included the entry
     :opened               reads of the entry
     :opened-after-return  reads that followed a search returning it in the
                           same MCP session

   No selector, query text, result position or actor is kept. A session is
   an id this server minted, held in memory for `session-ttl` after its last
   search, with at most `max-sessions` sessions and `max-returned` entries
   each, so a client can not grow the server's memory by searching or by
   naming sessions. Daily rows older than `retention-days` fold into one
   total per entry, so the counts stay bounded by the number of entries.

   Recording never fails a read: a count that can not be written is logged
   on stderr and dropped, and the search or read answers as it would have.
   Reading the counts for a report does fail, so a report never mistakes an
   unreadable usage file for one in which nothing was returned."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.io File)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.security SecureRandom)
           (java.sql Connection PreparedStatement ResultSet)
           (java.time Duration Instant LocalDate ZoneOffset)
           (java.util HexFormat)))

(def retention-days
  "Daily rows older than this many days fold into one total per entry."
  180)

(def ^Duration session-ttl
  "How long a session is remembered after its last search."
  (Duration/ofMinutes 30))

(def max-sessions
  "The most sessions held at once; the least recently searching one makes
   room for a new one."
  1024)

(def max-returned
  "The most entries one session remembers as returned."
  10000)

(def ^:private counters [:returned :opened :opened-after-return])

(defn day
  "The UTC day of an instant, as YYYY-MM-DD."
  [^Instant at]
  (str (LocalDate/ofInstant at ZoneOffset/UTC)))

(defn- days-before
  [d n]
  (str (.minusDays (LocalDate/parse d) (long n))))

(defn log!
  "A line on stderr, which the service hands to the journal."
  [& parts]
  (binding [*out* *err*] (apply println parts)))

(defprotocol Log
  (add! [log day counts]
    "Adds `counts`, {entry {:returned n :opened n :opened-after-return n}},
     to the rows of `day`, and records `day` as the start of counting when
     nothing was counted before.")
  (fold! [log before]
    "Folds the daily rows of days before `before` into the per-entry
     totals, and records `before` as the day the totals reach up to.")
  (snapshot [log]
    "Every count: {:since day :folded-through day
                   :days {entry {day counts}} :folded {entry counts}}."))

(defn- plus
  [a b]
  (merge-with + (zipmap counters (repeat 0)) (select-keys a counters) (select-keys b counters)))

;; ------------------------------------------------------------------ SQLite

(def ^:private ddl
  ["CREATE TABLE IF NOT EXISTS usage (
      entry TEXT NOT NULL, day TEXT NOT NULL,
      returned INTEGER NOT NULL DEFAULT 0, opened INTEGER NOT NULL DEFAULT 0,
      opened_after_return INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY (entry, day))"
   "CREATE TABLE IF NOT EXISTS usage_folded (
      entry TEXT PRIMARY KEY,
      returned INTEGER NOT NULL DEFAULT 0, opened INTEGER NOT NULL DEFAULT 0,
      opened_after_return INTEGER NOT NULL DEFAULT 0)"
   "CREATE TABLE IF NOT EXISTS usage_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)"])

(defn- statement
  ^PreparedStatement [^Connection conn sql params]
  (let [ps (.prepareStatement conn sql)]
    (doseq [[i p] (map-indexed vector params)]
      (.setObject ps (int (inc i)) p))
    ps))

(defn- execute!
  [^Connection conn sql & params]
  (with-open [ps (statement conn sql params)]
    (.executeUpdate ps)))

(defn- rows
  [^Connection conn sql]
  (with-open [ps (statement conn sql [])
              ^ResultSet rs (.executeQuery ps)]
    (let [n (.getColumnCount (.getMetaData rs))]
      (loop [acc []]
        (if (.next rs)
          (recur (conj acc (mapv #(.getObject rs (int (inc %))) (range n))))
          acc)))))

(defn- transact!
  "Runs `f` in one transaction on `conn`, rolled back when it throws."
  [^Connection conn f]
  (locking conn
    (.setAutoCommit conn false)
    (try
      (let [result (f)]
        (.commit conn)
        result)
      (catch Exception e
        (.rollback conn)
        (throw e))
      (finally (.setAutoCommit conn true)))))

(defn- ensure-tables!
  [^Connection conn created]
  (when-not @created
    (locking conn (doseq [s ddl] (execute! conn s)))
    (reset! created true)))

(defrecord SqliteLog [^Connection conn created]
  Log
  (add! [_ d counts]
    (ensure-tables! conn created)
    (transact! conn
               (fn []
                 (execute! conn "INSERT OR IGNORE INTO usage_meta (key, value) VALUES ('since', ?)" d)
                 (doseq [[entry {:keys [returned opened opened-after-return]}] counts]
                   (execute! conn "INSERT INTO usage (entry, day, returned, opened, opened_after_return)
                                   VALUES (?, ?, ?, ?, ?)
                                   ON CONFLICT (entry, day) DO UPDATE SET
                                     returned = returned + excluded.returned,
                                     opened = opened + excluded.opened,
                                     opened_after_return = opened_after_return + excluded.opened_after_return"
                             entry d (long (or returned 0)) (long (or opened 0)) (long (or opened-after-return 0)))))))
  (fold! [_ before]
    (ensure-tables! conn created)
    (transact! conn
               (fn []
                 (execute! conn "INSERT INTO usage_folded (entry, returned, opened, opened_after_return)
                                   SELECT entry, SUM(returned), SUM(opened), SUM(opened_after_return)
                                   FROM usage WHERE day < ? GROUP BY entry
                                   ON CONFLICT (entry) DO UPDATE SET
                                     returned = returned + excluded.returned,
                                     opened = opened + excluded.opened,
                                     opened_after_return = opened_after_return + excluded.opened_after_return"
                           before)
                 (execute! conn "DELETE FROM usage WHERE day < ?" before)
                 (execute! conn "INSERT INTO usage_meta (key, value) VALUES ('folded-through', ?)
                                   ON CONFLICT (key) DO UPDATE SET value = MAX(value, excluded.value)"
                           before))))
  (snapshot [_]
    (ensure-tables! conn created)
    (locking conn
      (let [meta (into {} (rows conn "SELECT key, value FROM usage_meta"))
            counts (fn [[r o a]] {:returned r :opened o :opened-after-return a})]
        {:since (get meta "since")
         :folded-through (get meta "folded-through")
         :days (reduce (fn [acc [entry d & cs]] (assoc-in acc [entry d] (counts cs)))
                       {}
                       (rows conn "SELECT entry, day, returned, opened, opened_after_return FROM usage"))
         :folded (into {} (map (fn [[entry & cs]] [entry (counts cs)]))
                       (rows conn "SELECT entry, returned, opened, opened_after_return FROM usage_folded"))}))))

(defn sqlite-log
  "The counts in the `usage` tables of the notebook database behind `conn`,
   outside the record tables. The tables are made by the first count."
  [conn]
  (->SqliteLog conn (atom false)))

;; -------------------------------------------------------------- flat files

(def ^:private empty-counts {:since nil :folded-through nil :days {} :folded {}})

(defn- read-counts
  "The counts in `f`, or none when it does not exist. A file that does not
   read as counts throws, so it is never overwritten by an empty start."
  [^File f]
  (if-not (.exists f)
    empty-counts
    (let [c (edn/read-string (slurp f))]
      (when-not (and (map? c) (map? (:days c)) (map? (:folded c)))
        (throw (ex-info (str "the usage file " f " does not hold usage counts") {:reason :kajero/usage-unreadable})))
      (merge empty-counts c))))

(defn- write-counts!
  "Replaces `f` with `counts` in one rename, so a reader sees the old file
   or the new one and never a part of it."
  [^File f counts]
  (let [dir (.getParentFile (.getAbsoluteFile f))
        tmp (Files/createTempFile (.toPath dir) (str "." (.getName f)) ".tmp" (make-array FileAttribute 0))]
    (try
      (spit (.toFile tmp) (pr-str counts))
      (Files/move tmp (.toPath f) ^"[Ljava.nio.file.CopyOption;"
                  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (finally (Files/deleteIfExists tmp)))))

(defn- add-counts
  [c d counts]
  (reduce-kv (fn [acc entry cs] (update-in acc [:days entry d] plus cs))
             (update c :since #(or % d))
             counts))

(defn- fold-counts
  [c before]
  (let [old (for [[entry ds] (:days c) [d cs] ds :when (neg? (compare d before))] [entry d cs])]
    (if (empty? old)
      c
      (-> (reduce (fn [acc [entry d cs]]
                    (-> acc
                        (update-in [:folded entry] plus cs)
                        (update-in [:days entry] dissoc d)))
                  c old)
          (update :days #(into {} (remove (comp empty? val)) %))
          (update :folded-through #(if (and % (pos? (compare % before))) % before))))))

(defrecord FileLog [^File file]
  Log
  (add! [this d counts]
    (locking this
      (write-counts! file (add-counts (read-counts file) d counts))))
  (fold! [this before]
    (locking this
      (when (.exists file)
        (let [c (read-counts file)
              folded (fold-counts c before)]
          (when-not (identical? c folded)
            (write-counts! file folded))))))
  (snapshot [this]
    (locking this (read-counts file))))

(defn file-log
  "The counts in the EDN file `path`, made by the first count."
  [path]
  (->FileLog (io/file path)))

(defn beside
  "The usage file of the flat-file notebook in `dir`: `<dir>.usage.edn`
   next to the directory, so nothing that copies the records copies it."
  ^File [dir]
  (let [d (.getAbsoluteFile (io/file dir))]
    (io/file (.getParentFile d) (str (.getName d) ".usage.edn"))))

;; ---------------------------------------------------------------- recorder

(defn recorder
  "Counts into `log`, with the sessions of this server in memory."
  [log]
  {:log log :sessions (atom {}) :folded-day (atom nil)})

(defn- expired?
  [{:keys [touched]} ^Instant at]
  (.isBefore ^Instant (.plus ^Instant touched session-ttl) at))

(defn- make-room
  "The sessions without expired ones, and without the least recently
   searching ones beyond what leaves room for one more."
  [sessions at]
  (let [live (into {} (remove (fn [[_ s]] (expired? s at))) sessions)]
    (if (< (count live) max-sessions)
      live
      (into {} (take (dec max-sessions)) (sort-by (comp :touched val) #(compare %2 %1) live)))))

(defn new-session-id
  "A session id nobody can guess."
  []
  (.formatHex (HexFormat/of) (let [b (byte-array 16)] (.nextBytes (SecureRandom.) b) b)))

(defn start-session!
  "Remembers a new session and returns its id."
  [{:keys [sessions]} at]
  (let [id (new-session-id)]
    (swap! sessions #(assoc (make-room % at) id {:touched at :returned #{}}))
    id))

(defn live-session
  "`id` when it names a session this server remembers, else nil, so a
   client can not bring a session id of its own."
  [{:keys [sessions]} id at]
  (when-let [s (and id sessions (get @sessions id))]
    (when-not (expired? s at) id)))

(defn- track!
  [{:keys [sessions]} session entries at]
  (when session
    (swap! sessions
           (fn [ss]
             (let [s (get ss session)
                   s (if (or (nil? s) (expired? s at)) {:returned #{}} s)
                   ss (if (contains? ss session) ss (make-room ss at))]
               (assoc ss session
                      (-> s
                          (assoc :touched at)
                          (update :returned #(into % (take (max 0 (- max-returned (count %)))) entries)))))))))

(defn- returned-in?
  [{:keys [sessions]} session entry at]
  (boolean (when-let [s (and session (get @sessions session))]
             (and (not (expired? s at)) (contains? (:returned s) entry)))))

(defn- count!
  [{:keys [log folded-day]} at counts]
  (let [d (day at)]
    (when-not (= d @folded-day)
      (fold! log (days-before d retention-days))
      (reset! folded-day d))
    (add! log d counts)))

(defn- guarded
  "Calls `f`; a failure is logged and answers nil instead of throwing."
  [what f]
  (try (f)
       (catch Exception e
         (log! "kajero: usage not recorded for" what "-" (or (ex-message e) (.getName (class e))))
         nil)))

(defn searched!
  "Counts that a search returned `entries`, and remembers them for
   `session` when there is one. nil."
  [rec session entries at]
  (when (and rec (seq entries))
    (track! rec session entries at)
    (guarded "a search" #(count! rec at (zipmap entries (repeat {:returned 1}))))
    nil))

(defn opened!
  "Counts that `entry` was read, and that the read followed a search of
   the same session that returned it. nil."
  [rec session entry at]
  (when (and rec entry)
    (let [after? (returned-in? rec session entry at)]
      (guarded "a read" #(count! rec at {entry (cond-> {:opened 1} after? (assoc :opened-after-return 1))}))
      nil)))

(defn counts
  "Every count of the recorder - see `snapshot` - or the empty counts when
   there is no recorder. Throws ex-info {:reason :kajero/usage-unreadable}
   when the counts can not be read."
  [rec]
  (if-not rec
    empty-counts
    (try (snapshot (:log rec))
         (catch Exception e
           (throw (ex-info (str "the usage counts can not be read: " (ex-message e))
                           {:reason :kajero/usage-unreadable} e))))))

(defn of-entry
  "The counts of one entry, for a selector: {:since :folded-through :days
   {day counts} :folded counts}."
  [all entry]
  {:since (:since all)
   :folded-through (:folded-through all)
   :days (get-in all [:days entry] {})
   :folded (get-in all [:folded entry])})

(defn returned-since
  "How many times searches returned the entry on day `since` or later. The
   folded totals count when `since` precedes the day they reach up to,
   because they may hold days after it."
  [{:keys [days folded folded-through]} since]
  (+ (reduce + 0 (for [[d cs] days :when (or (nil? since) (not (neg? (compare d since))))] (:returned cs 0)))
     (if (and folded (or (nil? since) (and folded-through (neg? (compare since folded-through)))))
       (:returned folded 0)
       0)))

(defn total
  "The sum of counter `k` over every day and the folded total."
  [{:keys [days folded]} k]
  (+ (reduce + 0 (map #(get % k 0) (vals days))) (get folded k 0)))
