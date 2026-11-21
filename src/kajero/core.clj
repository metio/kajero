;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: 0BSD
(ns kajero.core
  "A notebook: entries written and revised by actors, observed to hold or
   fail, linked to each other, and found again by selector.

   Every write is a record with its author, its time and its evidence,
   hashed over its canonical bytes and, when the actor has a key, signed. A
   notebook whose signing is :required refuses unsigned writes; one whose
   signing is :optional takes them and marks them unsigned. A signature is
   checked against the actors file before anything is written."
  (:require [clojure.string :as str]
            [kajero.record :as record]
            [kajero.select :as select]
            [kajero.standing :as standing]
            [kajero.store :as store])
  (:import (java.time Instant)))

(def ^:dynamic *now*
  "The clock of a write or a read; tests bind it."
  nil)

(defn- now ^Instant [] (or *now* (Instant/now)))

(def link-types #{"supersedes" "see-also" "contradicts"})

(defn notebook
  "{:path \"notes.db\" :signing :optional|:required :actors \"allowed_signers\"}"
  [{:keys [path signing actors] :or {signing :optional}}]
  {:conn (store/open path) :signing signing :actors actors})

(defn- refuse!
  [reason message data]
  (throw (ex-info message (assoc data :reason reason))))

(defn- seal
  "The signature a write carries, after checking it; nil for an unsigned
   write the notebook accepts."
  [{:keys [signing actors]} rec key-file]
  (let [sig (when key-file (record/sign key-file rec))]
    (cond
      (and (nil? sig) (= :required signing))
      (refuse! :kajero/unsigned "this notebook requires signed writes" {:actor (:actor rec)})

      (and sig (not (record/verified? actors rec sig)))
      (refuse! :kajero/bad-signature
               (format "the signature does not verify as %s against the actors file" (:actor rec))
               {:actor (:actor rec)})

      :else sig)))

(defn- check-actor!
  [actor]
  (when (str/blank? actor) (refuse! :kajero/no-actor "every write names its actor" {})))

;; ------------------------------------------------------------------ reads

(defn- revisions-of
  [{:keys [conn]} entry]
  (mapv store/read-record (store/query conn "SELECT record, sig FROM revisions WHERE entry = ? ORDER BY seq" entry)))

(defn- observations-of
  [{:keys [conn]} entry]
  (mapv store/read-record (store/query conn "SELECT record, sig FROM observations WHERE entry = ?" entry)))

(defn- links-of
  [{:keys [conn]} entry]
  (mapv store/read-record (store/query conn "SELECT record, sig FROM links WHERE source = ? OR target = ?" entry entry)))

(defn current
  "The entry's latest revision, or nil."
  [nb entry]
  (peek (revisions-of nb entry)))

(defn- entry-ids
  [{:keys [conn]}]
  (map :entry (store/query conn "SELECT DISTINCT entry FROM revisions")))

(defn- view
  [nb entry]
  (let [revisions (revisions-of nb entry)
        rev (peek revisions)
        observations (observations-of nb entry)
        links (links-of nb entry)]
    {:entry entry
     :revision (record/hash-of rev)
     :title (:title rev)
     :body (:body rev)
     :labels (:labels rev)
     :actor (:actor rev)
     :at (:at rev)
     :evidence (:evidence rev)
     :signed? (some? (:sig rev))
     :standing (standing/derive-standing
                rev (filter #(= (record/hash-of rev) (:revision %)) observations) (now))
     :links (mapv #(select-keys % [:source :type :target]) links)
     :superseded? (boolean (some #(and (= "supersedes" (:type %)) (= entry (:target %))) links))
     :actors (into #{} (map :actor) (concat revisions observations))}))

(defn entry
  "The entry with its current revision, standing and links, or nil."
  [nb id]
  (when (current nb id) (dissoc (view nb id) :actors)))

(defn- row
  [{:keys [title body labels standing signed? superseded? actors]}]
  {:labels labels
   :actors actors
   :haystack (str/lower-case (str/join " " (concat [title body] (map #(str/join "=" %) labels))))
   :disputed? (:disputed? standing)
   :stale? (:stale? standing)
   :unsigned? (not signed?)
   :superseded? superseded?})

(defn search
  "Summaries of the entries the selector picks, most recently changed first."
  [nb selector]
  (let [pred (select/compile selector)]
    (->> (entry-ids nb)
         (map #(view nb %))
         (filter (comp pred row))
         (sort-by :at #(compare %2 %1))
         (mapv (fn [v]
                 (-> (select-keys v [:entry :revision :title :labels :at :standing])
                     (assoc :summary (first (str/split-lines (or (:body v) ""))))))))))

(defn history
  "Every revision, observation and link of the entry, oldest first."
  [nb id]
  (->> (concat (map #(vector :revision %) (revisions-of nb id))
               (map #(vector :observation %) (observations-of nb id))
               (map #(vector :link %) (links-of nb id)))
       (map (fn [[kind r]]
              (-> r
                  (assoc :kind kind :hash (record/hash-of r) :signed? (some? (:sig r)))
                  (dissoc :sig))))
       (sort-by :at)
       vec))

(defn labels
  "Every label of a current revision, as {key {value count}}."
  [nb]
  (reduce (fn [acc id]
            (reduce-kv (fn [acc k v] (update-in acc [k v] (fnil inc 0))) acc (:labels (current nb id))))
          (sorted-map)
          (entry-ids nb)))

;; ----------------------------------------------------------------- writes

(defn- label-warnings
  "A warning for each label nobody used before, naming what is in use."
  [nb new-labels]
  (let [in-use (labels nb)]
    (when (seq in-use)
      (vec (for [[k v] (sort new-labels)
                 :when (not (get-in in-use [k v]))]
             (if-let [values (get in-use k)]
               (format "new label %s=%s; in use: %s" k v
                       (str/join ", " (map (fn [[v n]] (format "%s=%s (%d)" k v n)) values)))
               (format "new label key %s; keys in use: %s" k (str/join ", " (keys in-use)))))))))

(defn- similar
  "Entries that look like the same knowledge: the same title, or the same labels."
  [nb {:keys [title labels]}]
  (->> (entry-ids nb)
       (keep #(let [rev (current nb %)]
                (when (or (= (str/lower-case title) (str/lower-case (:title rev)))
                          (= labels (:labels rev)))
                  {:entry % :title (:title rev)})))
       vec))

(defn- check-content!
  [{:keys [title labels]}]
  (when (str/blank? title) (refuse! :kajero/no-title "an entry needs a title" {}))
  (when (empty? labels) (refuse! :kajero/no-labels "an entry needs at least one label" {})))

(defn- insert-revision!
  [{:keys [conn] :as nb} rec key-file]
  (let [sig (seal nb rec key-file)
        hash (record/hash-of rec)]
    (store/execute! conn "INSERT INTO revisions (hash, entry, seq, record, sig) VALUES (?, ?, ?, ?, ?)"
                    hash (:entry rec) (:seq rec) (pr-str rec) sig)
    hash))

(defn write!
  "Writes a new entry. Returns its id and first revision, the warnings for
   labels nobody used before, and the entries that look similar."
  [nb {:keys [title body labels evidence actor key]}]
  (check-actor! actor)
  (check-content! {:title title :labels labels})
  (let [id (str (random-uuid))
        rec {:entry id :seq 1 :base nil :title title :body (or body "") :labels labels
             :evidence (vec evidence) :actor actor :at (str (now))}
        warnings (label-warnings nb labels)
        similar (similar nb rec)]
    {:entry id
     :revision (store/in-transaction (:conn nb) #(insert-revision! nb rec key))
     :warnings warnings
     :similar similar}))

(defn revise!
  "Writes a new revision of an entry on top of `base`, which must be its
   current revision, so two writers can not overwrite each other unseen."
  [nb id {:keys [base title body labels evidence actor key]}]
  (check-actor! actor)
  (store/in-transaction
   (:conn nb)
   (fn []
     (let [cur (or (current nb id) (refuse! :kajero/no-entry (str "no entry " id) {:entry id}))
           cur-hash (record/hash-of cur)]
       (when (not= base cur-hash)
         (refuse! :kajero/stale-base "the base is not the current revision" {:entry id :current cur-hash}))
       (let [rec {:entry id :seq (inc (:seq cur)) :base cur-hash
                  :title (or title (:title cur)) :body (or body (:body cur))
                  :labels (or labels (:labels cur))
                  :evidence (vec evidence) :actor actor :at (str (now))}]
         (check-content! rec)
         {:entry id
          :revision (insert-revision! nb rec key)
          :warnings (when labels (label-warnings nb labels))})))))

(defn observe!
  "Records that `actor` checked a revision (the current one by default) and
   found it `held` or `failed`. A failed observation needs evidence."
  [{:keys [conn] :as nb} id {:keys [revision outcome evidence actor key]}]
  (check-actor! actor)
  (when-not (#{"held" "failed"} outcome)
    (refuse! :kajero/bad-outcome "an observation is held or failed" {:outcome outcome}))
  (when (and (= "failed" outcome) (empty? evidence))
    (refuse! :kajero/no-evidence "a failed observation needs evidence" {}))
  (let [revs (into {} (map (juxt record/hash-of identity)) (revisions-of nb id))
        cur (current nb id)
        target (or revision (some-> cur record/hash-of))]
    (when-not (contains? revs target)
      (refuse! :kajero/no-revision (format "entry %s has no revision %s" id target) {:entry id}))
    (let [rec {:entry id :revision target :outcome outcome :evidence (vec evidence)
               :actor actor :at (str (now))}
          sig (seal nb rec key)
          hash (record/hash-of rec)]
      (store/execute! conn "INSERT INTO observations (hash, entry, revision, record, sig) VALUES (?, ?, ?, ?, ?)"
                      hash id target (pr-str rec) sig)
      {:observation hash :standing (:standing (entry nb id))})))

(defn link!
  "Links two entries: `supersedes`, `see-also` or `contradicts`."
  [{:keys [conn] :as nb} {:keys [source type target actor key]}]
  (check-actor! actor)
  (when-not (link-types type)
    (refuse! :kajero/bad-link (str "a link is one of " (str/join ", " (sort link-types))) {:type type}))
  (doseq [e [source target]]
    (when-not (current nb e) (refuse! :kajero/no-entry (str "no entry " e) {:entry e})))
  (let [rec {:source source :type type :target target :actor actor :at (str (now))}
        sig (seal nb rec key)
        hash (record/hash-of rec)]
    (store/execute! conn "INSERT INTO links (hash, source, target, record, sig) VALUES (?, ?, ?, ?, ?)"
                    hash source target (pr-str rec) sig)
    {:link hash}))
