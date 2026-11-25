;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.usage-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.cli :as cli]
            [kajero.core :as k]
            [kajero.mcp :as mcp]
            [kajero.test-temp :as temp]
            [kajero.usage :as usage]
            [kajero.web :as web])
  (:import (java.time Duration Instant)
           (java.util Random)))

(use-fixtures :each temp/each)

(def ^:private ^Instant t0 (Instant/parse "2026-11-25T10:00:00Z"))

(defn- sqlite [] (k/notebook {:path (str (temp/temp-dir) "/notes.db")}))
(defn- files [] (k/notebook {:dir (str (temp/temp-dir) "/notes")}))

(def ^:private backends {"sqlite" sqlite "files" files})

(defmacro ^{:private true :clj-kondo/lint-as 'clojure.core/fn} on-both
  "Runs body once per store, with `nb` bound to a fresh notebook of it."
  [[nb] & body]
  `(doseq [[name# make#] backends]
     (testing name#
       (let [~nb (make#)] ~@body))))

(defn- write! [nb title & [labels]]
  (:entry (k/write! nb {:title title :labels (merge {"kind" "fact"} labels) :actor "claude"})))

(defn- ids [results] (set (map :entry results)))

(defn- day-counts [nb entry]
  (get-in (usage/counts (:usage nb)) [:days entry (usage/day t0)]))

(deftest a-search-counts-each-entry-it-returned
  (on-both [nb]
           (let [a (write! nb "CoreDNS caches answers" {"system" "dns"})
                 b (write! nb "Calico serves v3 by CRDs" {"system" "calico"})]
             (binding [k/*now* t0]
               (k/search! nb nil "label:system=dns")
               (k/search! nb nil "label:kind=fact"))
             (is (= {:returned 2 :opened 0 :opened-after-return 0} (day-counts nb a)))
             (is (= {:returned 1 :opened 0 :opened-after-return 0} (day-counts nb b)))
             (is (= (usage/day t0) (:since (usage/counts (:usage nb)))) "the day counting began"))))

(deftest a-plain-search-and-a-plain-read-count-nothing
  (on-both [nb]
           (let [a (write! nb "CoreDNS caches answers")]
             (k/search nb "label:kind=fact")
             (k/entry nb a)
             (is (= {} (:days (usage/counts (:usage nb))))))))

(deftest a-search-without-a-following-read-records-no-open
  (on-both [nb]
           (let [a (write! nb "CoreDNS caches answers")
                 session (binding [k/*now* t0] (k/start-session! nb))]
             (binding [k/*now* t0] (k/search! nb session "coredns"))
             (is (= {:returned 1 :opened 0 :opened-after-return 0} (day-counts nb a)))
             (is (= #{a} (ids (k/search nb "returned-unopened=1")))))))

(deftest a-read-after-a-search-of-the-same-session-counts-as-opened-after-return
  (on-both [nb]
           (let [a (write! nb "CoreDNS caches answers")]
             (binding [k/*now* t0]
               (let [session (k/start-session! nb)]
                 (k/search! nb session "coredns")
                 (is (= a (:entry (k/open! nb session a))))))
             (is (= {:returned 1 :opened 1 :opened-after-return 1} (day-counts nb a)))
             (is (empty? (k/search nb "returned-unopened=1")) "an opened entry is not returned-unopened"))))

(deftest a-read-counts-as-after-return-only-in-the-session-that-searched
  (on-both [nb]
           (let [a (write! nb "CoreDNS caches answers")]
             (binding [k/*now* t0]
               (let [searcher (k/start-session! nb)
                     other (k/start-session! nb)]
                 (k/search! nb searcher "coredns")
                 (k/open! nb other a)
                 (k/open! nb nil a)))
             (is (= {:returned 1 :opened 2 :opened-after-return 0} (day-counts nb a))))))

(deftest a-read-before-any-search-is-an-open-and-nothing-more
  (on-both [nb]
           (let [a (write! nb "CoreDNS caches answers")]
             (binding [k/*now* t0]
               (let [s (k/start-session! nb)]
                 (k/open! nb s a)
                 (k/search! nb s "coredns")))
             (is (= {:returned 1 :opened 1 :opened-after-return 0} (day-counts nb a))))))

(deftest a-session-ends-thirty-minutes-after-its-last-search
  (let [nb (sqlite)
        a (write! nb "CoreDNS caches answers")
        later (.plus t0 (.plusSeconds usage/session-ttl 1))
        s (binding [k/*now* t0] (k/start-session! nb))]
    (binding [k/*now* t0] (k/search! nb s "coredns"))
    (binding [k/*now* (.plus t0 (Duration/ofMinutes 29))]
      (is (= s (k/live-session nb s))))
    (binding [k/*now* later]
      (is (nil? (k/live-session nb s)))
      (k/open! nb s a))
    (is (zero? (usage/total (usage/of-entry (usage/counts (:usage nb)) a) :opened-after-return)))))

(deftest reading-a-missing-entry-counts-nothing
  (on-both [nb]
           (is (nil? (binding [k/*now* t0] (k/open! nb nil "no-such-entry"))))
           (is (= {} (:days (usage/counts (:usage nb)))))))

(deftest an-empty-selector-and-a-usage-report-count-nothing
  (on-both [nb]
           (write! nb "CoreDNS caches answers")
           (binding [k/*now* t0]
             (k/search! nb nil "")
             (k/search! nb nil "   ")
             (k/search! nb nil "never-returned")
             (k/search! nb nil "not returned-unopened")
             (k/search! nb nil "coredns|never-returned=2026-01-01"))
           (is (= {} (:days (usage/counts (:usage nb)))))))

(deftest never-returned-lists-the-entries-no-search-returned
  (on-both [nb]
           (let [found (write! nb "CoreDNS caches answers")
                 lost (write! nb "Velero schedules run at two")]
             (is (= #{found lost} (ids (k/search nb "never-returned"))) "before any counting, every entry")
             (binding [k/*now* t0] (k/search! nb nil "coredns"))
             (is (= #{lost} (ids (k/search nb "never-returned"))))
             (is (= #{found} (ids (k/search nb "not never-returned"))))
             (is (= #{found lost} (ids (k/search nb "never-returned=2026-11-26"))) "nothing was returned since then")
             (is (= #{lost} (ids (k/search nb "never-returned=2026-11-25"))))
             (is (= #{lost} (ids (k/search nb "never-returned label:kind=fact"))) "it combines with other terms"))))

(deftest returned-unopened-takes-its-threshold
  (on-both [nb]
           (let [a (write! nb "CoreDNS caches answers")]
             (binding [k/*now* t0]
               (dotimes [_ 2] (k/search! nb nil "coredns")))
             (is (empty? (k/search nb "returned-unopened")) "twice is below the default of three")
             (is (= #{a} (ids (k/search nb "returned-unopened=2"))))
             (binding [k/*now* t0] (k/search! nb nil "coredns"))
             (is (= #{a} (ids (k/search nb "returned-unopened"))))
             (binding [k/*now* t0] (k/open! nb nil a))
             (is (= #{a} (ids (k/search nb "returned-unopened"))) "a read outside a session is not an open after a return"))))

(deftest a-usage-term-with-a-bad-argument-is-refused
  (let [nb (sqlite)]
    (doseq [sel ["never-returned=yesterday" "never-returned=2026-13-01" "returned-unopened=0"
                 "returned-unopened=-1" "returned-unopened=x" "returned-unopened=1234567890"]]
      (is (= :select/bad-term (:reason (ex-data (try (k/search nb sel) nil (catch clojure.lang.ExceptionInfo e e)))))
          sel))))

(deftest a-usage-term-is-no-text-term
  (let [nb (sqlite)
        a (write! nb "CoreDNS caches answers")]
    (is (= [a] (mapv :entry (k/search nb "never-returned"))))
    (is (nil? (:score (first (k/search nb "never-returned")))) "it carries no text score")))

(defn- random-run!
  "Searches and reads in a random order, with the seed's choices."
  [nb ^Random rnd entries selectors]
  (let [sessions [nil (k/start-session! nb) (k/start-session! nb)]]
    (dotimes [_ 40]
      (let [session (nth sessions (.nextInt rnd (count sessions)))]
        (if (.nextBoolean rnd)
          (k/search! nb session (nth selectors (.nextInt rnd (count selectors))))
          (k/open! nb session (nth entries (.nextInt rnd (count entries)))))))))

(deftest counting-never-changes-search-results-or-entries
  (doseq [seed (range 8)]
    (on-both [nb]
             (binding [k/*now* t0]
               (let [entries [(write! nb "CoreDNS caches answers" {"system" "dns"})
                              (write! nb "Calico serves v3 by CRDs" {"system" "calico"})
                              (write! nb "Velero runs at two" {"system" "backup"})
                              (write! nb "coredns and calico together" {"system" "dns"})]
                     selectors ["" "coredns" "calico|velero" "label:system=dns" "not label:system=dns"
                                "label:kind=fact ~two" "never-returned" "returned-unopened=1"]
                     plain (fn [] [(mapv #(k/search nb %) (remove #{"never-returned" "returned-unopened=1"} selectors))
                                   (mapv #(k/entry nb %) entries)])
                     before (plain)]
                 (random-run! nb (Random. seed) entries selectors)
                 (is (= before (plain)) (str "seed " seed))
                 (testing "search! answers what search answers"
                   (doseq [sel selectors]
                     (is (= (k/search nb sel) (k/search! nb nil sel)) sel)))
                 (testing "open! answers what entry answers"
                   (doseq [e entries]
                     (is (= (k/entry nb e) (k/open! nb nil e))))))))))

(defrecord BrokenLog []
  usage/Log
  (add! [_ _ _] (throw (java.io.IOException. "disk full")))
  (fold! [_ _] (throw (java.io.IOException. "disk full")))
  (snapshot [_] (throw (java.io.IOException. "disk full"))))

(deftest a-count-that-can-not-be-written-is-logged-and-the-search-still-answers
  (let [nb (sqlite)
        a (write! nb "CoreDNS caches answers")
        broken (assoc nb :usage (usage/recorder (->BrokenLog)))
        lines (atom [])]
    (with-redefs [usage/log! (fn [& parts] (swap! lines conj (str/join " " parts)))]
      (is (= (k/search nb "coredns") (k/search! broken nil "coredns")))
      (is (= (k/entry nb a) (k/open! broken nil a))))
    (is (= 2 (count @lines)) "each lost count is logged")
    (is (every? #(str/includes? % "disk full") @lines))
    (is (str/includes? (first @lines) "usage not recorded for a search"))
    (is (str/includes? (second @lines) "usage not recorded for a read"))
    (is (not-any? #(str/includes? % "coredns") @lines) "the log keeps the query text out")
    (testing "a report refuses instead of reading unreadable counts as none"
      (is (= :kajero/usage-unreadable
             (:reason (ex-data (try (k/search broken "never-returned") nil
                                    (catch clojure.lang.ExceptionInfo e e)))))))))

(deftest a-failed-count-on-stderr-reaches-the-log
  (let [nb (assoc (sqlite) :usage (usage/recorder (->BrokenLog)))
        _ (write! nb "CoreDNS caches answers")
        err (java.io.StringWriter.)]
    (binding [*err* err]
      (is (= 1 (count (k/search! nb nil "coredns")))))
    (is (str/includes? (str err) "kajero: usage not recorded for a search - disk full"))))

(deftest an-unreadable-usage-file-is-never-overwritten
  (let [dir (str (temp/temp-dir) "/notes")
        nb (k/notebook {:dir dir})
        _ (write! nb "CoreDNS caches answers")
        f (usage/beside dir)]
    (spit f "{:not counts")
    (with-redefs [usage/log! (fn [& _])]
      (is (= 1 (count (k/search! nb nil "coredns")))))
    (is (= "{:not counts" (slurp f)) "the measurement is kept for a human to repair")
    (spit f "[1 2 3]")
    (with-redefs [usage/log! (fn [& _])]
      (is (= 1 (count (k/search! nb nil "coredns")))))
    (is (= "[1 2 3]" (slurp f)))))

(deftest the-counts-of-a-flat-file-notebook-live-beside-it
  (let [root (temp/temp-dir)
        nb (k/notebook {:dir (str root "/notes")})
        a (write! nb "CoreDNS caches answers")]
    (binding [k/*now* t0] (k/search! nb nil "coredns"))
    (is (.isFile (io/file root "notes.usage.edn")))
    (is (not (str/includes? (str/join "\n" (map str (file-seq (io/file root "notes")))) "usage"))
        "nothing inside the record directory")
    (is (= [a] (keys (:days (usage/counts (:usage (k/notebook {:dir (str root "/notes")})))))) "a reopened notebook reads them")))

(deftest the-counts-keep-no-query-text
  (on-both [nb]
           (let [a (write! nb "zebra crossing" {"system" "zoo"})]
             (binding [k/*now* t0]
               (let [s (k/start-session! nb)]
                 (k/search! nb s "zebra label:system=zoo")
                 (k/open! nb s a)))
             (let [dump (pr-str (usage/counts (:usage nb)))]
               (is (not (str/includes? dump "zebra")))
               (is (not (str/includes? dump "zoo")))
               (is (not (str/includes? dump "claude")) "no actor")))))

(deftest old-days-fold-into-one-total-per-entry
  (on-both [nb]
           (let [a (write! nb "CoreDNS caches answers")
                 old (Instant/parse "2026-01-01T10:00:00Z")
                 now (.plus old (Duration/ofDays (inc usage/retention-days)))]
             (binding [k/*now* old] (k/search! nb nil "coredns") (k/search! nb nil "coredns"))
             (binding [k/*now* now] (k/search! nb nil "coredns"))
             (let [c (usage/counts (:usage nb))]
               (is (= [(usage/day now)] (keys (get-in c [:days a]))) "only the recent day stays a row")
               (is (= 2 (get-in c [:folded a :returned])))
               (is (= "2026-01-01" (:since c)) "the day counting began survives the fold")
               (is (= 3 (usage/total (usage/of-entry c a) :returned))))
             (is (empty? (k/search nb "never-returned")))
             (is (= #{a} (ids (k/search nb (str "never-returned=" (usage/day (.plus now (Duration/ofDays 1))))))))
             (let [u (usage/of-entry (usage/counts (:usage nb)) a)]
               (is (= "2026-01-02" (:folded-through u)))
               (is (= 3 (usage/returned-since u "2026-01-01"))
                   "folded counts may lie on or after a day before the fold boundary, so they count")
               (is (= 1 (usage/returned-since u "2026-01-02")) "a day from the boundary on leaves them out")))))

(deftest sessions-and-their-memory-stay-bounded
  (let [nb (sqlite)
        a (write! nb "CoreDNS caches answers")]
    (with-redefs [usage/max-sessions 3 usage/max-returned 0]
      (binding [k/*now* t0]
        (let [first-session (k/start-session! nb)]
          (dotimes [i 5]
            (binding [k/*now* (.plusSeconds t0 (inc i))] (k/start-session! nb)))
          (is (<= (count @(get-in nb [:usage :sessions])) 3))
          (is (nil? (k/live-session nb first-session)) "the least recently used session made room"))
        (let [s (k/start-session! nb)]
          (k/search! nb s "coredns")
          (is (empty? (get-in @(get-in nb [:usage :sessions]) [s :returned])) "no more than max-returned remembered")
          (k/open! nb s a))))
    (is (zero? (usage/total (usage/of-entry (usage/counts (:usage nb)) a) :opened-after-return)))))

(deftest a-session-id-the-server-did-not-mint-is-no-session
  (let [nb (sqlite)]
    (binding [k/*now* t0]
      (is (nil? (k/live-session nb "0123456789abcdef0123456789abcdef")))
      (is (nil? (k/live-session nb nil)))
      (let [s (k/start-session! nb)]
        (is (re-matches #"[0-9a-f]{32}" s))
        (is (= s (k/live-session nb s)))))))

;; ------------------------------------------------------------- surfaces

(defn- mcp-call [nb session tool args]
  (-> (mcp/handle nb {:actor "claude"} session
                  (json/generate-string {:jsonrpc "2.0" :id 1 :method "tools/call"
                                         :params {:name tool :arguments args}}))
      (get-in [:result :content 0 :text])
      json/parse-string))

(deftest mcp-search-and-get-count-for-their-session
  (let [nb (sqlite)
        a (write! nb "CoreDNS caches answers")
        session (binding [k/*now* t0] (k/start-session! nb))]
    (binding [k/*now* t0]
      (is (= [a] (map #(get % "entry") (mcp-call nb session "kajero_search" {:selector "coredns"}))))
      (is (= a (get (mcp-call nb session "kajero_get" {:entry (subs a 0 8)}) "entry"))))
    (is (= {:returned 1 :opened 1 :opened-after-return 1} (day-counts nb a)))
    (is (= [a] (map #(get % "entry") (mcp-call nb session "kajero_search" {:selector "never-returned=2026-11-26"})))
        "the selector terms reach MCP")))

(def ^:private mcp-config {:token "s3cret" :writer {:actor "claude"} :port 7270})

(defn- http-call [nb request]
  (web/mcp-endpoint nb mcp-config (merge {:method "POST" :authorization "Bearer s3cret"} request)))

(defn- tool-body [tool args]
  (json/generate-string {:jsonrpc "2.0" :id 2 :method "tools/call" :params {:name tool :arguments args}}))

;; ------------------------------------------------- citations and missed

(deftest a-search-ranked-by-citations-counts-every-entry-it-returned
  (on-both [nb]
           (let [plain (write! nb "CoreDNS caches answers")
                 cited (write! nb "CoreDNS forwards upstream")
                 citer (write! nb "Resolver notes")]
             (k/revise! nb citer {:base (:revision (k/entry nb citer)) :body (str "builds on [[" cited "]]")
                                  :actor "claude"})
             (binding [k/*now* t0]
               (let [results (k/search! nb nil "coredns")]
                 (is (= [cited plain] (mapv :entry results)) "the cited entry ranks above the equal match")
                 (is (= [1 0] (mapv :citations results)))))
             (is (= {:returned 1 :opened 0 :opened-after-return 0} (day-counts nb cited)))
             (is (= {:returned 1 :opened 0 :opened-after-return 0} (day-counts nb plain)))
             (is (nil? (day-counts nb citer)) "the citing entry was not returned, so it is not counted"))))

(deftest counts-never-move-an-entry-in-a-ranking-by-citations
  (on-both [nb]
           (let [plain (write! nb "CoreDNS caches answers")
                 cited (write! nb "CoreDNS forwards upstream")
                 citer (write! nb "Resolver notes")
                 session (binding [k/*now* t0] (k/start-session! nb))]
             (k/link! nb {:source citer :type "see-also" :target cited :actor "claude"})
             (let [before (k/search nb "coredns")]
               (binding [k/*now* t0]
                 (dotimes [_ 5]
                   (k/search! nb session "coredns")
                   (k/open! nb session plain)))
               (is (= {:returned 5 :opened 5 :opened-after-return 5} (day-counts nb plain)))
               (is (= {:returned 5 :opened 0 :opened-after-return 0} (day-counts nb cited)))
               (is (= before (k/search nb "coredns") (k/search! nb nil "coredns"))
                   "the often opened entry stays below the cited one, with the same score, citations and rank")
               (is (= [cited plain] (mapv :entry before)))))))

(deftest recording-a-missed-observation-counts-nothing
  (on-both [nb]
           (let [a (write! nb "Etherpad runs in the lernraum namespace")
                 session (binding [k/*now* t0] (k/start-session! nb))]
             (binding [k/*now* t0]
               (k/observe! nb a {:outcome "missed" :selector "etherpad|pad" :actor "claude"}))
             (is (= {} (:days (usage/counts (:usage nb)))) "the observation runs no search")
             (testing "the entry still reads as never returned, because no search returned it"
               (is (= [a] (mapv :entry (k/search nb "never-returned")))))
             (testing "a read after the observation follows no search of the session"
               (binding [k/*now* t0] (k/open! nb session a))
               (is (= {:returned 0 :opened 1 :opened-after-return 0} (day-counts nb a)))))))

(deftest a-search-for-missed-entries-counts-what-it-returned
  (on-both [nb]
           (let [a (write! nb "Etherpad runs in the lernraum namespace")
                 b (write! nb "Velero runs at two")]
             (k/observe! nb a {:outcome "missed" :selector "etherpad|pad" :actor "claude"})
             (binding [k/*now* t0]
               (is (= [a] (mapv :entry (k/search! nb nil "missed"))))
               (is (= [b] (mapv :entry (k/search! nb nil "not missed")))))
             (is (= {:returned 1 :opened 0 :opened-after-return 0} (day-counts nb a)))
             (is (= {:returned 1 :opened 0 :opened-after-return 0} (day-counts nb b))))))

(deftest mcp-observe-missed-counts-nothing-for-its-session
  (let [nb (sqlite)
        a (write! nb "Etherpad runs in the lernraum namespace")
        session (binding [k/*now* t0] (k/start-session! nb))]
    (binding [k/*now* t0]
      (is (empty? (mcp-call nb session "kajero_search" {:selector "wiki"})))
      (is (contains? (mcp-call nb session "kajero_observe" {:entry a :outcome "missed" :selector "wiki"
                                                            :evidence ["found by label"]})
                     "observation"))
      (mcp-call nb session "kajero_get" {:entry a}))
    (is (= {:returned 0 :opened 1 :opened-after-return 0} (day-counts nb a))
        "neither the empty search nor the observation returned the entry, so the read follows no return")))

(deftest mcp-over-http-mints-the-session-it-counts-for
  (let [nb (sqlite)
        a (write! nb "CoreDNS caches answers")]
    (binding [k/*now* t0]
      (let [init (http-call nb {:body "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"})
            session (get-in init [:headers "Mcp-Session-Id"])]
        (is (re-matches #"[0-9a-f]{32}" session))
        (is (nil? (get-in (http-call nb {:body "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}" :session session})
                          [:headers "Mcp-Session-Id"]))
            "only an initialize mints one")
        (http-call nb {:body (tool-body "kajero_search" {:selector "coredns"}) :session session})
        (http-call nb {:body (tool-body "kajero_get" {:entry a}) :session session})
        (is (= {:returned 1 :opened 1 :opened-after-return 1} (day-counts nb a)))
        (testing "a forged or missing session id counts as none"
          (http-call nb {:body (tool-body "kajero_search" {:selector "coredns"}) :session "forged"})
          (http-call nb {:body (tool-body "kajero_get" {:entry a}) :session "forged"})
          (http-call nb {:body (tool-body "kajero_get" {:entry a})})
          (is (= {:returned 2 :opened 3 :opened-after-return 1} (day-counts nb a))))))))

(deftest the-pages-count-a-typed-search-and-an-entry-read
  (let [nb (sqlite)
        a (write! nb "CoreDNS caches answers")
        page #(web/respond nb {:actor "seb"} "t0ken" {:method "GET" :path %1 :query %2})]
    (binding [k/*now* t0]
      (page "/view" "selector=coredns")
      (page (str "/e/" a) nil)
      (page "/" nil)
      (page "/view.edn" "selector=coredns")
      (page (str "/e/" a ".edn") nil))
    (is (= {:returned 1 :opened 1 :opened-after-return 0} (day-counts nb a))
        "the front page and the EDN reads for tools count nothing")))

(deftest the-cli-counts-and-reports
  (let [nb (sqlite)
        a (write! nb "CoreDNS caches answers")
        b (write! nb "Velero runs at two")]
    (binding [k/*now* t0]
      (dotimes [_ 3] (#'cli/run-command nb ["search" "coredns"]))
      (#'cli/run-command nb ["get" b]))
    (let [report (#'cli/run-command nb ["usage"])]
      (is (= (usage/day t0) (:since report)))
      (is (= [b] (mapv :entry (:never-returned report))))
      (is (= [a] (mapv :entry (:returned-unopened report))))
      (is (= #{:entry :title :labels} (set (keys (first (:returned-unopened report)))))))
    (is (= [] (:returned-unopened (#'cli/run-command nb ["usage" "--min" "4"]))))
    (is (= #{a b} (set (map :entry (:never-returned (#'cli/run-command nb ["usage" "--since" "2026-11-26"]))))))
    (is (= {:returned 3 :opened 0 :opened-after-return 0} (day-counts nb a)) "the report counted nothing")))
