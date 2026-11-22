;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.core-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.store :as store])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)))

(defn- temp-dir []
  (str (Files/createTempDirectory "kajero" (make-array FileAttribute 0))))

(def ^:private ^:dynamic *backend* :sqlite)

(use-fixtures :each (fn [run-test] (doseq [backend [:sqlite :files]]
                                     (binding [*backend* backend]
                                       (testing (name backend) (run-test))))))

(defn- notebook
  ([] (notebook {}))
  ([opts] (k/notebook (merge (if (= :files *backend*)
                               {:dir (str (temp-dir) "/notes")}
                               {:path (str (temp-dir) "/notes.db")})
                             opts))))

(defmacro ^:private at
  [instant & body]
  `(binding [k/*now* (Instant/parse ~instant)] (let [res# (do ~@body)] res#)))

(defn- refusal
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(def ^:private gotcha
  {:title "Calico v3 is served by CRDs"
   :body "kubectl get apiservices shows Local for v3.projectcalico.org.\nRead the SERVICE column."
   :labels {"kind" "gotcha" "system" "calico"}
   :evidence ["kubectl get apiservices"]
   :actor "claude"})

(deftest an-entry-is-written-and-read-back
  (let [nb (notebook)
        {:keys [entry revision warnings]} (k/write! nb gotcha)
        e (k/entry nb entry)]
    (is (= revision (:revision e)))
    (is (= "Calico v3 is served by CRDs" (:title e)))
    (is (false? (:signed? e)))
    (is (nil? warnings) "the first entry has nothing to compare its labels with")))

(deftest an-entry-needs-an-actor-a-title-and-a-label
  (let [nb (notebook)]
    (is (= :kajero/no-actor (refusal #(k/write! nb (dissoc gotcha :actor)))))
    (is (= :kajero/no-title (refusal #(k/write! nb (assoc gotcha :title " ")))))
    (is (= :kajero/no-labels (refusal #(k/write! nb (assoc gotcha :labels {})))))))

(deftest a-new-label-warns-and-names-what-is-in-use
  (let [nb (notebook)]
    (k/write! nb gotcha)
    (is (= ["new label system=k8s; in use: system=calico (1)"]
           (:warnings (k/write! nb (assoc gotcha :title "x" :labels {"kind" "gotcha" "system" "k8s"})))))
    (is (= ["new label key team; keys in use: kind, system"]
           (:warnings (k/write! nb (assoc gotcha :title "y" :labels {"kind" "gotcha" "team" "sre"})))))))

(deftest a-write-reports-entries-that-look-the-same
  (let [nb (notebook)
        {first-id :entry} (k/write! nb gotcha)]
    (is (= [{:entry first-id :title (:title gotcha)}]
           (:similar (k/write! nb (assoc gotcha :body "other words")))))))

(deftest a-revision-needs-the-current-revision-as-its-base
  (let [nb (notebook)
        {:keys [entry revision]} (k/write! nb gotcha)
        {second-rev :revision} (k/revise! nb entry {:base revision :body "new body" :actor "seb"})]
    (is (= "new body" (:body (k/entry nb entry))))
    (is (= (:labels gotcha) (:labels (k/entry nb entry))) "what a revision leaves out stays")
    (testing "a writer working from an older revision is refused"
      (is (= :kajero/stale-base (refusal #(k/revise! nb entry {:base revision :body "lost" :actor "claude"})))))
    (is (= second-rev (:revision (k/entry nb entry))))))

(deftest observations-make-the-standing
  (let [nb (notebook)
        {:keys [entry]} (at "2026-11-01T00:00:00Z" (k/write! nb gotcha))]
    (at "2026-11-02T00:00:00Z" (k/observe! nb entry {:outcome "held" :evidence ["kubectl get apiservices"] :actor "claude"}))
    (at "2026-11-03T00:00:00Z" (k/observe! nb entry {:outcome "held" :evidence ["again"] :actor "claude"}))
    (at "2026-11-04T00:00:00Z" (k/observe! nb entry {:outcome "held" :evidence ["mine"] :actor "seb"}))
    (let [standing (at "2026-11-05T00:00:00Z" (:standing (k/entry nb entry)))]
      (is (= 2 (:held standing)) "one actor counts once per window")
      (is (= "2026-11-04T00:00:00Z" (:last-held standing)))
      (is (false? (:disputed? standing))))))

(deftest a-failed-observation-disputes-until-a-new-revision-answers
  (let [nb (notebook)
        {:keys [entry revision]} (k/write! nb gotcha)]
    (is (= :kajero/no-evidence (refusal #(k/observe! nb entry {:outcome "failed" :actor "claude"}))))
    (k/observe! nb entry {:outcome "failed" :evidence ["kubectl output shows calico-apiserver"] :actor "claude"})
    (is (true? (get-in (k/entry nb entry) [:standing :disputed?])))
    (k/revise! nb entry {:base revision :body "depends on the Calico version" :actor "claude"})
    (is (false? (get-in (k/entry nb entry) [:standing :disputed?])) "the new revision starts with a clean record")))

(deftest an-unconfirmed-fact-goes-stale
  (let [nb (notebook)
        {:keys [entry]} (at "2026-01-01T00:00:00Z" (k/write! nb (assoc gotcha :labels {"kind" "fact"})))]
    (is (false? (at "2026-03-01T00:00:00Z" (get-in (k/entry nb entry) [:standing :stale?]))))
    (is (true? (at "2026-05-01T00:00:00Z" (get-in (k/entry nb entry) [:standing :stale?]))))
    (at "2026-05-02T00:00:00Z" (k/observe! nb entry {:outcome "held" :evidence ["checked"] :actor "seb"}))
    (is (false? (at "2026-05-03T00:00:00Z" (get-in (k/entry nb entry) [:standing :stale?]))))))

(deftest search-uses-the-tik-where-grammar
  (let [nb (notebook)
        {calico :entry} (k/write! nb gotcha)
        {dns :entry} (k/write! nb {:title "CoreDNS caches for 30s" :labels {"kind" "fact" "system" "dns"}
                                   :actor "seb"})
        ids #(set (map :entry (k/search nb %)))]
    (is (= #{calico dns} (ids "")))
    (is (= #{calico} (ids "label:system=calico")))
    (is (= #{dns} (ids "label:kind and not label:system=calico")))
    (is (= #{dns} (ids "actor=seb")))
    (is (= #{calico} (ids "~apiservices")))
    (is (= #{calico dns} (ids "unsigned")))
    (is (= :select/bad-term (refusal #(k/search nb "stage=:blocked"))))))

(deftest a-superseded-entry-is-selectable
  (let [nb (notebook)
        {old :entry} (k/write! nb gotcha)
        {new :entry} (k/write! nb (assoc gotcha :title "Calico v3 depends on the version"))]
    (k/link! nb {:source new :type "supersedes" :target old :actor "claude"})
    (is (= [old] (map :entry (k/search nb "superseded"))))
    (is (= :kajero/bad-link (refusal #(k/link! nb {:source new :type "likes" :target old :actor "claude"}))))))

(deftest history-lists-every-record-with-its-hash
  (let [nb (notebook)
        {:keys [entry revision]} (k/write! nb gotcha)]
    (k/observe! nb entry {:outcome "held" :evidence ["x"] :actor "seb"})
    (let [h (k/history nb entry)]
      (is (= [:revision :observation] (map :kind h)))
      (is (= revision (:hash (first h)))))))

;; ---------------------------------------------------------------- signing

(defn- keypair
  "A fresh ed25519 key and an actors file naming it, or nil without ssh-keygen."
  [actor]
  (let [dir (temp-dir)
        key (str dir "/id_ed25519")]
    (when (zero? (:exit (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-f" key)))
      (spit (str dir "/actors") (str actor " namespaces=\"kajero\" " (slurp (str key ".pub"))))
      {:key key :actors (str dir "/actors")})))

(deftest a-required-notebook-takes-only-verified-signatures
  (when-let [{:keys [key actors]} (keypair "claude")]
    (let [nb (notebook {:signing :required :actors actors})]
      (is (= :kajero/unsigned (refusal #(k/write! nb gotcha))))
      (let [{:keys [entry]} (k/write! nb (assoc gotcha :key key))]
        (is (true? (:signed? (k/entry nb entry))))
        (is (= #{} (set (map :entry (k/search nb "unsigned"))))))
      (testing "a key the actors file does not give to that actor"
        (is (= :kajero/bad-signature (refusal #(k/write! nb (assoc gotcha :actor "seb" :key key)))))))))

(deftest an-optional-notebook-takes-both
  (when-let [{:keys [key actors]} (keypair "claude")]
    (let [nb (notebook {:actors actors})]
      (is (false? (:signed? (k/entry nb (:entry (k/write! nb gotcha))))))
      (is (true? (:signed? (k/entry nb (:entry (k/write! nb (assoc gotcha :title "z" :key key))))))))))

(deftest a-revision-number-lands-once-whoever-writes-it
  (let [nb (notebook)
        {:keys [entry]} (k/write! nb gotcha)
        rec {:entry entry :seq 2 :title "a" :labels {"kind" "fact"} :actor "claude" :at "2026-11-21T00:00:00Z"}]
    (store/append! (:store nb) :revision rec nil)
    (is (= :kajero/stale-base (refusal #(store/append! (:store nb) :revision (assoc rec :title "b") nil))))
    (is (= "a" (:title (k/entry nb entry))))))

(deftest flat-files-are-plain-edn
  (when (= :files *backend*)
    (let [dir (str (temp-dir) "/notes")
          nb (k/notebook {:dir dir})
          {:keys [entry]} (k/write! nb gotcha)]
      (is (= "Calico v3 is served by CRDs"
             (:title (read-string (slurp (io/file dir "entries" entry "revisions" "00000001.edn")))))))))

(deftest temp-files-stay-out-of-the-repository
  (is (not (.exists (io/file "notes.db")))))

(deftest links-are-selectable
  (let [nb (notebook)
        hub (:entry (k/write! nb gotcha))
        a (:entry (k/write! nb (assoc gotcha :title "a")))
        b (:entry (k/write! nb (assoc gotcha :title "b")))
        c (:entry (k/write! nb (assoc gotcha :title "c")))
        lone (:entry (k/write! nb (assoc gotcha :title "lone")))
        ids #(set (map :entry (k/search nb %)))]
    (k/link! nb {:source a :type "see-also" :target hub :actor "claude"})
    (k/link! nb {:source hub :type "see-also" :target b :actor "claude"})
    (k/link! nb {:source c :type "supersedes" :target hub :actor "claude"})
    (testing "link=<id> picks the entries linked to it, in either direction, not itself"
      (is (= #{a b c} (ids (str "link=" hub)))))
    (testing "an eight-character prefix names the entry as it does elsewhere"
      (is (= #{a b c} (ids (str "link=" (subs hub 0 8))))))
    (testing "link:<type>=<id> narrows to one type"
      (is (= #{a b} (ids (str "link:see-also=" hub))))
      (is (= #{c} (ids (str "link:supersedes=" hub))))
      (is (= #{} (ids (str "link:contradicts=" hub)))))
    (testing "the term composes with not, and an entry without links matches nothing"
      (is (= #{hub lone} (ids (str "not link=" hub))))
      (is (= #{} (ids (str "link=" lone)))))
    (testing "an unknown link type or a too-short id is a bad term"
      (is (= :select/bad-term (refusal #(k/search nb (str "link:likes=" hub)))))
      (is (= :select/bad-term (refusal #(k/search nb "link=abc")))))))

(deftest an-entry-is-found-by-a-path-it-names
  (let [nb (notebook)
        short (:entry (k/write! nb (assoc gotcha :title "issuer"
                                          :body "derived in `templates/_cert-issuer.tpl`")))
        long (:entry (k/write! nb (assoc gotcha :title "issuer, full"
                                         :body "see helm-charts/cluster-info/templates/_cert-issuer.tpl")))
        cited (:entry (k/write! nb (assoc gotcha :title "in evidence" :body "no path here"
                                          :evidence ["helm-charts/cluster-info/templates/_cert-issuer.tpl@abc123"])))
        other (:entry (k/write! nb (assoc gotcha :title "other" :body "templates/_helpers.tpl")))
        ids #(set (map :entry (k/search nb %)))]
    (is (= #{short long cited} (ids "path=helm-charts/cluster-info/templates/_cert-issuer.tpl")))
    (is (= #{short long cited} (ids "path=_cert-issuer.tpl")))
    (is (= #{other} (ids "path=_helpers.tpl")))
    (is (= #{other} (ids "label:kind not path=_cert-issuer.tpl")))
    (is (= :select/bad-term (refusal #(k/search nb "path="))))))
