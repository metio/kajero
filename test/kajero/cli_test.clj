;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.cli-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.cli :as cli]
            [kajero.core :as k]
            [kajero.skills :as skills]
            [kajero.test-keys :as tk]
            [kajero.test-temp :as temp]))

(use-fixtures :each temp/each)

(defn- notebook []
  (k/notebook {:path (str (temp/temp-dir) "/notes.db")}))

(deftest observe-many-reads-its-observations-as-edn-from-stdin
  (let [nb (notebook)
        a (:entry (k/write! nb {:title "one" :labels {"kind" "fact"} :actor "seb"}))
        b (:entry (k/write! nb {:title "two" :labels {"kind" "fact"} :actor "seb"}))
        results (with-redefs [cli/writer (constantly {:actor "claude"})]
                  (with-in-str (pr-str [{:entry a :outcome "held" :evidence ["book"]}
                                        {:entry b :outcome "held"}])
                    (#'cli/run-command nb ["observe-many" "-"])))]
    (is (= [a b] (mapv :entry results)))
    (is (= [1 1] (mapv #(get-in (k/entry nb %) [:standing :held]) [a b])))))

(deftest observe-records-a-missed-search-with-its-selector
  (let [nb (notebook)
        a (:entry (k/write! nb {:title "45.92.49.20 is our own HAProxy" :labels {"kind" "fact"} :actor "seb"}))]
    (with-redefs [cli/writer (constantly {:actor "claude"})]
      (#'cli/run-command nb ["observe" a "missed" "--selector" "etherpad lernraum"]))
    (is (= [a] (map :entry (k/search nb "missed"))))
    (is (= ["etherpad lernraum"] (map :selector (:missed (k/entry nb a)))))
    (is (zero? (get-in (k/entry nb a) [:standing :held])))))

(deftest skill-verify-checks-the-signature-and-the-files
  (if-let [{:keys [key actors]} (tk/keypair "claude")]
    (let [_ (spit actors (str/replace (slurp actors) "namespaces=\"kajero\"" "namespaces=\"kajero,kajero-skill-manifest\""))
          catalog (skills/from-directory "skills")
          signed (skills/list-result catalog (skills/signer {:actor "claude" :key key}))
          response-file (str (temp/temp-dir) "/list.json")
          env {"KAJERO_ACTORS" actors}
          verify (fn [& args] (#'cli/skill-command env (into ["verify"] args)))]
      (spit response-file (json/generate-string {:jsonrpc "2.0" :id 1 :result signed}))
      (testing "a skills/list response verifies, and so do the repository's files"
        (let [r (verify response-file "--root" "skills")]
          (is (= [{:verified? true :actor "claude" :uri "skill://kajero/SKILL.md" :files-differ []}] r))
          (is (zero? (:kajero/exit (meta r))))))
      (testing "files that differ from the manifest fail"
        (let [root (temp/temp-dir)]
          (.mkdirs (java.io.File. ^String root "kajero"))
          (spit (str root "/kajero/SKILL.md") "changed")
          (let [r (verify response-file "--root" root)]
            (is (= [{:uri "skill://kajero/SKILL.md" :reason :mismatch}] (:files-differ (first r))))
            (is (= 1 (:kajero/exit (meta r)))))))
      (testing "a tampered manifest fails"
        (spit response-file (json/generate-string (update-in signed [:skills 0 :resources 0 :size] inc)))
        (let [r (verify response-file)]
          (is (= :bad-signature (:reason (first r))))
          (is (= 1 (:kajero/exit (meta r))))))
      (testing "an unsigned manifest fails"
        (spit response-file (json/generate-string (skills/list-result catalog (skills/signer {}))))
        (is (= :unsigned (:reason (first (verify response-file))))))
      (testing "stdin, as EDN"
        (let [r (with-in-str (pr-str (first (:skills signed))) (verify "-"))]
          (is (:verified? (first r))))))
    (println "skipped: no ssh-keygen")))

(deftest skill-manifest-prints-the-served-entries-marked-unsigned-without-a-key
  (with-redefs [skills/served (delay (skills/from-directory "skills"))]
    (let [[entry] (#'cli/skill-command {} ["manifest"])]
      (is (= "skill://kajero/SKILL.md" (:uri entry)))
      (is (false? (get-in entry [:_meta skills/meta-key :signed]))))))
