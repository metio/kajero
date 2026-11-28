;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.tik-store-test
  "kajero.tik/store-lookup against tik stores built in the test with tik's
   own CLI, run in process: one file-backed, one SQLite-backed. No test
   here reads a store outside its temp directory or talks to a server."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.test-keys :as tk]
            [kajero.test-temp :as temp]
            [kajero.tik :as tik]
            [tik.api :as api]
            [tik.cli :as cli]))

(use-fixtures :each temp/each)

(def ^:private definition
  '{:process/id :citation
    :process/version 1
    :process/guard-vocab 1
    :process/facts {[:commit] [:string {:min 7}]
                    [:note] [:string {:min 1}]}
    :process/stages [{:stage/id :opened :guards []}
                     {:stage/id :done :after [:opened] :guards [[:fact [:commit]]]}]})

(defn- run [root & args]
  (with-redefs-fn {(requiring-resolve 'tik.cli-core/root) (constantly (str root))}
    #(cli/run-argv (mapv str args))))

(defn- tik! [root & args]
  (let [r (apply run root args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "tik " (first args) " failed: " (:err r)) r)))
    (:out r)))

(defn- signed [key] ["--actor" "seb" "--key" (str key)])

(defn- new-ticket! [root key title]
  (str/trim (first (str/split-lines (apply tik! root "new" "citation" "--title" title (signed key))))))

(defn- built-store
  "A tik store on `backend` (:file or :sqlite) with a ticket at :done, one
   at :opened, and the key that signed them; nil without ssh-keygen."
  [backend]
  (when-let [key (tk/key-file)]
    (let [root (io/file (temp/temp-dir) "tik")]
      (io/make-parents (io/file root "processes" "x"))
      (spit (io/file root "processes" "citation.edn") (pr-str definition))
      (.mkdirs (io/file root "tickets"))
      (tik! root "actor" "add" "seb" (str key ".pub"))
      (let [done (new-ticket! root key "a finished ticket")
            open (new-ticket! root key "an open ticket")]
        (apply tik! root "set" done "commit=abcdef1" (signed key))
        (when (= :sqlite backend)
          (tik! root "store" "migrate" "--to" "sqlite"))
        {:root (str root) :key key :done done :open open}))))

(defn- old-status
  "What kajero showed for a ticket before tik.api: the output `out` of
   `tik status <id> --edn`, read and mapped as the subprocess lookup did."
  [out id]
  (let [s (edn/read-string {:default (fn [_ v] v)} out)]
    {:ticket id
     :title (:title s)
     :current (mapv name (:current s))
     :process (some-> (get-in s [:rules :process]) name)}))

(defn- tik-on-path? []
  (some #(.canExecute (io/file % "tik"))
        (str/split (or (System/getenv "PATH") "") (re-pattern java.io.File/pathSeparator))))

(defn- with-store
  "Calls `f` with a store built on `backend`, or skips without ssh-keygen."
  [backend f]
  (if-let [store (built-store backend)]
    (f store)
    (println "skipped: no ssh-keygen")))

(deftest the-lookup-shows-what-tik-status-showed
  (doseq [backend [:file :sqlite]]
    (testing (name backend)
      (with-store backend (fn [{:keys [root done open]}]
                            (let [lookup (tik/store-lookup root)]
                              (doseq [id [done open]]
                                (is (= (old-status (tik! root "status" id "--edn") id) (tik/for-display lookup id)))
                                (is (= {:ticket id} (tik/for-write lookup id))))
                              (is (= ["done"] (:current (tik/for-display lookup done))))
                              (is (= ["opened"] (:current (tik/for-display lookup open))))
                              (when (tik-on-path?)
                                (testing "and what the tik subprocess on PATH shows"
                                  (doseq [id [done open]]
                                    (let [{:keys [exit out]} (sh/sh "tik" "status" id "--edn"
                                                                    :env (assoc (into {} (System/getenv)) "TIK_ROOT" root))]
                                      (is (zero? exit))
                                      (is (= (old-status out id) (tik/for-display lookup id)))))))))))))

(deftest a-lookup-starts-no-process
  (with-store :file (fn [{:keys [root done]}]
                      (with-redefs [sh/sh (fn [& _] (throw (ex-info "a process was started" {})))]
                        (let [lookup (tik/store-lookup root)]
                          (is (= ["done"] (:current (tik/for-display lookup done))))
                          (is (some? (tik/for-write lookup done))))))))

(deftest an-unknown-ticket-is-nil
  (with-store :file (fn [{:keys [root done]}]
                      (let [lookup (tik/store-lookup root)
                            unknown "00000000-0000-0000-0000-000000000000"]
                        (is (nil? (tik/for-display lookup unknown)))
                        (is (nil? (tik/for-write lookup unknown)))
                        (testing "a prefix is never resolved, though tik.api would resolve it"
                          (is (some? (api/resolve-id (api/open-store root) (subs done 0 8))) "positive control")
                          (is (nil? (tik/for-display lookup (subs done 0 8))))
                          (is (nil? (tik/for-write lookup (subs done 0 8))))
                          (is (= [] (tik/ticket-ids [(str "tik:" (subs done 0 8))]))))))))

(deftest a-definition-mismatch-shows-no-stage
  (with-store :file (fn [{:keys [root done]}]
                      (let [lookup (tik/store-lookup root)]
                        (is (= ["done"] (:current (tik/for-display lookup done))) "negative control: the pinned definition")
      ;; the archived definition is gone and processes/citation.edn changed, so
      ;; the definition found no longer hashes to the pin
                        (doseq [^java.io.File f (.listFiles (io/file root "processes" "by-hash"))] (.delete f))
                        (spit (io/file root "processes" "citation.edn")
                              (pr-str (assoc definition :process/stages [{:stage/id :opened :guards []}
                                                                         {:stage/id :done :after [:opened] :guards []}
                                                                         {:stage/id :shipped :after [:done] :guards []}])))
                        (is (= :tik.api/definition-mismatch
                               (try (api/derive (api/open-store root) done) nil
                                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
                            "tik.api refuses it by default")
                        (let [t (tik/for-display (tik/store-lookup root) done)]
                          (is (= "a finished ticket" (:title t)))
                          (is (some #{"definition-mismatch"} (:problems t)))
                          (is (not (contains? t :current)) "the stage the changed definition would derive is not shown"))
                        (is (= {:ticket done} (tik/for-write (tik/store-lookup root) done)) "the ticket still exists for a citation")))))

(deftest an-unsigned-event-shows-no-stage
  (with-store :file (fn [{:keys [root done]}]
                      (tik! root "set" done "note=unsigned" "--actor" "eve")
                      (is (:ok? (api/verify-ticket (api/open-store root) done))
                          "tik counts an unsigned event as a note, not a failure")
                      (let [t (tik/for-display (tik/store-lookup root) done)]
                        (is (= ["unverified"] (:problems t)))
                        (is (not (contains? t :current)))))))

(deftest a-key-the-store-no-longer-grants-shows-no-stage
  (with-store :file (fn [{:keys [root done open]}]
                      (let [lookup (tik/store-lookup root)]
                        (is (= ["done"] (:current (tik/for-display lookup done))) "negative control")
                        (let [other (tk/key-file)]
                          (spit (io/file root "actors") (str "seb " (str/trim (slurp (str other ".pub")))))
                          (testing "the changed actors file is seen at once, without waiting for the verdict to expire"
                            (let [t (tik/for-display lookup done)]
                              (is (= ["unverified"] (:problems t)))
                              (is (not (contains? t :current)))))
                          (is (= ["unverified"] (:problems (tik/for-display lookup open)))))))))

(deftest a-tampered-event-shows-no-stage
  (with-store :file (fn [{:keys [root done]}]
                      (let [events (io/file root "tickets" done "events")
                            ^java.io.File fact (first (filter #(str/includes? (slurp %) "abcdef1")
                                                              (filter #(str/ends-with? (.getName ^java.io.File %) ".edn") (.listFiles events))))]
                        (spit fact (str/replace (slurp fact) "abcdef1" "abcdef2"))
                        (is (not (:current (tik/for-display (tik/store-lookup root) done))))))))

(deftest verdicts-are-reused-only-for-the-same-events
  (with-store :file (fn [{:keys [root key done]}]
                      (let [calls (atom 0)
                            now (atom 0)
                            verify api/verify-ticket]
      ;; the original is called with its options, so its two-argument arity
      ;; does not come back through the redefined var
                        (with-redefs [api/verify-ticket (fn [store ref & [opts]] (swap! calls inc) (verify store ref (or opts {})))]
                          (let [lookup (tik/store-lookup root {:clock #(deref now) :verify-ttl 1000})]
                            (tik/for-display lookup done)
                            (tik/for-display lookup done)
                            (is (= 1 @calls) "the same events, the same trust files: verified once")
                            (apply tik! root "set" done "note=more" (signed key))
                            (is (= ["done"] (:current (tik/for-display lookup done))))
                            (is (= 2 @calls) "a new event is verified again")
                            (reset! now 1000)
                            (tik/for-display lookup done)
                            (is (= 3 @calls) "the verdict expired")))))))

(deftest a-root-that-is-no-store-is-unavailable-until-it-is-one
  (with-store :file (fn [{:keys [root done]}]
                      (let [missing (str root "-later")
                            lookup (tik/store-lookup missing)]
                        (is (= {:ticket done :problems [tik/unavailable]} (tik/for-display lookup done)))
                        (is (= :kajero/tik-unavailable
                               (try (tik/for-write lookup done) nil
                                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))
                        (.renameTo (io/file root) (io/file missing))
                        (is (= ["done"] (:current (tik/for-display lookup done))) "the store is opened once it exists")))))

(deftest a-store-unavailable-is-never-taken-from-memory-for-a-write
  (with-store :file (fn [{:keys [root done]}]
                      (let [missing (str root "-later")
                            lookup (tik/cached (tik/store-lookup missing) {:clock (constantly 0)})]
                        (is (= [tik/unavailable] (:problems (tik/for-display lookup done))))
                        (is (= :kajero/tik-unavailable
                               (try (tik/for-write lookup done) nil
                                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))))

(deftest a-notebook-bound-to-a-store-checks-and-shows-citations
  (doseq [backend [:file :sqlite]]
    (testing (name backend)
      (with-store backend (fn [{:keys [root done]}]
                            (let [nb (k/notebook {:dir (str (temp/temp-dir) "/notes") :tik-root root})
                                  entry {:title "a fact" :labels {"kind" "fact"} :actor "claude"}]
                              (is (= :kajero/no-ticket
                                     (try (k/write! nb (assoc entry :evidence ["tik:00000000-0000-0000-0000-000000000000"])) nil
                                          (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))
                              (let [{e :entry} (k/write! nb (assoc entry :evidence [(str "tik:" done)]))]
                                (is (= [{:ticket done :title "a finished ticket" :current ["done"] :process "citation"}]
                                       (:tickets (k/entry nb e)))))))))))

(deftest an-exception-from-tik-shows-as-a-problem-on-display
  (with-store :file (fn [{:keys [root done]}]
                      (with-redefs [api/derive (fn [& _] (throw (IllegalStateException. "a bug")))]
                        (is (= {:ticket done :problems ["tik-error"]}
                               (tik/for-display (tik/store-lookup root) done)))))))
