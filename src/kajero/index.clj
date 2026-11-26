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
   revision it names is the store's.

   Given a function that turns records into Merkle leaves, the index also
   keeps a kajero.merkle tree over every record it has read, earlier
   revisions included. Computing a leaf judges a signature, so loading the
   index computes none: the tree starts out building, and every record the
   index takes in while it builds is queued in the same swap that adds the
   record. build-tree! computes the leaves of the queue until it is empty
   and marks the tree ready in the swap that finds it empty, so no record
   the index holds is ever left out of a ready tree. Once the tree is ready,
   a new record's leaf is added in the same swap that adds the record and
   raises the generation. The leaves are computed before the swap, so a
   swap that retries never judges a signature twice; a record whose leaf
   was not computed because the tree was still building when the writer
   looked has its leaf computed and added right after its swap.

   A leaf replaces the leaf of the same hash, except that a copy read
   without its signature never replaces the signed one, as the index keeps
   the signed copy of a record. Adding a record twice therefore changes
   nothing, and the order the leaves arrive in does not change the tree."
  (:require [kajero.derived :as derived]
            [kajero.merkle :as merkle]
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

(defn- merge-leaves
  "`tree` with `leaves` in it, except a leaf without a signature where the
   tree holds the signed copy of the same record: the tree keeps the copy
   the index keeps (see signed-copy?), whichever was read last."
  [tree leaves]
  (merkle/add-all tree (remove #(and (nil? (:sig %)) (:sig (merkle/lookup tree (:hash %)))) leaves)))

(defn- add-leaves
  "`state` with the leaves `computed` - {:actors digest :leaves [...]}, see
   `indexed` - in its tree, remembering the actors file they were judged
   under."
  [state computed]
  (cond-> state
    computed (update :tree (fn [{:keys [merkle actors] :as t}]
                             (assoc t
                                    :merkle (merge-leaves (or merkle merkle/empty-tree) (:leaves computed))
                                    :actors (conj (or actors #{}) (:actors computed)))))))

(defn- leaves-of
  "The leaves of `records`, [[kind record] ...], computed by the index's
   leaf function, or nil when it keeps no tree or there are none."
  [{:keys [leaves]} records]
  (when (and leaves (seq records))
    (leaves records)))

(defn- tree-ready? [s] (boolean (get-in s [:tree :ready?])))

(defn- leaves-when-ready
  "The leaves of `records` when the index keeps a ready tree, else nil:
   while the tree builds, records are queued instead. ::failed when the
   leaf function threw."
  [{:keys [state] :as idx} records]
  (when (tree-ready? @state)
    (try (leaves-of idx records)
         (catch Exception e
           (binding [*out* *err*]
             (println "kajero: the Merkle leaves of new records not computed -" (ex-message e)))
           ::failed))))

(defn- into-tree
  "`s` with `records` in its tree: queued while the tree builds, their
   leaves `computed` added once it is ready. A ready tree without computed
   leaves is left as it is, and the caller adds them after its swap."
  [s records computed]
  (cond
    (or (nil? (:tree s)) (empty? records)) s
    (not (tree-ready? s)) (update-in s [:tree :pending] into records)
    (= ::failed computed) (assoc-in s [:tree :stale?] true)
    computed (add-leaves s computed)
    :else s))

(defn- add-late-leaves!
  "Adds the leaves of `records` to a tree that became ready between the
   writer's look at it and its swap, so they were neither computed nor
   queued. `after` is the state that swap left."
  [{:keys [state] :as idx} records computed after]
  (when (and (:tree after) (seq records) (nil? computed) (tree-ready? after))
    (let [late (try (leaves-of idx records)
                    (catch Exception e
                      (binding [*out* *err*]
                        (println "kajero: the Merkle leaves of new records not computed -" (ex-message e)))
                      ::failed))]
      (swap! state #(if (= ::failed late) (assoc-in % [:tree :stale?] true) (add-leaves % late))))))

(defn- refresh-held!
  "Reads the store's changes into the index; the caller holds the lock."
  [{:keys [inner state] :as idx}]
  (let [{:keys [cursor records]} (store/changes inner (:cursor @state))
        computed (leaves-when-ready idx records)
        [_ after] (swap-vals! state (fn [s] (-> (reduce add-record s records)
                                                (into-tree records computed)
                                                (cond-> (seq records) (update :generation inc))
                                                (assoc :cursor cursor :refreshed (System/nanoTime)))))]
    (add-late-leaves! idx records computed after)
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

(defrecord Indexed [inner state lock refresh-ms on-append leaves ^ReentrantLock tree-lock]
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
      (let [held (assoc rec :sig sig)
            records [[kind held]]
            computed (leaves-when-ready this records)
            [_ after] (swap-vals! state #(-> %
                                             (add-record [kind held])
                                             (into-tree records computed)
                                             (update :generation inc)))]
        (add-late-leaves! this records computed after)
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
   through the index landed, with the record as the index holds it.

   :leaves, a function of records [[kind record] ...] returning {:actors
   digest :leaves [leaf ...]} - one kajero.merkle leaf per record, judged
   under the actors file of that digest - makes the index keep a Merkle
   tree over every record; without it the index keeps none. The tree is
   building until build-tree! has run: loading queues the records instead
   of judging them."
  [inner & [{:keys [refresh-ms on-append leaves]}]]
  (doto (->Indexed inner
                   (atom (cond-> empty-state
                           leaves (assoc :tree {:merkle merkle/empty-tree :actors #{} :ready? false :pending []})))
                   (ReentrantLock.) (long (or refresh-ms default-refresh-ms)) on-append leaves (ReentrantLock.))
    refresh!))

(defn tree
  "{:merkle tree :actors #{digest} :stale? bool :ready? bool} of the index:
   the Merkle tree over every record it read, the digests of the actors
   files its verdicts were judged under, whether a leaf failed to be
   computed, and whether the tree is complete. A tree that is not :ready?
   lacks the records still queued, so nothing may be judged from it but
   that it is building; :failed names why the last build failed. nil when
   the index keeps no tree or `s` is no index."
  [s]
  (when (instance? Indexed s)
    (dissoc (:tree (held s)) :pending)))

(defn- drain!
  "Adds the leaves of every queued record to `acc` until the queue is
   empty, and in the swap that finds it empty makes `acc` the index's
   ready tree."
  [{:keys [state] :as idx} acc]
  (loop [acc acc]
    (let [[before] (swap-vals! state
                               (fn [s]
                                 (if (empty? (get-in s [:tree :pending]))
                                   (-> s
                                       (assoc :tree (assoc acc :ready? true :pending []))
                                       (update :generation inc))
                                   (assoc-in s [:tree :pending] []))))
          queued (get-in before [:tree :pending])]
      (if (empty? queued)
        acc
        (let [{:keys [actors leaves]} (leaves-of idx queued)]
          (recur (-> acc
                     (update :merkle merge-leaves leaves)
                     (update :actors conj actors))))))))

(defn build-tree!
  "Completes the index's tree: computes the leaves of every record queued
   while it was building, and marks it ready. With :reread? the tree is
   built again from every record of the store, each judged now under the
   actors file as it is now - for an actors file that changed, which
   changes verdicts; the tree reads as building meanwhile. One build runs
   at a time. A build that throws leaves the tree building, with :failed
   naming why, and the next build starts over from the store. Returns the
   tree as `tree` answers it, or nil when the index keeps none."
  [{:keys [inner state ^ReentrantLock tree-lock] :as idx} & [{:keys [reread?]}]]
  (when (:leaves idx)
    (.lock tree-lock)
    (try
      (let [reread? (or reread? (some? (get-in @state [:tree :failed])))
            _ (when reread?
                (swap! state update :tree assoc :ready? false :pending []))
            start (if reread?
                    (let [computed (leaves-of idx (:records (store/changes inner nil)))]
                      {:merkle (merge-leaves merkle/empty-tree (:leaves computed))
                       :actors (if computed #{(:actors computed)} #{})})
                    {:merkle merkle/empty-tree :actors #{}})]
        (drain! idx start)
        (tree idx))
      (catch Exception e
        (swap! state update :tree assoc :failed (or (ex-message e) (.getName (class e))))
        (throw e))
      (finally (.unlock tree-lock)))))

(defn rebuild-tree!
  "Builds the tree again from every record of the store - see
   build-tree! with :reread?."
  [idx]
  (build-tree! idx {:reread? true}))

(defn inner
  "The store behind `s`, or `s` itself when it has no index."
  [s]
  (if (instance? Indexed s) (:inner s) s))
