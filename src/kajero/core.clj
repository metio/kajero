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
            [kajero.cite :as cite]
            [kajero.data :as data]
            [kajero.data-query :as dq]
            [kajero.derived :as derived]
            [kajero.index :as index]
            [kajero.migrate :as migrate]
            [kajero.rank :as rank]
            [kajero.record :as record]
            [kajero.select :as select]
            [kajero.standing :as standing]
            [kajero.store :as store]
            [kajero.tik :as tik]
            [kajero.usage :as usage]
            [kajero.verify :as verify])
  (:import (java.time Instant)))

(def ^:dynamic *now*
  "The clock of a write or a read; tests bind it."
  nil)

(defn- now ^Instant [] (or *now* (Instant/now)))

(def link-types select/link-types)

(defn- warm-on-append
  "What a notebook computes for a record it has just written: every value
   a search derives from a revision, and the revision's signature verdict,
   so the first search after a write finds them remembered."
  [verifier]
  (fn [kind rec]
    (if (= :revision kind)
      (verify/verdict verifier rec (derived/warm-revision! rec))
      (derived/hash-of rec))))

(defn notebook
  "{:path \"notes.db\"} for SQLite or {:dir \"notes/\"} for flat files, with
   :signing :optional|:required, :actors \"allowed_signers\", and :tik-root, the
   tik store whose tickets evidence may cite as tik:<id>. :tik-status replaces
   the lookup of a ticket, for tests.

   The store sits behind an in-memory index - see kajero.index - whose
   :refresh-ms says how old it may get before a read picks up what other
   processes wrote; :index? false reads the store directly.

   Searches and reads made through search! and open! are counted beside
   the notebook - see kajero.usage: in the database's usage tables, or in
   <dir>.usage.edn next to a flat-file notebook. The counts are held in
   memory until flush! or close! writes them."
  [{:keys [signing actors tik-root tik-status dir refresh-ms index?] :or {signing :optional index? true} :as opts}]
  (let [s (store/open opts)
        verifier (verify/verifier actors)]
    {:store (if index? (index/indexed s {:refresh-ms refresh-ms :on-append (warm-on-append verifier)}) s)
     :signing signing :actors actors
     :verifier verifier
     :citations (atom nil)
     :usage (usage/recorder (if dir (usage/file-log (usage/beside dir)) (usage/sqlite-log (:conn s))))
     :tik-status (or tik-status (when tik-root (tik/cached (tik/status-in tik-root))))}))

(defn flush!
  "Writes the usage counts held in memory - see kajero.usage."
  [nb]
  (usage/flush! (:usage nb)))

(defn close!
  "Stops the notebook's periodic flush, if one runs, and writes the counts
   still held in memory."
  [nb]
  (usage/close! (:usage nb)))

(defn keep-flushing!
  "Writes the usage counts every usage/flush-interval-ms, and once more
   when the JVM shuts down. For a long-running server."
  [nb]
  (usage/start-flushing! (:usage nb))
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (close! nb))))
  nb)

(defn- check-tickets!
  "Refuses evidence citing a ticket the bound tik store does not hold. A
   notebook bound to no store takes citations unchecked."
  [{:keys [tik-status]} evidence]
  (when tik-status
    (doseq [id (tik/ticket-ids evidence)]
      (when-not (tik/for-write tik-status id)
        (throw (ex-info (str "the tik store holds no ticket " id) {:reason :kajero/no-ticket :ticket id}))))))

(defn tickets
  "The tickets an entry's records cite, with their title and current stages
   when the notebook is bound to a tik store."
  [{:keys [tik-status]} records]
  (vec (for [id (distinct (mapcat (comp tik/ticket-ids :evidence) records))]
         (or (when tik-status (tik/for-display tik-status id)) {:ticket id}))))

(defn- refuse!
  [reason message data]
  (throw (ex-info message (assoc data :reason reason))))

(defn- evidence-list
  "Evidence as a vector of strings. One string is one item: a client that
   sends a bare string instead of a list means one piece of evidence, and
   taking it apart into characters would store nonsense."
  [evidence]
  (cond
    (nil? evidence) []
    (string? evidence) [evidence]
    (and (sequential? evidence) (every? string? evidence)) (vec evidence)
    :else (refuse! :kajero/bad-evidence "evidence is a list of strings" {:evidence evidence})))

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

(def ^:private min-prefix
  "The shortest id prefix taken as an id; prose and tik quote eight."
  4)

