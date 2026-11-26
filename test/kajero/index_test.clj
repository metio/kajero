;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.index-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.index :as index]
            [kajero.paths :as paths]
            [kajero.print :as print]
            [kajero.record :as record]
            [kajero.store :as store]
            [kajero.test-keys :as keys]
            [kajero.test-temp :as temp]
            [kajero.verify :as verify])
  (:import (java.util.concurrent CountDownLatch)))

(def ^:private ^:dynamic *backend* :sqlite)

(use-fixtures :each temp/each (fn [run-test] (doseq [backend [:sqlite :files]]
                                               (binding [*backend* backend]
                                                 (testing (name backend) (run-test))))))

(defn- location
  "Where a notebook of the current backend lives, as notebook options."
  []
  (if (= :files *backend*)
    {:dir (str (temp/temp-dir) "/notes")}
    {:path (str (temp/temp-dir) "/notes.db")}))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- write! [nb title & [opts]]
  (k/write! nb (merge {:title title :labels {"kind" "fact"} :actor "claude"} opts)))

(defn- ids [results] (set (map :entry results)))

;; ------------------------------------------------- the store's contract

(defn- fill!
  "Writes the same records into `s`: two entries, a revision, an
   observation, a link and a page. Returns the entry ids."
  [s]
  (let [r1 {:entry "a" :seq 1 :title "A" :actor "claude" :evidence ["e1"] :at "2026-11-26T10:00:00Z"}
        r2 {:entry "a" :seq 2 :title "A2" :actor "seb" :evidence ["tik:x"] :at "2026-11-26T10:01:00Z"}
        b1 {:entry "b" :seq 1 :title "B" :actor "claude" :evidence [] :at "2026-11-26T10:02:00Z"}]
    (store/append! s :revision r1 nil)
    (store/append! s :revision r2 nil)
    (store/append! s :revision b1 nil)
    (store/append! s :observation {:entry "a" :revision (record/hash-of r2) :outcome "held" :actor "seb"
                                   :evidence [] :at "2026-11-26T10:03:00Z"} nil)
    (store/append! s :link {:source "a" :type "see-also" :target "b" :actor "claude" :at "2026-11-26T10:04:00Z"} nil)
    (store/append! s :page {:name "p" :title "P" :selector "" :actor "claude" :at "2026-11-26T10:05:00Z"} nil)
    ["a" "b"]))

(defn- answers
  "Everything the Store and Summary protocols answer about `s`."
  [s]
  {:entries (set (store/entries s))
   :revisions (into {} (for [e ["a" "b" "c"]] [e (store/revisions s e)]))
   :observations (into {} (for [e ["a" "b" "c"]] [e (set (store/observations s e))]))
   :links (into {} (for [e ["a" "b" "c"]] [e (set (store/links s e))]))
   :heads (into {} (for [e ["a" "b" "c"]] [e (store/head s e)]))
   :authorship (into {} (for [e ["a" "b"]] [e (store/authorship s e)]))
   :pages (set (store/page-versions s))})

