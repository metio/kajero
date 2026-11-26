;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.index
  "An in-memory index in front of any store, answering the reads a search
   makes for every entry from memory.

   Records are written once and never change, so a record read once stays
   right. The index holds, per entry, its current revision, the actors and
   evidence of all its revisions, and its observations; every link, by
   source and by target; and every page version. Earlier revisions are not
   held: the history of one entry is read from the store when asked for.

   Writes go through to the store, which alone decides whether they land:
   a revision whose :seq is taken is refused there, by its atomic create or
   its unique key, whatever the index believed. A write that landed is added
   to the index at once, so a writer always reads its own writes.

   Records other processes append - the command line, a git pull, a sync -
   are picked up from the store's change feed. A read starts a refresh when
   the last one is older than the refresh interval and no other thread is
   refreshing; meanwhile, reads answer from the records already held. A
   refused stale write refreshes before it is reported, so the current
   revision it names is the store's."
  (:require [kajero.derived :as derived]
            [kajero.store :as store])
  (:import (java.util.concurrent.locks ReentrantLock)))

(def default-refresh-ms
  "How old the index may get before a read refreshes it. Listing a store of
   1300 entries for changes takes 20-30 ms, so a refresh at most once a
   second keeps that out of almost every request."
  1000)

(def ^:private empty-state
  {:entries {} :links {} :link-hashes #{} :pages [] :page-hashes #{} :cursor nil :refreshed nil
   :generation 0})

