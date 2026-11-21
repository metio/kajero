;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.core
  "A notebook: entries written and revised by actors, observed to hold or
   fail, linked to each other, and found again by selector.

   Every write is a record with its author, its time and its evidence,
   hashed over its canonical bytes and, when the actor has a key, signed. A
   notebook whose signing is :required refuses unsigned writes; one whose
   signing is :optional takes them and marks them unsigned. A signature is
   checked against the actors file before anything is written."
  (:require [clojure.string :as str]
            [kajero.data :as data]
            [kajero.record :as record]
            [kajero.select :as select]
            [kajero.standing :as standing]
            [kajero.store :as store]
            [kajero.tik :as tik])
  (:import (java.time Instant)))

(def ^:dynamic *now*
  "The clock of a write or a read; tests bind it."
  nil)

(defn- now ^Instant [] (or *now* (Instant/now)))

(def link-types #{"supersedes" "see-also" "contradicts"})

(defn notebook
  "{:path \"notes.db\"} for SQLite or {:dir \"notes/\"} for flat files, with
   :signing :optional|:required, :actors \"allowed_signers\", and :tik-root, the
   tik store whose tickets evidence may cite as tik:<id>. :tik-status replaces
   the lookup of a ticket, for tests."
  [{:keys [signing actors tik-root tik-status] :or {signing :optional} :as opts}]
  {:store (store/open opts) :signing signing :actors actors
   :tik-status (or tik-status (when tik-root (tik/status-in tik-root)))})

(defn- check-tickets!
  "Refuses evidence citing a ticket the bound tik store does not hold. A
   notebook bound to no store takes citations unchecked."
  [{:keys [tik-status]} evidence]
  (when tik-status
    (doseq [id (tik/ticket-ids evidence)]
      (when-not (tik-status id)
        (throw (ex-info (str "the tik store holds no ticket " id) {:reason :kajero/no-ticket :ticket id}))))))

(defn tickets
  "The tickets an entry's records cite, with their title and current stages
   when the notebook is bound to a tik store."
  [{:keys [tik-status]} records]
  (vec (for [id (distinct (mapcat (comp tik/ticket-ids :evidence) records))]
         (or (when tik-status (tik-status id)) {:ticket id}))))

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

(defn- revisions-of [nb entry] (store/revisions (:store nb) entry))
(defn- observations-of [nb entry] (store/observations (:store nb) entry))
(defn- links-of [nb entry] (store/links (:store nb) entry))

(defn current
  "The entry's latest revision, or nil."
  [nb entry]
  (peek (revisions-of nb entry)))

(defn- entry-ids [nb] (store/entries (:store nb)))

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
     :data (:data rev)
     :schema (:schema rev)
     :signed? (some? (:sig rev))
     :standing (standing/derive-standing
                rev (filter #(= (record/hash-of rev) (:revision %)) observations) (now))
     :links (mapv #(select-keys % [:source :type :target]) links)
     :superseded? (boolean (some #(and (= "supersedes" (:type %)) (= entry (:target %))) links))
     :actors (into #{} (map :actor) (concat revisions observations))
     :ticket-ids (into #{} (mapcat (comp tik/ticket-ids :evidence)) (concat revisions observations))}))

(defn entry
  "The entry with its current revision, standing, links and the tickets its
   records cite, or nil."
  [nb id]
  (when (current nb id)
    (-> (view nb id)
        (dissoc :actors :ticket-ids)
        (assoc :tickets (tickets nb (concat (revisions-of nb id) (observations-of nb id)))))))

(defn- row
  [{:keys [title body labels standing signed? superseded? actors ticket-ids] :as v}]
  {:labels labels
   :actors actors
   :tickets ticket-ids
   :haystack (str/lower-case (str/join " " (concat [title body (some-> (:data v) pr-str)]
                                                   (map #(str/join "=" %) labels))))
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
  (->> (concat (for [r (revisions-of nb id)] [:revision r])
               (for [o (observations-of nb id)] [:observation o])
               (for [l (links-of nb id)] [:link l]))
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

(defn- check-data!
  "The schema reference a revision records: {:entry :revision} of the schema
   entry its schema= label names, after checking the data against it. A
   kind=schema entry's own data must be a malli schema."
  [nb {:keys [labels] :as rec}]
  (when (= "schema" (get labels "kind"))
    (data/schema (:data rec)))
  (when-let [schema-id (get labels "schema")]
    (let [schema-rev (or (current nb schema-id)
                         (refuse! :kajero/no-schema (str "no schema entry " schema-id) {:schema schema-id}))]
      (when-not (= "schema" (get-in schema-rev [:labels "kind"]))
        (refuse! :kajero/no-schema (str schema-id " is not labelled kind=schema") {:schema schema-id}))
      (when-let [errors (data/explain (:data schema-rev) (:data rec))]
        (refuse! :kajero/bad-data (str "the data does not fit schema " schema-id ": " (pr-str errors))
                 {:schema schema-id :errors errors}))
      {:entry schema-id :revision (record/hash-of schema-rev)})))

(defn- check-content!
  [{:keys [title labels]}]
  (when (str/blank? title) (refuse! :kajero/no-title "an entry needs a title" {}))
  (when (empty? labels) (refuse! :kajero/no-labels "an entry needs at least one label" {})))

(defn- insert-revision!
  [nb rec key-file]
  (store/append! (:store nb) :revision rec (seal nb rec key-file)))

(defn write!
  "Writes a new entry. Returns its id and first revision, the warnings for
   labels nobody used before, and the entries that look similar."
  [nb {:keys [title body labels evidence actor key] :as opts}]
  (check-actor! actor)
  (check-content! {:title title :labels labels})
  (check-tickets! nb evidence)
  (let [id (str (random-uuid))
        base-rec (cond-> {:entry id :seq 1 :base nil :title title :body (or body "") :labels labels
                          :evidence (vec evidence) :actor actor :at (str (now))}
                   (some? (:data opts)) (assoc :data (:data opts)))
        schema-ref (check-data! nb base-rec)
        rec (cond-> base-rec schema-ref (assoc :schema schema-ref))
        warnings (label-warnings nb labels)
        similar (similar nb rec)]
    {:entry id
     :revision (insert-revision! nb rec key)
     :warnings warnings
     :similar similar}))

(defn revise!
  "Writes a new revision of an entry on top of `base`, which must be its
   current revision, so two writers can not overwrite each other unseen."
  [nb id {:keys [base title body labels evidence actor key] :as opts}]
  (check-actor! actor)
  (check-tickets! nb evidence)
  (let [cur (or (current nb id) (refuse! :kajero/no-entry (str "no entry " id) {:entry id}))
        cur-hash (record/hash-of cur)]
    (when (not= base cur-hash)
      (refuse! :kajero/stale-base "the base is not the current revision" {:entry id :current cur-hash}))
    (let [data (if (contains? opts :data) (:data opts) (:data cur))
          base-rec (cond-> {:entry id :seq (inc (:seq cur)) :base cur-hash
                            :title (or title (:title cur)) :body (or body (:body cur))
                            :labels (or labels (:labels cur))
                            :evidence (vec evidence) :actor actor :at (str (now))}
                     (some? data) (assoc :data data))
          _ (check-content! base-rec)
          schema-ref (check-data! nb base-rec)
          rec (cond-> base-rec schema-ref (assoc :schema schema-ref))]
      {:entry id
       ;; another writer may land the same :seq between the check above and
       ;; this append; the store refuses the second one
       :revision (try (insert-revision! nb rec key)
                      (catch clojure.lang.ExceptionInfo e
                        (if (= :kajero/stale-base (:reason (ex-data e)))
                          (refuse! :kajero/stale-base (ex-message e)
                                   {:entry id :current (some-> (current nb id) record/hash-of)})
                          (throw e))))
       :warnings (when labels (label-warnings nb labels))})))

(defn observe!
  "Records that `actor` checked a revision (the current one by default) and
   found it `held` or `failed`. A failed observation needs evidence."
  [nb id {:keys [revision outcome evidence actor key]}]
  (check-actor! actor)
  (when-not (#{"held" "failed"} outcome)
    (refuse! :kajero/bad-outcome "an observation is held or failed" {:outcome outcome}))
  (when (and (= "failed" outcome) (empty? evidence))
    (refuse! :kajero/no-evidence "a failed observation needs evidence" {}))
  (check-tickets! nb evidence)
  (let [revs (into {} (map (juxt record/hash-of identity)) (revisions-of nb id))
        cur (current nb id)
        target (or revision (some-> cur record/hash-of))]
    (when-not (contains? revs target)
      (refuse! :kajero/no-revision (format "entry %s has no revision %s" id target) {:entry id}))
    (let [rec {:entry id :revision target :outcome outcome :evidence (vec evidence)
               :actor actor :at (str (now))}
          hash (store/append! (:store nb) :observation rec (seal nb rec key))]
      {:observation hash :standing (:standing (entry nb id))})))

(defn link!
  "Links two entries: `supersedes`, `see-also` or `contradicts`."
  [nb {:keys [source type target actor key]}]
  (check-actor! actor)
  (when-not (link-types type)
    (refuse! :kajero/bad-link (str "a link is one of " (str/join ", " (sort link-types))) {:type type}))
  (doseq [e [source target]]
    (when-not (current nb e) (refuse! :kajero/no-entry (str "no entry " e) {:entry e})))
  (let [rec {:source source :type type :target target :actor actor :at (str (now))}]
    {:link (store/append! (:store nb) :link rec (seal nb rec key))}))

;; ------------------------------------------------------------------ pages

(defn save-page!
  "Saves a page: a named selector, with an optional title, intro, order and
   grouping label key. Saving a name again writes a new version; the newest
   one is the page."
  [nb {:keys [name title intro selector group-by actor key]}]
  (check-actor! actor)
  (when-not (re-matches #"[a-z0-9][a-z0-9-]*" (str name))
    (refuse! :kajero/bad-page-name "a page name is lower-case letters, digits and dashes" {:name name}))
  (select/compile selector)
  (let [rec {:name name :title (or (not-empty title) name) :intro (or intro "") :selector (or selector "")
             :group-by (not-empty group-by) :actor actor :at (str (now))}
        hash (store/append! (:store nb) :page rec (seal nb rec key))]
    {:page name :version hash}))

(defn pages
  "The newest version of every page, by name."
  [nb]
  (->> (store/page-versions (:store nb))
       (group-by :name)
       (map (fn [[_ versions]] (last (sort-by :at versions))))
       (sort-by :name)
       vec))

(defn page
  [nb name]
  (some #(when (= name (:name %)) %) (pages nb)))
