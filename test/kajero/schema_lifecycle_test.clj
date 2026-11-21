;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.schema-lifecycle-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.migrate :as migrate])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private ^:dynamic *backend* :sqlite)

(use-fixtures :each (fn [run-test] (doseq [backend [:sqlite :files]]
                                     (binding [*backend* backend]
                                       (testing (name backend) (run-test))))))

(defn- notebook []
  (let [dir (str (Files/createTempDirectory "kajero" (make-array FileAttribute 0)))]
    (k/notebook (if (= :files *backend*) {:dir (str dir "/notes")} {:path (str dir "/notes.db")}))))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(def ^:private v1 [:vector [:map [:name :string] [:cpus :int]]])
(def ^:private v2 [:vector [:map [:host :string] [:cores :int] [:role [:enum "worker" "control-plane"]]]])

(defn- schema! [nb title form]
  (k/write! nb {:title title :labels {"kind" "schema"} :data form :actor "claude"}))

(defn- nodes! [nb schema title rows]
  (k/write! nb {:title title :labels {"kind" "fact" "schema" schema} :data rows :actor "claude"}))

(defn- migration! [nb from to steps]
  (k/write! nb {:title "nodes v1 to v2" :labels {"kind" "migration"}
                :data {:from from :to to :steps steps} :actor "claude"}))

(def ^:private steps [[:rename :name :host] [:rename :cpus :cores] [:default :role "worker"]])

;; ------------------------------------------------------------ the steps

(deftest steps-rewrite-maps-and-lists-of-maps
  (is (= [{:host "w-1" :cores 8 :role "worker"}]
         (migrate/run {:steps steps} [{:name "w-1" :cpus 8}])))
  (is (= {:host "w-1" :cores 8 :role "control-plane"}
         (migrate/run {:steps steps} {:name "w-1" :cpus 8 :role "control-plane"}))
      "a default leaves a present value alone")
  (is (= "a string" (migrate/run {:steps steps} "a string")) "data that is no map passes through"))

(deftest every-step-does-one-thing
  (let [row {:a 1 :b "x" :nested {:deep 2}}]
    (is (= {:b "x" :nested {:deep 2}} (migrate/run {:steps [[:drop :a]]} row)))
    (is (= (assoc row :a 9) (migrate/run {:steps [[:set :a 9]]} row)))
    (is (= (assoc row :b "y") (migrate/run {:steps [[:map-values :b {"x" "y"}]]} row)))
    (is (= row (migrate/run {:steps [[:map-values :b {"z" "y"}]]} row)) "an unlisted value stays")
    (is (= {:a 1 :b "x" :nested {} :top 2} (migrate/run {:steps [[:move [:nested :deep] [:top]]]} row)))
    (is (= row (migrate/run {:steps [[:move [:missing] [:top]]]} row)) "moving nothing changes nothing")
    (is (= row (migrate/run {:steps [[:rename :missing :other]]} row)) "renaming nothing adds nothing")))