(def ^:private empty-entry
  {:head nil :authorship {:actors #{} :evidence []} :evidence-by-seq (sorted-map)
   :observations [] :observation-hashes #{}})

(defn- signed-copy?
  "Whether `rec` is `held` read again with the signature `held` lacked."
  [held rec]
  (and (nil? (:sig held)) (some? (:sig rec))))

(defn- newer-head?
  [head rec]
  (or (nil? head)
      (> (long (:seq rec)) (long (:seq head)))
      (and (= (:seq rec) (:seq head)) (signed-copy? head rec))))

(defn- replace-signed
  "`records` with the one whose hash is `h` replaced by `rec` when `rec`
   carries the signature it lacked."
  [records h rec]
  (mapv #(if (and (signed-copy? % rec) (= h (derived/hash-of %))) rec %) records))

(defn- add-revision
  [state rec]
  (update-in state [:entries (:entry rec)]
             (fn [e]
               (let [e (or e empty-entry)
                     by-seq (assoc (:evidence-by-seq e) (:seq rec) (:evidence rec))]
                 (-> e
                     (cond-> (newer-head? (:head e) rec) (assoc :head rec))
                     (update-in [:authorship :actors] conj (:actor rec))
                     (assoc :evidence-by-seq by-seq)
                     (assoc-in [:authorship :evidence] (store/ordered-evidence (vals by-seq))))))))

(defn- add-observation
  [state rec]
  (let [h (derived/hash-of rec)]
    (update-in state [:entries (:entry rec)]
               (fn [e]
                 (let [e (or e empty-entry)]
                   (if (contains? (:observation-hashes e) h)
                     (update e :observations replace-signed h rec)
                     (-> e
                         (update :observations conj rec)
                         (update :observation-hashes (fnil conj #{}) h))))))))

(defn- add-link
  [state rec]
  (let [h (derived/hash-of rec)]
    (if (contains? (:link-hashes state) h)
      (reduce (fn [s id] (update-in s [:links id] replace-signed h rec))
              state
              (distinct [(:source rec) (:target rec)]))
      (reduce (fn [s id] (update-in s [:links id] (fnil conj []) rec))
              (update state :link-hashes (fnil conj #{}) h)
              (distinct [(:source rec) (:target rec)])))))

(defn- add-page
  [state rec]
  (let [h (derived/hash-of rec)]
    (if (contains? (:page-hashes state) h)
      (update state :pages replace-signed h rec)
      (-> state (update :pages (fnil conj []) rec) (update :page-hashes (fnil conj #{}) h)))))

(defn add-record
  "`state` with the record `rec` of `kind` in it. Adding a record the
   state already holds changes nothing."
  [state [kind rec]]
  (case kind
    :revision (add-revision state rec)
    :observation (add-observation state rec)
    :link (add-link state rec)
    :page (add-page state rec)))

(defn- refresh-held!
  "Reads the store's changes into the index; the caller holds the lock."
  [{:keys [inner state]}]
  (let [{:keys [cursor records]} (store/changes inner (:cursor @state))]
    (swap! state (fn [s] (-> (reduce add-record s records)
                             (cond-> (seq records) (update :generation inc))
                             (assoc :cursor cursor :refreshed (System/nanoTime)))))
    nil))

(defn refresh!
  "Reads what was appended to the store since the last refresh, waiting
   for a refresh another thread is running."
  [{:keys [^ReentrantLock lock] :as idx}]
  (.lock lock)
  (try (refresh-held! idx)
       (finally (.unlock lock))))

(defn- fresh!
  "Refreshes when the index is older than its interval and no other thread
   is refreshing it already."
  [{:keys [state ^ReentrantLock lock refresh-ms] :as idx}]
  (let [refreshed (:refreshed @state)]
    (when (and (or (nil? refreshed) (< (* 1000000 (long refresh-ms)) (- (System/nanoTime) (long refreshed))))
               (.tryLock lock))
      (try (refresh-held! idx)
           (finally (.unlock lock))))))

(defn- held
  [idx]
  (fresh! idx)
  @(:state idx))

(defrecord Indexed [inner state lock refresh-ms on-append]
  store/Store
  (revisions [_ entry] (store/revisions inner entry))
  (observations [this entry] (get-in (held this) [:entries entry :observations] []))
  (links [this entry] (get-in (held this) [:links entry] []))
  (entries [this] (into [] (keep (fn [[id e]] (when (:head e) id))) (:entries (held this))))
  (page-versions [this] (:pages (held this)))
  (append! [this kind rec sig]
    (let [h (try (store/append! inner kind rec sig)
                 (catch clojure.lang.ExceptionInfo e
                   (when (= :kajero/stale-base (:reason (ex-data e)))
                     (refresh! this))
                   (throw e)))]
      (let [held (assoc rec :sig sig)]
        (swap! state #(-> % (add-record [kind held]) (update :generation inc)))
        (when on-append
          ;; the record has landed, so a failure here must not report the
          ;; write as failed; what it would have computed is computed on read
          (try (on-append kind held)
               (catch Exception e
                 (binding [*out* *err*]
                   (println "kajero: values of a written record not computed -" (ex-message e)))))))
      h))
  store/Generation
  (generation [this] (:generation (held this)))
  store/Summary
  (head [this entry]
    (or (get-in (held this) [:entries entry :head])
        ;; An entry the index does not know may have been written by another
        ;; process since the last refresh. Asking the store about the one
        ;; entry is cheap, and only a hit pays for a refresh.
        (when (store/head inner entry)
          (refresh! this)
          (get-in @state [:entries entry :head]))))
  (authorship [this entry]
    (get-in (held this) [:entries entry :authorship] {:actors #{} :evidence #{}})))

(defn indexed
  "The store `inner` behind an index, loaded with every record it holds.
   :refresh-ms is how old the index may get before a read refreshes it;
   :on-append, a function of a kind and a record, runs after each write
   through the index landed, with the record as the index holds it."
  [inner & [{:keys [refresh-ms on-append]}]]
  (doto (->Indexed inner (atom empty-state) (ReentrantLock.) (long (or refresh-ms default-refresh-ms)) on-append)
    refresh!))

(defn inner
  "The store behind `s`, or `s` itself when it has no index."
  [s]
  (if (instance? Indexed s) (:inner s) s))