(deftest the-index-answers-what-its-store-answers
  (let [raw (store/open (location))
        _ (fill! raw)
        idx (index/indexed raw)]
    (is (= (answers raw) (answers idx)))
    (testing "and so does an index that saw every write go through it"
      (let [fresh (index/indexed (store/open (location)))]
        (fill! fresh)
        (is (= (answers (index/inner fresh)) (answers fresh)))))
    (is (= {:actors #{"claude" "seb"} :evidence #{"e1" "tik:x"}} (store/authorship idx "a")))
    (is (= "A2" (:title (store/head idx "a"))) "the head is the revision with the highest :seq")
    (is (nil? (store/head idx "c")))))

(deftest links-are-indexed-by-source-and-by-target
  (let [nb (k/notebook (location))
        a (:entry (write! nb "A"))
        b (:entry (write! nb "B"))
        c (:entry (write! nb "C"))
        d (:entry (write! nb "D"))]
    (k/link! nb {:source a :type "see-also" :target b :actor "claude"})
    (k/link! nb {:source c :type "supersedes" :target a :actor "claude"})
    (let [s (:store nb)
          shape #(set (map (juxt :source :type :target) (store/links s %)))]
      (is (= #{[a "see-also" b] [c "supersedes" a]} (shape a)))
      (is (= #{[a "see-also" b]} (shape b)) "the target finds the link")
      (is (= #{[c "supersedes" a]} (shape c)) "the source finds the link")
      (is (= #{} (shape d)))
      (index/refresh! s)
      (is (= 1 (count (store/links s b))) "a write the feed reports again is held once")
      (is (= (shape a) (set (map (juxt :source :type :target) (store/links (index/inner s) a))))
          "the store behind it agrees"))
    (is (:superseded? (k/entry nb a)))
    (is (= #{b c d} (ids (k/search nb "not superseded"))))))

(deftest a-self-link-is-held-once
  (let [nb (k/notebook (location))
        a (:entry (write! nb "A"))]
    (k/link! nb {:source a :type "see-also" :target a :actor "claude"})
    (is (= 1 (count (store/links (:store nb) a))))))

;; ---------------------------------------------------- other writers

(deftest another-writers-entries-become-visible
  (let [where (location)
        reader (k/notebook (assoc where :refresh-ms 0))
        writer (k/notebook where)
        a (:entry (write! writer "written elsewhere"))]
    (is (= #{a} (ids (k/search reader ""))))
    (let [rev (:revision (k/entry reader a))
          {r2 :revision} (k/revise! writer a {:base rev :title "revised elsewhere" :actor "seb"})]
      (is (= r2 (:revision (k/entry reader a))) "a new revision replaces the head")
      (k/observe! writer a {:outcome "held" :actor "seb"})
      (is (= 1 (get-in (k/entry reader a) [:standing :held])))
      (let [b (:entry (write! writer "linked"))]
        (k/link! writer {:source b :type "see-also" :target a :actor "seb"})
        (is (= [{:source b :type "see-also" :target a}] (:links (k/entry reader a)))))
      (k/save-page! writer {:name "elsewhere" :selector "" :actor "seb"})
      (is (= ["elsewhere"] (map :name (k/pages reader)))))))

(deftest a-refresh-waits-for-its-interval
  (let [where (location)
        reader (k/notebook (assoc where :refresh-ms 3600000))
        writer (k/notebook where)
        a (:entry (write! writer "written elsewhere"))]
    (is (= #{} (ids (k/search reader ""))) "an index younger than its interval answers from memory")
    (index/refresh! (:store reader))
    (is (= #{a} (ids (k/search reader ""))))))

(deftest a-stale-base-is-refused-by-the-store-behind-the-index
  (let [where (location)
        slow (k/notebook (assoc where :refresh-ms 3600000))
        other (k/notebook where)
        {:keys [entry revision]} (write! slow "base")
        {r2 :revision} (k/revise! other entry {:base revision :body "landed first" :actor "seb"})]
    (is (= revision (:revision (k/entry slow entry))) "the slow index has not seen the other revision")
    (let [refused (refusal #(k/revise! slow entry {:base revision :body "lost" :actor "claude"}))]
      (is (= :kajero/stale-base (:reason refused)) "the store's atomic create refuses the taken :seq")
      (is (= r2 (:current refused)) "the refusal names the store's current revision"))
    (is (= r2 (:revision (k/entry slow entry))) "the refusal refreshed the index")
    (is (= "landed first" (:body (k/entry other entry))))
    (is (= 2 (count (store/revisions (index/inner (:store slow)) entry))) "nothing was overwritten")))

(deftest a-second-process-write-becomes-visible
  (when (= :files *backend*)
    (let [{:keys [dir]} (location)
          nb (k/notebook {:dir dir :refresh-ms 0})
          {:keys [exit out err]} (sh/sh "java" "-cp" (System/getProperty "java.class.path")
                                        "clojure.main" "-m" "kajero.cli"
                                        "write" "from the command line" "--label" "kind=fact"
                                        :env (assoc (into {} (System/getenv))
                                                    "KAJERO_DIR" dir "KAJERO_ACTOR" "seb"))]
      (is (zero? exit) (str out err))
      (is (= ["from the command line"] (map :title (k/search nb ""))))
      (is (empty? (filter #(str/ends-with? (str %) ".edn.tmp") (file-seq (io/file dir))))))))

;; ------------------------------------------------- signatures in flight

(deftest a-record-read-before-its-signature-is-read-again-with-it
  (when (= :files *backend*)
    (let [{:keys [dir]} (location)
          s (store/open {:dir dir})
          {:keys [cursor]} (store/changes s nil)
          f (io/file dir "links" "sha256-0.edn")
          rec {:source "a" :type "see-also" :target "b" :actor "claude" :at "2026-11-26T10:00:00Z"}]
      (io/make-parents f)
      (spit f (pr-str rec))
      (let [{:keys [records cursor]} (store/changes s cursor)]
        (is (= [[:link (assoc rec :sig nil)]] records) "a file without its .sig is read as it is")
        (is (empty? (:records (store/changes s cursor))) "and not again while no signature appears")
        (spit (io/file (str f ".sig")) "sig")
        (let [{:keys [records cursor]} (store/changes s cursor)
              [[kind got]] records]
          (is (= 1 (count records)))
          (is (= :link kind))
          (is (= rec (dissoc got :sig)))
          (is (= "sig" (String. ^bytes (:sig got))) "the record is read again with its signature")
          (is (empty? (:records (store/changes s cursor))) "and then never again"))))))

(deftest the-index-keeps-the-signed-copy-of-a-record
  (let [rec {:entry "a" :seq 1 :title "A" :actor "seb" :evidence [] :at "2026-11-26T10:00:00Z"}
        obs {:entry "a" :revision "r" :outcome "held" :actor "seb" :evidence [] :at "2026-11-26T10:01:00Z"}
        link {:source "a" :type "see-also" :target "b" :actor "seb" :at "2026-11-26T10:02:00Z"}
        sig (.getBytes "sig")
        state (reduce index/add-record {}
                      [[:revision (assoc rec :sig nil)] [:observation (assoc obs :sig nil)] [:link (assoc link :sig nil)]
                       [:revision (assoc rec :sig sig)] [:observation (assoc obs :sig sig)] [:link (assoc link :sig sig)]
                       [:revision (assoc rec :sig nil)] [:observation (assoc obs :sig nil)] [:link (assoc link :sig nil)]])]
    (is (identical? sig (get-in state [:entries "a" :head :sig])))
    (is (= [sig] (map :sig (get-in state [:entries "a" :observations]))))
    (is (= [sig] (map :sig (get-in state [:links "a"]))))
    (is (= [sig] (map :sig (get-in state [:links "b"]))))))

(deftest a-signature-written-after-its-record-shows-on-the-entry
  (when-let [{:keys [key actors]} (and (= :files *backend*) (keys/keypair "seb"))]
    (let [{:keys [dir]} (location)
          nb (k/notebook {:dir dir :refresh-ms 0 :actors actors})
          f (io/file dir "entries" "e" "revisions" "00000001.edn")
          rec {:entry "e" :seq 1 :base nil :title "T" :body "" :labels {"kind" "fact"}
               :evidence [] :actor "seb" :at "2026-11-26T10:00:00Z"}]
      (io/make-parents f)
      (spit f (print/edn-str rec))
      (is (= :unsigned (:signature (k/entry nb "e"))))
      (io/copy (record/sign key rec) (io/file (str f ".sig")))
      (is (= :verified (:signature (k/entry nb "e"))) "the record read again carries its signature, which verifies"))))

;; --------------------------------------------------------- concurrency

(deftest searches-run-while-entries-are-appended
  (let [nb (k/notebook (location))
        n 40
        start (CountDownLatch. 1)
        writer (future (.await start)
                       (dotimes [i n] (write! nb (str "entry " i) {:body "concurrent"})))
        readers (doall (for [_ (range 4)]
                         (future (.await start)
                                 (loop [seen [] tries 0]
                                   (let [c (count (k/search nb "concurrent"))
                                         seen (conj seen c)]
                                     (if (or (= n c) (< 2000 tries)) seen (recur seen (inc tries))))))))]
    (.countDown start)
    @writer
    (doseq [r readers]
      (let [counts (deref r 60000 ::timeout)]
        (is (not= ::timeout counts))
        (is (= counts (sort counts)) "a reader never sees an entry disappear")
        (is (every? #(<= 0 % n) counts))))
    (is (= n (count (k/search nb "concurrent"))))))

(deftest concurrent-revisions-of-one-entry-land-once-per-base
  (let [nb (k/notebook (location))
        {:keys [entry revision]} (write! nb "contested")
        start (CountDownLatch. 1)
        results (doall (for [i (range 8)]
                         (future (.await start)
                                 (try (k/revise! nb entry {:base revision :body (str "writer " i) :actor "claude"})
                                      :landed
                                      (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))]
    (.countDown start)
    (let [outcomes (frequencies (map #(deref % 30000 ::timeout) results))]
      (is (= 1 (:landed outcomes)) (pr-str outcomes))
      (is (= 7 (:kajero/stale-base outcomes)) (pr-str outcomes)))
    (is (= 2 (count (store/revisions (:store nb) entry))))))

;; ------------------------------------------------- values at write time

(deftest a-write-computes-what-searches-derive-from-it
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [nb (k/notebook (assoc (location) :actors actors))
          cited (atom 0)
          checked (atom 0)
          real-cited paths/cited
          real-check verify/check-signature]
      (with-redefs [paths/cited (fn [t] (swap! cited inc) (real-cited t))
                    verify/check-signature (fn [& args] (swap! checked inc) (apply real-check args))]
        (let [file (str "x-" (random-uuid) ".yaml")
              {:keys [entry]} (write! nb (str "Notes on templates/" file) {:key key})]
          (reset! cited 0)
          (reset! checked 0)
          (is (= [entry] (map :entry (k/search nb (str "path=" file)))))
          (is (= :verified (:signature (k/entry nb entry))))
          (is (zero? @cited) "the cited paths were computed when the revision was written")
          (is (zero? @checked) "the signature verdict was judged when the revision was written"))))))

(deftest a-failure-after-a-write-landed-does-not-fail-the-write
  (let [s (index/indexed (store/open (location)) {:on-append (fn [_ _] (throw (ex-info "boom" {})))})
        rec {:entry "a" :seq 1 :title "A" :actor "claude" :evidence [] :at "2026-11-26T10:00:00Z"}]
    (binding [*err* (java.io.StringWriter.)]
      (is (= (record/hash-of rec) (store/append! s :revision rec nil))))
    (is (= "A" (:title (store/head s "a"))))))

(deftest verdicts-are-warmed-on-a-second-thread-while-writes-land
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [nb (k/notebook (assoc (location) :actors actors))]
      (dotimes [i 10] (write! nb (str "before " i) {:key key}))
      (let [warm (future (k/verify-current! nb))]
        (dotimes [i 10] (write! nb (str "during " i) {:key key}))
        (is (<= 10 (deref warm 30000 -1) 20)))
      (is (= #{:verified} (set (map :signature (k/search nb ""))))))))