(deftest a-migration-is-data-and-never-code
  (is (nil? (migrate/explain {:from "a" :to "b" :steps steps})))
  (is (some? (migrate/explain {:from "a" :to "b" :steps [[:eval '(System/exit 1)]]})) "unknown steps are refused")
  (is (some? (migrate/explain {:from "a" :to "b" :steps [[:rename :a :b]] :hook '(fn [x] x)})) "extra keys are refused")
  (is (some? (migrate/explain {:from "a" :to "b" :steps [[:move [] [:x]]]})) "an empty path is refused"))

;; ------------------------------------------------------ deprecated schemas

(deftest a-deprecated-schema-takes-no-new-entries-but-old-ones-stay-correctable
  (let [nb (notebook)
        {s1 :entry s1-rev :revision} (schema! nb "Nodes v1" v1)
        {e :entry e-rev :revision} (nodes! nb s1 "staging nodes" [{:name "w-1" :cpus 8}])]
    (k/revise! nb s1 {:base s1-rev :labels {"kind" "schema" "deprecated" "use Nodes v2"} :actor "seb"})
    (is (= :kajero/deprecated-schema (refusal #(nodes! nb s1 "prod nodes" [{:name "p-1" :cpus 4}]))))
    (let [{:keys [warnings]} (k/revise! nb e {:base e-rev :body "corrected" :actor "claude"})]
      (is (some #(re-find #"deprecated" %) warnings) "the revision warns"))))

(deftest a-superseded-schema-names-its-successor
  (let [nb (notebook)
        {s1 :entry} (schema! nb "Nodes v1" v1)
        {s2 :entry} (schema! nb "Nodes v2" v2)]
    (k/link! nb {:source s2 :type "supersedes" :target s1 :actor "seb"})
    (let [e (try (nodes! nb s1 "prod" [{:name "p" :cpus 1}]) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :kajero/deprecated-schema (:reason (ex-data e))))
      (is (= s2 (:successor (ex-data e))))
      (is (re-find (re-pattern s2) (ex-message e))))))

;; ------------------------------------------------------- who uses a schema

(deftest a-schema-lists-its-users-and-whether-they-are-current
  (let [nb (notebook)
        {s :entry s-rev :revision} (schema! nb "Nodes" v1)
        {a :entry} (nodes! nb s "a" [{:name "a" :cpus 1}])]
    (k/revise! nb s {:base s-rev :data [:vector [:map [:name :string] [:cpus :int] [:zone {:optional true} :string]]]
                     :actor "claude"})
    (let [{b :entry} (nodes! nb s "b" [{:name "b" :cpus 2}])
          users (into {} (map (juxt :entry :current?)) (:used-by (k/entry nb s)))]
      (is (= {a false b true} users))
      (testing "selectors find outdated entries and entries by schema revision"
        (is (= [a] (map :entry (k/search nb "outdated-schema"))))
        (is (= [b] (map :entry (k/search nb (str "schema-revision=" (get-in (k/entry nb b) [:schema :revision]))))))
        (is (= #{a b} (set (map :entry (k/search nb (str "label:schema=" s))))))))))

;; -------------------------------------------------------------- migrations

(deftest a-migration-moves-every-entry-onto-the-new-schema
  (let [nb (notebook)
        {s1 :entry} (schema! nb "Nodes v1" v1)
        {s2 :entry} (schema! nb "Nodes v2" v2)
        {a :entry} (nodes! nb s1 "a" [{:name "a" :cpus 1}])
        {b :entry} (nodes! nb s1 "b" [{:name "b" :cpus 2}])
        {other :entry} (k/write! nb {:title "unrelated" :labels {"kind" "fact"} :data [{:name "x"}] :actor "claude"})
        {m :entry m-rev :revision} (migration! nb s1 s2 steps)]
    (testing "a dry run writes nothing"
      (let [{:keys [entries]} (k/migrate! nb m {:actor "claude" :dry-run? true})]
        (is (= #{a b} (set (map :entry entries))))
        (is (= s1 (get-in (k/entry nb a) [:labels "schema"])))))
    (let [{:keys [entries]} (k/migrate! nb m {:actor "claude"})]
      (is (= #{:migrated} (set (map :status entries))))
      (is (= [{:host "a" :cores 1 :role "worker"}] (:data (k/entry nb a))))
      (is (= s2 (get-in (k/entry nb b) [:labels "schema"])))
      (is (= s2 (get-in (k/entry nb b) [:schema :entry])))
      (is (= [(str "kajero:migration " m "@" m-rev)] (:evidence (k/entry nb a)))
          "the revision cites the migration revision it ran")
      (is (= [{:name "x"}] (:data (k/entry nb other))) "entries on other schemas are untouched"))
    (testing "running it again finds nothing left to do"
      (is (= [] (:entries (k/migrate! nb m {:actor "claude"})))))))

(deftest a-migration-that-breaks-one-entry-writes-nothing
  (let [nb (notebook)
        {s1 :entry} (schema! nb "Nodes v1" v1)
        {s2 :entry} (schema! nb "Nodes v2" v2)
        {a :entry} (nodes! nb s1 "a" [{:name "a" :cpus 1}])
        {m :entry} (migration! nb s1 s2 [[:rename :name :host] [:rename :cpus :cores] [:default :role "manager"]])]
    (is (= :kajero/migration-failed (refusal #(k/migrate! nb m {:actor "claude"}))))
    (is (= s1 (get-in (k/entry nb a) [:labels "schema"])) "nothing was written")
    (testing "the dry run shows why"
      (is (re-find #"does not fit" (:error (first (:entries (k/migrate! nb m {:actor "claude" :dry-run? true})))))))))

(deftest a-migration-entry-must-name-two-schemas
  (let [nb (notebook)
        {s1 :entry} (schema! nb "Nodes v1" v1)
        {fact :entry} (k/write! nb {:title "f" :labels {"kind" "fact"} :actor "claude"})]
    (is (= :kajero/no-schema (refusal #(migration! nb s1 fact steps))))
    (is (= :kajero/no-schema (refusal #(migration! nb s1 "no-such-entry" steps))))
    (is (= :kajero/bad-migration (refusal #(migration! nb s1 s1 [[:eval "rm -rf /"]]))))
    (is (= :kajero/no-migration (refusal #(k/migrate! nb fact {:actor "claude"}))) "only migration entries run")
    (is (= :kajero/no-actor (refusal #(k/migrate! nb fact {}))) "a migration names who ran it")))

(deftest a-step-that-can-not-apply-is-reported-not-thrown
  (let [nb (notebook)
        {s1 :entry} (schema! nb "Free" [:vector [:map [:name :string] [:zone :string]]])
        {s2 :entry} (schema! nb "Nested" [:vector :any])
        {a :entry} (nodes! nb s1 "a" [{:name "a" :zone "z"}])
        {m :entry} (migration! nb s1 s2 [[:move [:name] [:zone :first]]])]
    (is (= :kajero/migration-failed (refusal #(k/migrate! nb m {:actor "claude"})))
        "moving a value below a string fails for that entry")
    (is (= [{:name "a" :zone "z"}] (:data (k/entry nb a))))))

(deftest no-revision-moves-onto-a-deprecated-schema
  (let [nb (notebook)
        {s1 :entry s1-rev :revision} (schema! nb "Nodes v1" v1)
        {s2 :entry} (schema! nb "Nodes v2" v1)
        {e :entry e-rev :revision} (nodes! nb s2 "nodes" [{:name "a" :cpus 1}])]
    (k/revise! nb s1 {:base s1-rev :labels {"kind" "schema" "deprecated" "true"} :actor "seb"})
    (is (= :kajero/deprecated-schema
           (refusal #(k/revise! nb e {:base e-rev :labels {"kind" "fact" "schema" s1} :actor "claude"}))))
    (is (= s2 (get-in (k/entry nb e) [:labels "schema"])))))

(deftest a-migration-onto-a-deprecated-schema-is-refused
  (let [nb (notebook)
        {s1 :entry} (schema! nb "Nodes v1" v1)
        {s2 :entry s2-rev :revision} (schema! nb "Nodes v2" v2)
        {m :entry} (migration! nb s1 s2 steps)]
    (nodes! nb s1 "a" [{:name "a" :cpus 1}])
    (k/revise! nb s2 {:base s2-rev :labels {"kind" "schema" "deprecated" "true"} :actor "seb"})
    (is (= :kajero/deprecated-schema (refusal #(k/migrate! nb m {:actor "claude"}))))))
