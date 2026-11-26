;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.search-equivalence-test
  "Search computes only the row keys a selector reads and summarises only
   the entries it returns. These tests hold it to a reference search that
   builds the full view of every entry first, over a generated notebook
   and many generated selectors."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.cite :as cite]
            [kajero.core :as k]
            [kajero.data-query :as dq]
            [kajero.derived :as derived]
            [kajero.rank :as rank]
            [kajero.record :as record]
            [kajero.select :as select]
            [kajero.standing :as standing]
            [kajero.store :as store]
            [kajero.test-keys :as keys]
            [kajero.test-temp :as temp]
            [kajero.tik :as tik]
            [kajero.usage :as usage]
            [kajero.verify :as verify])
  (:import (java.time Instant)
           (java.util Random)))

(use-fixtures :each temp/each)

;; ------------------------------------------------------ the reference

(defn- counted
  [observations]
  (let [corrected (into #{} (keep :corrects) observations)]
    (remove #(or (= "withdrawn" (:outcome %)) (corrected (derived/hash-of %))) observations)))

(defn- reference-view
  [nb entry]
  (let [rev (k/current nb entry)
        authorship (store/authorship (:store nb) entry)
        rev-hash (derived/hash-of rev)
        all-observations (store/observations (:store nb) entry)
        of-current (filter #(= rev-hash (:revision %)) (counted all-observations))
        links (store/links (:store nb) entry)
        state (:signature (verify/verdict (:verifier nb) rev rev-hash))]
    {:entry entry :revision rev-hash :title (:title rev) :body (:body rev) :labels (:labels rev)
     :at (:at rev) :evidence (:evidence rev) :data (:data rev) :schema (:schema rev)
     :signed? (= :verified state) :signature state
     :standing (standing/derive-standing rev of-current k/*now*)
     :missed (filterv #(= "missed" (:outcome %)) of-current)
     :links (mapv #(select-keys % [:source :type :target]) links)
     :superseded? (boolean (some #(and (= "supersedes" (:type %)) (= entry (:target %))) links))
     :retired (:retired rev)
     :outdated-schema? (boolean (when-let [{schema-entry :entry pinned :revision} (:schema rev)]
                                  (not= pinned (some-> (k/current nb schema-entry) derived/hash-of))))
     :actors (into (:actors authorship) (map :actor) all-observations)
     :ticket-ids (into (set (tik/ticket-ids (:evidence authorship)))
                       (mapcat (comp tik/ticket-ids :evidence)) all-observations)}))

(defn- reference-row
  [{:keys [entry labels links standing signed? superseded? retired actors ticket-ids outdated-schema? missed] :as v}]
  {:labels labels
   :links (into #{} (map (fn [{:keys [source type target]}] [type (if (= entry source) target source)])) links)
   :data (:data v)
   :schema-revision (get-in v [:schema :revision])
   :outdated-schema? outdated-schema?
   :actors actors
   :tickets ticket-ids
   :paths (derived/cited-paths (:revision v) v)
   :haystack (derived/haystack (:revision v) v)
   :disputed? (:disputed? standing)
   :stale? (:stale? standing)
   :missed? (boolean (seq missed))
   :unsigned? (= :unsigned (:signature v))
   :bad-signature? (= :bad-signature (:signature v))
   :unverified? (not signed?)
   :superseded? superseded?
   :retired? (some? retired)})

(defn- reference-search
  "kajero.core/search as it was before it computed rows lazily: the full
   view of every entry, then the filter."
  [nb selector]
  (let [pred (cond-> (select/compile selector)
               (not (select/names? selector "retired")) (every-pred (complement :retired?)))
        counts (when (select/reads-usage? selector) (usage/counts (:usage nb)))
        row-of (if counts (fn [v] (assoc (reference-row v) :usage (usage/of-entry counts (:entry v)))) reference-row)
        terms (select/text-terms selector)
        data-terms (not-empty (select/data-terms selector))
        views (mapv #(reference-view nb %) (store/entries (:store nb)))
        citations (when (seq terms) (cite/incoming views))
        ranked (fn [v] (if (seq terms)
                         (let [text (derived/search-text (:revision v) v)
                               score (rank/score text terms)
                               cited (get citations (:entry v) 0)]
                           (assoc v :score score :citations cited :rank (+ score (cite/boost cited))
                                  :matches (rank/matches text terms)))
                         v))]
    (->> views
         (filter (comp pred row-of))
         (map ranked)
         (sort (fn [x y] (let [by-rank (compare (:rank y 0) (:rank x 0))]
                           (if (zero? by-rank) (compare (:at y) (:at x)) by-rank))))
         (mapv (fn [v]
                 (-> (select-keys v [:entry :revision :title :labels :at :standing :signature :score :citations :rank :matches])
                     (cond-> (:retired v) (assoc :retired (:retired v))
                             data-terms (assoc :rows (dq/matching (:data v) data-terms)))
                     (assoc :summary (first (str/split-lines (or (:body v) ""))))))))))

;; ---------------------------------------------- a generated notebook

(def ^:private words
  ["calico" "coredns" "gateway" "İstanbul" "istanbul" "straße" "STRASSE" "ß" "Flux" "velero" "ﬁle"])

(def ^:private tickets
  ["5084203b-b89a-479e-9075-02fdf34806d9" "6afff677-67e5-45a0-aadb-551ef2348cdd"])

(defn- pick [^Random r xs] (nth xs (.nextInt r (count xs))))
(defn- chance [^Random r p] (< (.nextDouble r) p))

(defn- day [^Random r] (Instant/parse (format "2026-%02d-%02dT10:00:00Z" (inc (.nextInt r 11)) (inc (.nextInt r 28)))))

(defmacro ^:private on
  "Runs `body` on a day `r` picks; a write the notebook refuses, such as
   retiring an entry twice or recording an observation it already holds,
   is skipped."
  [r & body]
  `(binding [k/*now* (day ~r)]
     (try (let [res# (do ~@body)] res#) (catch Exception _# nil))))

(defn- body [^Random r ids]
  (str/join (pick r ["\n" "\r\n" "\n\n"])
            (for [_ (range (inc (.nextInt r 4)))]
              (str/join " " (concat (repeatedly (inc (.nextInt r 5)) #(pick r words))
                                    (when (and (seq ids) (chance r 0.3)) [(str "[[" (subs (pick r ids) 0 8) "]]")])
                                    (when (chance r 0.2) [(pick r ["templates/a.tpl" "charts/x/templates/a.tpl" "src/b.clj"])]))))))

(defn- generate!
  "Writes a notebook of `n` entries with revisions, observations of every
   outcome, corrections, links, retirements, schemas, every signature
   state and usage counts, all chosen by `seed`. Returns the notebook,
   its entry ids and its schema entry, or nil without ssh-keygen."
  [where seed n]
  (when-let [claude (keys/key-file)]
    (let [r (Random. seed)
          seb (keys/key-file)
          foreign (keys/key-file)
          actors (keys/write-actors! (str (temp/temp-dir) "/actors") [["claude" claude] ["seb" seb]])
          nb (k/notebook (assoc where :actors actors))
          writer (fn [] (pick r [{:actor "claude" :key claude} {:actor "seb" :key seb} {:actor "claude"}]))
          schema (on r (:entry (k/write! nb (merge {:actor "claude" :key claude}
                                                   {:title "Hosts" :labels {"kind" "schema"}
                                                    :data [:map [:hosts [:vector [:map [:domain :string] [:flavor :string]]]]]}))))
          ids (atom [schema])]
      (dotimes [_ n]
        (let [labels (cond-> {"kind" (pick r ["fact" "gotcha" "howto"])}
                       (chance r 0.7) (assoc "component" (pick r ["calico" "dns" "flux"]))
                       (chance r 0.2) (assoc "schema" schema))
              data (cond
                     (get labels "schema") {:hosts (vec (for [_ (range (inc (.nextInt r 3)))]
                                                          {:domain (pick r ["a.example" "b.example"])
                                                           :flavor (pick r ["gw" "web"])}))}
                     (chance r 0.3) {:n (.nextInt r 5) :tags [(pick r words)]})
              id (on r (if (chance r 0.1)
                         (let [id (str (random-uuid))
                               rec {:entry id :seq 1 :base nil :title (pick r words) :body (body r @ids)
                                    :labels {"kind" "fact"} :evidence [] :actor "claude" :at (str k/*now*)}]
                           (store/append! (:store nb) :revision
                                          (cond-> rec (chance r 0.5) (assoc :body "changed after signing"))
                                          (record/sign (pick r [foreign claude]) rec))
                           id)
                         (:entry (k/write! nb (merge (writer)
                                                     {:title (str (pick r words) " " (pick r words))
                                                      :body (body r @ids) :labels labels
                                                      :evidence (cond-> [(pick r ["kubectl get pods" "src/b.clj"])]
                                                                  (chance r 0.4) (conj (str "tik:" (pick r tickets))))}
                                                     (when data {:data data}))))))]
          (swap! ids conj id)))
      (doseq [id @ids]
        (on r
            (when (chance r 0.3)
              (k/revise! nb id (merge (writer) {:base (:revision (k/entry nb id)) :body (body r @ids)
                                                :evidence [(str "tik:" (pick r tickets))]}))))
        (on r
            (dotimes [_ (.nextInt r 3)]
              (let [outcome (pick r ["held" "failed" "missed"])]
                (k/observe! nb id (merge (writer) {:outcome outcome :evidence ["checked"]}
                                         (when (= "missed" outcome) {:selector (pick r words)}))))))
        (on r
            (when (chance r 0.1)
              (when-let [o (first (filter #(= :observation (:kind %)) (k/history nb id)))]
                (k/observe! nb id (merge (writer) {:outcome (pick r ["withdrawn" "held"]) :corrects (:hash o)
                                                   :evidence ["wrong check"]})))))
        (on r
            (when (chance r 0.2)
              (k/link! nb (merge (writer) {:source id :type (pick r ["see-also" "supersedes" "contradicts"])
                                           :target (pick r @ids)}))))
        (on r
            (when (chance r 0.08)
              (k/retire! nb id (merge (writer) {:base (:revision (k/entry nb id)) :reason "a test"})))))
      (on r (k/revise! nb schema {:base (:revision (k/entry nb schema)) :actor "claude" :key claude
                                  :body "every host" :evidence []}))
      (let [session (k/start-session! nb)]
        (dotimes [_ 5] (k/search! nb session (pick r words)))
        (dotimes [_ 5] (k/open! nb session (pick r @ids))))
      {:nb nb :ids @ids :schema schema})))

(defn- terms
  [{:keys [ids schema nb]}]
  (let [id (second ids)]
    ["" "label:kind" "label:kind=fact" "label:component=calico" "label:nope" "coverage"
     "data:hosts" "data:hosts.domain=a.example" "data:n=3" "data:tags=Flux"
     "data:hosts.domain=a.example data:hosts.flavor=gw"
     "~calico" "calico" "~İstanbul" "~istanbul" "~i̇stanbul" "~straße" "~STRASSE" "~strasse" "~ß" "~ﬁle" "~file"
     "path=templates/a.tpl" "path=a.tpl" "path=src/b.clj"
     "actor=seb" "actor=claude" (str "ticket=" (first tickets)) (str "ticket=" (second tickets))
     (str "link=" (subs id 0 8)) (str "link:see-also=" id) (str "link:supersedes=" (subs id 0 6))
     "disputed" "stale" "unsigned" "bad-signature" "unverified" "superseded" "retired" "missed"
     "outdated-schema"
     (str "schema-revision=" (some #(get-in (k/current nb %) [:schema :revision]) ids))
     (str "schema-revision=" (:hash (first (k/history nb schema))))
     "never-returned" "returned-unopened=1"
     "calico|label:kind=fact" "unsigned|~gateway" "~velero|~coredns|bad-signature" "stale|disputed"]))

(defn- random-selector [^Random r pool]
  (str/join " " (for [_ (range (.nextInt r 4))]
                  (str (when (chance r 0.3) "not ") (pick r pool)))))

(defn- outcome
  [f]
  (try (f) (catch clojure.lang.ExceptionInfo e [:refused (:reason (ex-data e))])))

(def ^:private now (Instant/parse "2026-11-26T18:00:00Z"))

(deftest search-returns-what-the-full-view-search-returned
  (doseq [[backend where] [["files" {:dir (str (temp/temp-dir) "/notes")}]
                           ["sqlite" {:path (str (temp/temp-dir) "/notes.db")}]]]
    (testing backend
      (when-let [g (generate! where 20261126 70)]
        (let [nb (:nb g)
              pool (terms g)
              r (Random. 7)
              selectors (concat pool (repeatedly 400 #(random-selector r pool)))]
          (binding [k/*now* now]
            (doseq [s selectors]
              (is (= (outcome #(reference-search nb s)) (outcome #(k/search nb s))) s))
            (testing "every term picks something somewhere, so the comparison is not empty"
              (is (< 10 (count (filter #(seq (outcome (fn [] (k/search nb %)))) pool)))))
            (testing "recent is the head of the empty search"
              (is (= (take 12 (reference-search nb "")) (k/recent nb 12))))))))))

(deftest the-first-line-is-split-lines-first
  (doseq [b [nil "" "a" "a\nb" "a\r\nb" "\nb" "\n" "\n\n" "\r\n" "\r\n\r\n" "\r" "a\r" "\r\r\n" "\n\r\n x"
             "İstanbul\nß" "\n\nabc" " \n" "abc\n" "abc\r\n"]]
    (is (= (first (str/split-lines (or b ""))) (k/first-line b)) (pr-str b))))