(defn resolve-among
  "The id in `ids` that `id` names: the id itself, or the one id it is a
   prefix of, at least four characters long. nil when nothing fits;
   ex-info {:reason :kajero/ambiguous-id :candidates [...]} when more than
   one does."
  [ids id]
  (let [id (some-> id str/trim)]
    (cond
      (str/blank? id) nil
      (some #{id} ids) id
      (< (count id) min-prefix) nil
      :else (let [fits (vec (sort (filter #(str/starts-with? % id) ids)))]
              (case (count fits)
                0 nil
                1 (first fits)
                (refuse! :kajero/ambiguous-id
                         (str "the id " id " fits " (count fits) " entries: " (str/join ", " fits))
                         {:id id :candidates fits}))))))

(declare entry-ids)

(defn resolve-id
  "The entry id `id` names in the notebook - see resolve-among - or `id`
   unchanged when it names none, so the caller's own refusal reports it."
  [nb id]
  (or (resolve-among (entry-ids nb) id) id))

(defn- revisions-of [nb entry] (store/revisions (:store nb) entry))
(defn- observations-of [nb entry] (store/observations (:store nb) entry))
(defn- links-of [nb entry] (store/links (:store nb) entry))

(defn current
  "The entry's latest revision, or nil."
  [nb entry]
  (store/head (:store nb) entry))

(defn- entry-ids [nb] (store/entries (:store nb)))

(defn- registry
  "The actors file as one request reads it, for every verdict the request
   judges - see kajero.verify/registry."
  [nb]
  (verify/registry (:verifier nb)))

(defn- signature
  "Whether the record's signature verifies against the actors file `reg`:
   {:signature :verified|:unsigned|:bad-signature}, see kajero.verify.
   `hash` is the record's hash when the caller has it already."
  [nb reg rec hash]
  (verify/verdict (:verifier nb) reg rec hash))

(defn verify-current!
  "Judges the signature of every entry's current revision, which every
   search reads, so later searches find the verdicts cached. The records
   are judged in parallel. Returns the number judged. The web and MCP
   servers run it on a background thread at start; a read that comes first
   judges what it needs itself."
  [nb]
  (let [s (:store nb)]
    (verify/warm! (:verifier nb) (keep #(store/head s %) (store/entries s)) derived/hash-of)))

(defn verify-in-background!
  "Runs verify-current! on a daemon thread, so a server answers while the
   verdicts are judged and the thread never keeps the JVM alive. A failure
   is logged; reads judge what they need themselves either way."
  ^Thread [nb]
  (doto (Thread. ^Runnable (fn []
                             (try (verify-current! nb)
                                  (catch Exception e
                                    (binding [*out* *err*]
                                      (println "kajero: verifying signatures in the background failed:"
                                               (or (ex-message e) (.getName (class e))))))))
                 "kajero-verify")
    (.setDaemon true)
    (.start)))

(defn- corrections
  "Every observation a later one corrected, oldest first:
   {:observation :outcome :revision :actor :at} of the corrected one with
   :corrected-by, :correction (the later outcome, or withdrawn) and the
   :evidence the correction gave."
  [observations]
  (let [by-hash (into {} (map (juxt derived/hash-of identity)) observations)]
    (->> observations
         (filter :corrects)
         (keep (fn [c]
                 (when-let [o (by-hash (:corrects c))]
                   {:observation (:corrects c) :outcome (:outcome o) :revision (:revision o)
                    :actor (:actor o) :at (:at o)
                    :corrected-by (derived/hash-of c) :correction (:outcome c)
                    :corrector (:actor c) :corrected-at (:at c) :evidence (:evidence c)})))
         (sort-by :at)
         vec)))

(defn- counted
  "The observations that count toward a standing: not withdrawn, and not
   corrected by a later observation."
  [observations]
  (let [corrected (into #{} (keep :corrects) observations)]
    (remove #(or (= "withdrawn" (:outcome %)) (corrected (derived/hash-of %))) observations)))

(defn- missed
  "The searches that did not find the revision although it held the answer:
   {:selector :actor :at} of each missed observation among `observations`,
   oldest first."
  [observations]
  (->> observations
       (filter #(= "missed" (:outcome %)))
       (map #(select-keys % [:selector :actor :at]))
       (sort-by :at)
       vec))

(defn- facts
  "What a read may need about the entry `id`, each value computed on first
   use and then kept for the rest of the read: `rev` is its current
   revision, `reg` the actors file of the request, `at` the instant the
   standing is derived for, and `counts` the usage counts when the read
   needs them."
  [nb reg at counts id rev]
  (let [h (delay (derived/hash-of rev))
        links (delay (links-of nb id))
        all-observations (delay (observations-of nb id))
        of-current (delay (let [rh @h] (filter #(= rh (:revision %)) (counted @all-observations))))
        authorship (delay (store/authorship (:store nb) id))
        state (delay (:signature (signature nb reg rev @h)))]
    {:nb nb :id id :rev rev :counts counts
     :hash h :links links :all-observations all-observations :of-current of-current
     :authorship authorship :state state
     :standing (delay (standing/derive-standing rev @of-current at))
     :missed (delay (missed @of-current))
     :superseded? (delay (boolean (some #(and (= "supersedes" (:type %)) (= id (:target %))) @links)))
     :outdated-schema? (delay (boolean (when-let [{schema-entry :entry pinned :revision} (:schema rev)]
                                         (not= pinned (some-> (current nb schema-entry) derived/hash-of)))))
     :actors (delay (into (:actors @authorship) (map :actor) @all-observations))
     :ticket-ids (delay (into (set (tik/ticket-ids (:evidence @authorship)))
                              (mapcat (comp tik/ticket-ids :evidence)) @all-observations))}))

(defn- view
  [nb reg id]
  (let [rev (current nb id)
        f (facts nb reg (now) nil id rev)
        state @(:state f)
        links @(:links f)]
    {:entry id
     :revision @(:hash f)
     :title (:title rev)
     :body (:body rev)
     :labels (:labels rev)
     :actor (:actor rev)
     :at (:at rev)
     :evidence (:evidence rev)
     :data (:data rev)
     :schema (:schema rev)
     :signed? (= :verified state)
     :signature state
     :standing @(:standing f)
     :missed @(:missed f)
     :links (mapv #(select-keys % [:source :type :target]) links)
     :superseded? @(:superseded? f)
     :retired (:retired rev)
     :outdated-schema? @(:outdated-schema? f)
     :corrections (corrections @(:all-observations f))
     :actors @(:actors f)
     :ticket-ids @(:ticket-ids f)}))

(declare schema-users)

(defn- page-entry
  "The entry `id` as a page draws it: entry without the tickets, which
   only the entry's own page and kajero_get show; nil when there is none."
  [nb reg id]
  (when-let [rev (current nb id)]
    (-> (view nb reg id)
        (dissoc :actors :ticket-ids)
        (as-> e (if (seq (:corrections e)) e (dissoc e :corrections)))
        (as-> e (if (seq (:missed e)) e (dissoc e :missed)))
        (cond-> (nil? (:retired rev)) (dissoc :retired))
        (cond-> (= "schema" (get-in rev [:labels "kind"]))
          (assoc :used-by (schema-users nb id))))))

(defn entry
  "The entry with its current revision, standing, links and the tickets its
   records cite, or nil."
  [nb id]
  (when-let [e (page-entry nb (registry nb) id)]
    (assoc e :tickets (tickets nb (cons {:evidence (:evidence (store/authorship (:store nb) id))}
                                        (observations-of nb id))))))

(defn entries
  "The entries of `ids` as entry reads them, without the tickets their
   records cite: what a page of several entries draws. Every verdict is
   judged under one reading of the actors file. Ids without an entry are
   left out."
  [nb ids]
  (let [reg (registry nb)]
    (into [] (keep #(page-entry nb reg %)) ids)))

(def ^:private row-fields
  "How each key of a search row - see kajero.select/compile - is computed
   from the facts of one entry."
  {:labels #(:labels (:rev %))
   :links (fn [{:keys [id links]}]
            (into #{} (map (fn [{:keys [source type target]}] [type (if (= id source) target source)])) @links))
   :data #(:data (:rev %))
   :schema-revision #(get-in (:rev %) [:schema :revision])
   :outdated-schema? #(deref (:outdated-schema? %))
   :actors #(deref (:actors %))
   :tickets #(deref (:ticket-ids %))
   :paths (fn [{:keys [rev hash]}] (derived/cited-paths @hash rev))
   :haystack (fn [{:keys [rev hash]}] (derived/haystack @hash rev))
   :disputed? #(:disputed? @(:standing %))
   :stale? #(:stale? @(:standing %))
   :missed? #(boolean (seq @(:missed %)))
   :unsigned? #(= :unsigned @(:state %))
   :bad-signature? #(= :bad-signature @(:state %))
   :unverified? #(not= :verified @(:state %))
   :superseded? #(deref (:superseded? %))
   :retired? #(some? (:retired (:rev %)))
   :usage (fn [{:keys [counts id]}] (when counts (usage/of-entry counts id)))})

(deftype ^:private Row [facts fields ^java.util.HashMap memo]
  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [_ k _]
    (if (.containsKey memo k)
      (.get memo k)
      (let [compute (or (get fields k)
                        (throw (ex-info (str "a selector read the row key " k " it did not declare")
                                        {:reason :kajero/undeclared-row-key :key k})))
            v (compute facts)]
        (.put memo k v)
        v))))

(defn- row
  "The search row of the entry facts `f`, offering only the keys of
   `fields` and computing each the first time the selector reads it."
  [fields f]
  (->Row f fields (java.util.HashMap.)))

(defn- live?
  "Whether the entry is not retired. Reads that list entries - search,
   labels, similar - skip retired ones unless asked for them."
  [nb id]
  (nil? (:retired (current nb id))))

(defn first-line
  "The first line of `body` as (first (clojure.string/split-lines body))
   gives it, without splitting the rest of the body: nil when the body is
   nothing but line breaks, \"\" for a nil or empty body."
  [body]
  (let [^String b (or body "")
        i (.indexOf b "\n")]
    (if (neg? i)
      b
      (let [line (subs b 0 (if (and (pos? i) (= \return (.charAt b (dec i)))) (dec i) i))]
        (when-not (and (= "" line) (re-matches #"(?:\r?\n)+" b))
          line)))))

(defn- citation-input
  "What kajero.cite/incoming reads about the entry `id`."
  [nb id]
  (let [rev (current nb id)
        links (links-of nb id)]
    {:entry id :body (:body rev) :links links :retired (:retired rev)
     :superseded? (boolean (some #(and (= "supersedes" (:type %)) (= id (:target %))) links))}))

(defn- citations
  "How many entries cite each entry - see kajero.cite - computed once per
   generation of the store and kept until a record changes it. The
   generation is read before the records, so what is kept is never older
   than the generation it is kept for. A store without generations counts
   afresh every time."
  [nb]
  (let [g (store/generation (:store nb))
        memo (:citations nb)
        [kept-g kept] (some-> memo deref)]
    (if (and g (= g kept-g))
      kept
      (let [c (cite/incoming (map #(citation-input nb %) (entry-ids nb)))]
        (when (and g memo) (reset! memo [g c]))
        c))))

(defn- summary
  "The search summary of one returned entry."
  [f terms compiled data-terms scored]
  (let [{:keys [id rev]} f
        v (cond-> {:entry id :revision @(:hash f) :title (:title rev) :labels (:labels rev) :at (:at rev)
                   :standing @(:standing f) :signature @(:state f)}
            (seq terms) (assoc :score (:score scored) :citations (:citations scored) :rank (:rank scored)
                               :matches (rank/matches-compiled (:text scored) compiled)))]
    (-> (select-keys v [:entry :revision :title :labels :at :standing :signature :score :citations :rank :matches])
        (cond-> (:retired rev) (assoc :retired (:retired rev))
                data-terms (assoc :rows (dq/matching (:data rev) data-terms)))
        (assoc :summary (first-line (:body rev))))))

(defn search
  "Summaries of the entries the selector picks. With text terms, each
   summary carries its term :score - see kajero.rank - the number of
   entries in the notebook that cite it as :citations - see kajero.cite -
   and their sum as :rank, the term score plus (cite/boost citations); the
   highest rank comes first, and each summary says per term where it
   matched with the text around it. Equal ranks and selectors without text
   go most recently changed first. With data:
   terms, each summary carries as :rows the elements of its data they pick
   together - see kajero.data-query. Retired entries are left out unless the selector names `retired`.
   A never-returned or returned-unopened term reads the usage counts, and
   refuses with :kajero/usage-unreadable when they can not be read. A
   search counts nothing; search! does.

   Only the row keys the selector reads are computed for an entry, and the
   standing, signature and matches only for the entries returned; :limit
   returns the first that many. Every signature a term judges, and every
   signature a summary shows, is judged under the actors file read once
   for the search."
  ([nb selector] (search nb selector nil))
  ([nb selector {:keys [limit]}]
   (let [retired? (select/names? selector "retired")
         pred (select/compile selector)
         fields (select-keys row-fields (cond-> (select/reads pred) (not retired?) (conj :retired?)))
         counts (when (select/reads-usage? selector) (usage/counts (:usage nb)))
         terms (select/text-terms selector)
         compiled (when (seq terms) (rank/compile-terms terms))
         data-terms (not-empty (select/data-terms selector))
         reg (registry nb)
         at (now)
         matched (into []
                       (keep (fn [id]
                               (let [f (facts nb reg at counts id (current nb id))
                                     r (row fields f)]
                                 (when (and (or retired? (not (:retired? r))) (pred r))
                                   f))))
                       (entry-ids nb))
         cited (when (seq terms) (citations nb))
         scored (mapv (fn [{:keys [id rev hash] :as f}]
                        (if (seq terms)
                          (let [text (derived/search-text @hash rev)
                                score (rank/score-compiled text compiled)
                                n (get cited id 0)]
                            {:facts f :at (:at rev) :text text :score score :citations n
                             :rank (+ score (cite/boost n))})
                          {:facts f :at (:at rev)}))
                      matched)]
     (->> scored
          (sort (fn [x y] (let [by-rank (compare (:rank y 0) (:rank x 0))]
                            (if (zero? by-rank) (compare (:at y) (:at x)) by-rank))))
          (#(if limit (take limit %) %))
          (mapv #(summary (:facts %) terms compiled data-terms %))))))

(defn recent
  "The `n` most recently changed entries that are not retired, summarised
   as a search without terms summarises them."
  [nb n]
  (search nb "" {:limit n}))

(defn search!
  "The search, counting that it returned its entries, for `session` when
   there is one - see kajero.usage. A selector naming no term, or reading
   the counts itself, counts nothing; every other term counts, `missed`
   included. The counts are no input to the rank, which search computes
   from terms and citations alone, so the counting never changes the
   result, and a count that can not be written is logged and dropped."
  [nb session selector]
  (let [results (search nb selector)]
    (when (select/retrieval? selector)
      (usage/searched! (:usage nb) session (mapv :entry results) (now)))
    results))

(defn open!
  "The entry, as `entry` reads it, counting that it was read and whether a
   search of the same `session` returned it before. Nothing is counted for
   an entry that does not exist."
  [nb session id]
  (let [e (entry nb id)]
    (when e (usage/opened! (:usage nb) session id (now)))
    e))

(defn start-session!
  "A new session for the counts of search! and open!, by its id."
  [nb]
  (usage/start-session! (:usage nb) (now)))

(defn live-session
  "`id` when it names a session start-session! began and that is not
   over, else nil."
  [nb id]
  (usage/live-session (:usage nb) id (now)))

(defn usage-since
  "The day the counting began, or nil when nothing was counted yet."
  [nb]
  (:since (usage/counts (:usage nb))))

(defn history
  "Every revision, observation and link of the entry, oldest first. An
   observation a later one corrected carries :corrected-by."
  [nb id]
  (let [reg (registry nb)]
    (->> (concat (for [r (revisions-of nb id)] [:revision r nil])
                 (let [obs (observations-of nb id)
                       corrector (into {} (keep #(when (:corrects %) [(:corrects %) (record/hash-of %)])) obs)]
                   (for [o obs
                         :let [h (record/hash-of o)]]
                     [:observation o (when (corrector h) {:corrected-by (corrector h)})]))
                 (for [l (links-of nb id)] [:link l nil]))
         (map (fn [[kind r extra]]
                (let [h (record/hash-of r)
                      {state :signature} (signature nb reg r h)]
                  (-> r
                      (dissoc :sig)
                      (assoc :kind kind :hash h
                             :signed? (= :verified state) :signature state)
                      (merge extra)))))
         (sort-by :at)
         vec)))

(defn labels
  "Every label of a current revision of an entry that is not retired, as
   {key {value count}}."
  [nb]
  (reduce (fn [acc id]
            (reduce-kv (fn [acc k v] (update-in acc [k v] (fnil inc 0))) acc (:labels (current nb id))))
          (sorted-map)
          (filter #(live? nb %) (entry-ids nb))))

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
  "Entries that look like the same knowledge: the same title, or the same
   labels. A retired entry is not offered."
  [nb {:keys [title labels]}]
  (->> (filter #(live? nb %) (entry-ids nb))
       (keep #(let [rev (current nb %)]
                (when (or (= (str/lower-case title) (str/lower-case (:title rev)))
                          (= labels (:labels rev)))
                  {:entry % :title (:title rev)})))
       vec))

(defn- successor
  "The entry that supersedes `id`, or nil."
  [nb id]
  (some #(when (and (= "supersedes" (:type %)) (= id (:target %))) (:source %)) (links-of nb id)))

(defn- deprecation
  "Why the schema entry should no longer be used, or nil: a deprecated label
   on its current revision, or an entry superseding it."
  [nb schema-id schema-rev]
  (let [label (get-in schema-rev [:labels "deprecated"])
        next-id (successor nb schema-id)]
    (when (or label next-id)
      (str "schema " schema-id " is deprecated"
           (when (and label (not= "true" label)) (str " (" label ")"))
           (when next-id (str "; use " next-id))))))

(defn- schema-entry!
  "The current revision of the schema entry `id`, refusing anything else."
  [nb id]
  (let [rev (or (current nb id) (refuse! :kajero/no-schema (str "no schema entry " id) {:schema id}))]
    (when-not (= "schema" (get-in rev [:labels "kind"]))
      (refuse! :kajero/no-schema (str id " is not labelled kind=schema") {:schema id}))
    rev))

(defn- check-data!
  "{:ref :warnings} for a revision: :ref is {:entry :revision} of the schema
   entry its schema= label names, after checking the data against it. A
   kind=schema entry's own data must be a malli schema, a kind=migration
   entry's a migration between two schema entries, and a kind=coverage
   entry's fit kajero.data/coverage-schema. No revision may move onto
   a deprecated schema; one that stays on the deprecated schema it already
   uses passes with a warning, so old entries stay correctable until they
   are migrated. `previous` is the schema the entry used before, nil for a
   new entry."
  [nb {:keys [labels] :as rec} previous]
  (case (get labels "kind")
    "schema" (data/schema (:data rec))
    "migration" (if-let [errors (migrate/explain (:data rec))]
                  (refuse! :kajero/bad-migration (str "the data is not a migration: " (pr-str errors))
                           {:errors errors})
                  (do (schema-entry! nb (get-in rec [:data :from]))
                      (schema-entry! nb (get-in rec [:data :to]))))
    "coverage" (when-let [errors (data/explain data/coverage-schema (:data rec))]
                 (refuse! :kajero/bad-coverage
                          (str "a kind=coverage entry's data names the question, population, method and "
                               "the day it was asked (YYYY-MM-DD): " (pr-str errors))
                          {:errors errors}))
    nil)
  (when-let [schema-id (get labels "schema")]
    (let [schema-rev (schema-entry! nb schema-id)
          deprecated (deprecation nb schema-id schema-rev)]
      (when (and deprecated (not= previous schema-id))
        (refuse! :kajero/deprecated-schema deprecated {:schema schema-id :successor (successor nb schema-id)}))
      (when-let [errors (data/explain (:data schema-rev) (:data rec))]
        (refuse! :kajero/bad-data (str "the data does not fit schema " schema-id ": " (pr-str errors))
                 {:schema schema-id :errors errors}))
      {:ref {:entry schema-id :revision (record/hash-of schema-rev)}
       :warnings (when deprecated [deprecated])})))

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
  (check-tickets! nb (evidence-list evidence))
  (let [id (str (random-uuid))
        base-rec (cond-> {:entry id :seq 1 :base nil :title title :body (or body "") :labels labels
                          :evidence (evidence-list evidence) :actor actor :at (str (now))}
                   (some? (:data opts)) (assoc :data (:data opts)))
        {schema-ref :ref} (check-data! nb base-rec nil)
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
  (check-tickets! nb (evidence-list evidence))
  (let [cur (or (current nb id) (refuse! :kajero/no-entry (str "no entry " id) {:entry id}))
        cur-hash (record/hash-of cur)]
    (when (not= base cur-hash)
      (refuse! :kajero/stale-base "the base is not the current revision" {:entry id :current cur-hash}))
    (let [data (if (contains? opts :data) (:data opts) (:data cur))
          base-rec (cond-> {:entry id :seq (inc (:seq cur)) :base cur-hash
                            :title (or title (:title cur)) :body (or body (:body cur))
                            :labels (or labels (:labels cur))
                            :evidence (evidence-list evidence) :actor actor :at (str (now))}
                     (some? data) (assoc :data data)
                     ;; a revision keeps the entry retired; only restore! lifts it
                     (:retired cur) (assoc :retired (:retired cur)))
          _ (check-content! base-rec)
          {schema-ref :ref schema-warnings :warnings} (check-data! nb base-rec (get-in cur [:labels "schema"]))
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
       :warnings (not-empty (concat schema-warnings (when labels (label-warnings nb labels))))})))

(defn- retirement!
  "Writes a revision of entry `id` on top of `base` with the current
   content unchanged and the retirement set to `retired`, nil to lift it."
  [nb id {:keys [base evidence actor key]} retired]
  (check-actor! actor)
  (check-tickets! nb (evidence-list evidence))
  (let [cur (or (current nb id) (refuse! :kajero/no-entry (str "no entry " id) {:entry id}))
        cur-hash (record/hash-of cur)]
    (when (not= base cur-hash)
      (refuse! :kajero/stale-base "the base is not the current revision" {:entry id :current cur-hash}))
    (let [rec (cond-> (-> cur
                          (dissoc :sig :retired)
                          (assoc :seq (inc (:seq cur)) :base cur-hash
                                 :evidence (evidence-list evidence) :actor actor :at (str (now))))
                retired (assoc :retired retired))]
      {:entry id
       :revision (try (insert-revision! nb rec key)
                      (catch clojure.lang.ExceptionInfo e
                        (if (= :kajero/stale-base (:reason (ex-data e)))
                          (refuse! :kajero/stale-base (ex-message e)
                                   {:entry id :current (some-> (current nb id) record/hash-of)})
                          (throw e))))})))

(defn retire!
  "Retires an entry: a new revision on top of `base`, its content
   unchanged, carrying the reason. Search, labels and the similar-entry
   check leave a retired entry out until it is restored; reading it by id
   and its history still work, and nothing is deleted."
  [nb id {:keys [reason] :as opts}]
  (when (str/blank? reason)
    (refuse! :kajero/no-reason "retiring an entry needs a reason" {:entry id}))
  (when (some-> (current nb id) :retired)
    (refuse! :kajero/retired (str "entry " id " is already retired") {:entry id}))
  (retirement! nb id opts {:reason reason}))

(defn restore!
  "Restores a retired entry: a new revision on top of `base`, its content
   unchanged and the retirement lifted."
  [nb id opts]
  (when (and (current nb id) (not (:retired (current nb id))))
    (refuse! :kajero/not-retired (str "entry " id " is not retired") {:entry id}))
  (retirement! nb id opts nil))

(defn- corrected!
  "The observation of entry `id` whose hash is `h`, refusing one the entry
   does not have or that a later observation already corrected."
  [nb id h]
  (let [obs (observations-of nb id)
        o (or (some #(when (= h (record/hash-of %)) %) obs)
              (refuse! :kajero/no-observation (format "entry %s has no observation %s" id h)
                       {:entry id :observation h}))]
    (when-let [c (some #(when (= h (:corrects %)) %) obs)]
      (refuse! :kajero/already-corrected
               (format "observation %s is already corrected by %s; correct that one instead" h (record/hash-of c))
               {:entry id :observation h :corrected-by (record/hash-of c)}))
    o))

(defn observe!
  "Records that `actor` checked a revision (the current one by default) and
   found it `held` or `failed`. A failed observation needs evidence.

   The outcome `missed` records that the revision held the answer but a
   search did not find it: its :selector is the selector that was run. It
   judges the entry's words and leaves the standing alone; the `missed`
   selector term picks entries whose current revision has one.

   With :corrects naming an earlier observation of the entry, the new one
   replaces its verdict: the earlier one stays in the history, marked
   corrected, and stops counting toward the standing. The outcome
   `withdrawn` takes a verdict back without giving a new one. A correction
   needs evidence saying what was wrong."
  [nb id {:keys [revision outcome evidence actor key corrects selector]}]
  (check-actor! actor)
  (when-not (or (#{"held" "failed" "missed"} outcome) (and corrects (= "withdrawn" outcome)))
    (refuse! :kajero/bad-outcome "an observation is held, failed or missed, or withdrawn when it corrects another"
             {:outcome outcome}))
  (if (= "missed" outcome)
    (do (when (str/blank? selector)
          (refuse! :kajero/no-selector "a missed observation needs the selector that did not find the entry" {}))
        (select/compile selector))
    (when (some? selector)
      (refuse! :kajero/bad-selector "only a missed observation carries a selector" {:outcome outcome})))
  (when (and (or (= "failed" outcome) corrects) (empty? (evidence-list evidence)))
    (refuse! :kajero/no-evidence (if corrects "a correction needs evidence" "a failed observation needs evidence") {}))
  (check-tickets! nb (evidence-list evidence))
  (let [revs (into {} (map (juxt record/hash-of identity)) (revisions-of nb id))
        cur (current nb id)
        earlier (when corrects (corrected! nb id corrects))
        target (or revision (if (= "withdrawn" outcome) (:revision earlier) (some-> cur record/hash-of)))]
    (when-not (contains? revs target)
      (refuse! :kajero/no-revision (format "entry %s has no revision %s" id target) {:entry id}))
    (let [rec (cond-> {:entry id :revision target :outcome outcome :evidence (evidence-list evidence)
                       :actor actor :at (str (now))}
                corrects (assoc :corrects corrects)
                (= "missed" outcome) (assoc :selector selector))
          hash (store/append! (:store nb) :observation rec (seal nb rec key))]
      {:observation hash :standing (:standing (entry nb id))})))

(def max-observations
  "How many observations one observe-many! call takes."
  500)

(defn observe-many!
  "Records several observations by `actor` at once, each a map of the
   arguments observe! takes plus its :entry, which may be a short id. Each
   is judged on its own, so a refused one leaves the others recorded: the
   result holds, in order, {:entry :observation :standing} for one that was
   recorded and {:entry :refused :message} for one that was not.

   A book that confirmed a dozen entries records them in one call; one
   call per entry is what keeps standing empty."
  [nb {:keys [observations actor key]}]
  (check-actor! actor)
  (when (empty? observations)
    (refuse! :kajero/no-observations "observe-many needs at least one observation" {}))
  (when (< max-observations (count observations))
    (refuse! :kajero/too-many-observations
             (format "observe-many takes at most %d observations, got %d" max-observations (count observations))
             {:count (count observations)}))
  (mapv (fn [{:keys [entry] :as o}]
          (let [id (resolve-id nb (str entry))]
            (try
              (assoc (observe! nb id (merge (select-keys o [:outcome :revision :evidence :corrects :selector])
                                            {:actor actor :key key}))
                     :entry id)
              (catch clojure.lang.ExceptionInfo e
                {:entry id :refused (some-> (:reason (ex-data e)) name) :message (ex-message e)}))))
        observations))

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

;; ------------------------------------------------------------- schemas

(defn schema-users
  "The entries whose current revision uses the schema entry `id`, each with
   the schema revision it was checked against and whether that is the
   schema's current one."
  [nb id]
  (let [now-rev (some-> (current nb id) record/hash-of)]
    (vec (for [e (entry-ids nb)
               :let [rev (current nb e)]
               :when (= id (get-in rev [:labels "schema"]))]
           {:entry e :title (:title rev) :schema-revision (get-in rev [:schema :revision])
            :current? (= now-rev (get-in rev [:schema :revision]))}))))

(defn migrate!
  "Moves every entry whose current revision uses the migration's :from
   schema onto its :to schema: a new revision per entry, with the data the
   steps produce, labelled schema=<to>, citing the migration revision as
   evidence and checked against the new schema like any other write. A
   deprecated :to schema is refused.

   Every entry is converted and checked before the first write; when one
   fails, nothing is written and the refusal lists them all. With
   :dry-run? it only reports what would happen. A write can still fail
   afterwards, most often because somebody revised the entry meanwhile;
   that entry is reported as stale or refused, keeps its schema, and the
   others are written, so running the migration again converts what is
   left."
  [nb migration-id {:keys [actor key dry-run?]}]
  (check-actor! actor)
  (let [mig (or (current nb migration-id)
                (refuse! :kajero/no-entry (str "no entry " migration-id) {:entry migration-id}))
        _ (when-not (= "migration" (get-in mig [:labels "kind"]))
            (refuse! :kajero/no-migration (str migration-id " is not labelled kind=migration") {:entry migration-id}))
        {:keys [from to] :as spec} (:data mig)
        _ (when-let [why (deprecation nb to (schema-entry! nb to))]
            (refuse! :kajero/deprecated-schema (str "the migration targets a deprecated schema: " why) {:schema to}))
        evidence [(str "kajero:migration " migration-id "@" (record/hash-of mig))]
        plan (vec (for [e (entry-ids nb)
                        :let [rev (current nb e)]
                        :when (= from (get-in rev [:labels "schema"]))]
                    (try
                      (let [new-data (migrate/run spec (:data rev))
                            labels (assoc (:labels rev) "schema" to)]
                        (check-data! nb {:labels labels :data new-data} from)
                        {:entry e :base (record/hash-of rev) :data new-data :labels labels})
                      (catch Exception ex
                        {:entry e :error (ex-message ex)}))))
        failed (filterv :error plan)]
    (cond
      dry-run? {:migration migration-id :entries (mapv #(dissoc % :labels :base) plan)}

      (seq failed)
      (refuse! :kajero/migration-failed
               (str (count failed) " of " (count plan) " entries do not convert: "
                    (pr-str (mapv #(select-keys % [:entry :error]) failed)))
               {:failed failed})

      :else
      {:migration migration-id
       :entries (mapv (fn [{:keys [entry base data labels]}]
                        (try
                          (let [{:keys [revision]} (revise! nb entry {:base base :data data :labels labels
                                                                      :evidence evidence :actor actor :key key})]
                            {:entry entry :status :migrated :revision revision})
                          (catch Exception ex
                            {:entry entry :status (if (= :kajero/stale-base (:reason (ex-data ex))) :stale :refused)
                             :error (ex-message ex)})))
                      plan)})))
