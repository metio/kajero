;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.compute-once-test
  "Reads compute what they return and nothing else: the home page judges
   only the signatures it shows, pages never ask tik, an entry is read from
   the index, and kept citations follow every change."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.index :as index]
            [kajero.store :as store]
            [kajero.test-keys :as keys]
            [kajero.test-temp :as temp]
            [kajero.verify :as verify]
            [kajero.web :as web]))

(use-fixtures :each temp/each)

(defn- get-page [nb path & [query]]
  (web/respond nb {:actor "seb"} "t0ken" {:method "GET" :path path :query query}))

(deftest the-home-page-judges-only-the-signatures-it-shows
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [where {:dir (str (temp/temp-dir) "/notes") :actors actors}
          writer (k/notebook where)]
      (dotimes [i 30]
        (k/write! writer {:title (str "entry " i) :labels {"kind" "fact"} :actor "claude" :key key}))
      (let [calls (atom 0)
            nb (update (k/notebook where) :verifier
                       assoc :check (fn [& args] (swap! calls inc) (apply verify/check-signature args)))]
        (is (= 200 (:status (get-page nb "/"))))
        (is (<= 1 @calls 12) (str @calls " signatures judged"))
        (testing "a search without terms judges every entry it returns"
          (reset! calls 0)
          (is (= 30 (count (k/search nb ""))))
          (is (= 18 @calls) "the 12 of the home page are remembered"))))))

(deftest a-signature-term-judges-every-candidate
  (when-let [{:keys [key actors]} (keys/keypair "claude")]
    (let [where {:dir (str (temp/temp-dir) "/notes") :actors actors}
          writer (k/notebook where)]
      (dotimes [i 5]
        (k/write! writer {:title (str "entry " i) :labels {"kind" (if (even? i) "fact" "gotcha")}
                          :actor "claude" :key key}))
      (let [calls (atom 0)
            nb (update (k/notebook where) :verifier
                       assoc :check (fn [& args] (swap! calls inc) (apply verify/check-signature args)))]
        (is (= 3 (count (k/search nb "label:kind=fact not unverified"))))
        (is (= 3 @calls) "only the entries the label term let through")
        (spit actors "")
        (is (= 3 (count (k/search nb "label:kind=fact bad-signature")))
            "an emptied actors file is read by the next search")))))

(deftest a-new-citation-changes-the-next-search
  (let [where {:dir (str (temp/temp-dir) "/notes")}
        nb (k/notebook where)
        write! #(:entry (k/write! nb {:title %1 :body (or %2 "") :labels {"kind" "fact"} :actor "claude"}))
        cited #(:citations (first (filter (fn [s] (= %2 (:entry s))) (k/search nb %1))))
        target (write! "calico policy order" nil)]
    (is (zero? (cited "~calico" target)))
    (let [citer (write! "notes" (str "see [[" (subs target 0 8) "]]"))]
      (is (= 1 (cited "~calico" target)) "a body reference counts at once")
      (let [linker (write! "more notes" nil)]
        (k/link! nb {:source linker :type "see-also" :target target :actor "claude"})
        (is (= 2 (cited "~calico" target)) "a see-also link counts at once"))
      (k/revise! nb citer {:base (:revision (k/entry nb citer)) :body "no reference" :actor "claude"})
      (is (= 1 (cited "~calico" target)) "a removed reference stops counting"))
    (testing "a citation another process writes counts once the index has read it"
      (let [other (k/notebook where)]
        (k/write! other {:title "elsewhere" :body (str "[[" target "]]") :labels {"kind" "fact"} :actor "seb"})
        (index/refresh! (:store nb))
        (is (= 2 (cited "~calico" target)))))))

(deftest unicode-case-folding-matches-and-cuts-snippets-right
  (let [nb (k/notebook {:dir (str (temp/temp-dir) "/notes")})
        {:keys [entry]} (k/write! nb {:title "Die Straße in İstanbul" :body "Erste Zeile ß\r\nzweite İ Zeile"
                                      :labels {"kind" "fact"} :actor "claude"})]
    (doseq [term ["~ß" "~straße" "~İstanbul" "~STRASSE|~straße"]]
      (let [[hit] (k/search nb term)]
        (is (= entry (:entry hit)) term)
        (is (= "Erste Zeile ß" (:summary hit)) term)
        (is (every? #(str/includes? (str/lower-case (:snippet %)) "stra") (filter #(= "title" (:field %)) (:matches hit)))
            term)))))

(deftest pages-never-ask-tik
  (let [calls (atom 0)
        nb (k/notebook {:dir (str (temp/temp-dir) "/notes")
                        :tik-status (fn [id] (swap! calls inc) {:ticket id :title "a ticket" :current ["proven"]})})
        {:keys [entry]} (k/write! nb {:title "cited" :labels {"kind" "fact"} :actor "claude"
                                      :evidence ["tik:6afff677-67e5-45a0-aadb-551ef2348cdd"]})]
    (k/save-page! nb {:name "facts" :selector "label:kind=fact" :actor "seb"})
    (reset! calls 0)
    (doseq [[path query] [["/"] ["/view" "selector=label%3Akind%3Dfact"] ["/view"] ["/p/facts"]]]
      (is (= 200 (:status (get-page nb path query))) path))
    (is (zero? @calls) "no page of several entries asks tik")
    (testing "the entry's own page shows its tickets"
      (is (str/includes? (:body (get-page nb (str "/e/" entry))) "a ticket"))
      (is (pos? @calls)))))

(deftest an-entry-is-read-from-the-index
  (let [nb (k/notebook {:dir (str (temp/temp-dir) "/notes")})
        t1 "tik:5084203b-b89a-479e-9075-02fdf34806d9"
        t2 "tik:6afff677-67e5-45a0-aadb-551ef2348cdd"
        {:keys [entry]} (k/write! nb {:title "x" :labels {"kind" "fact"} :actor "claude" :evidence [t2 "a"]})]
    (k/revise! nb entry {:base (:revision (k/entry nb entry)) :body "y" :actor "seb" :evidence [t1 t2]})
    (k/observe! nb entry {:outcome "held" :evidence ["tik:00000000-0000-4000-8000-000000000000"] :actor "seb"})
    (let [reads (atom 0)
          e (with-redefs [store/revisions (fn [& _] (swap! reads inc) [])]
              (k/entry nb entry))]
      (is (zero? @reads) "no revision is read from the store")
      (is (= ["6afff677-67e5-45a0-aadb-551ef2348cdd" "5084203b-b89a-479e-9075-02fdf34806d9"
              "00000000-0000-4000-8000-000000000000"]
             (map :ticket (:tickets e)))
          "tickets in the order the revisions, then the observations, cite them"))
    (testing "entries reads what entry reads, without the tickets"
      (is (= [(dissoc (k/entry nb entry) :tickets)] (k/entries nb [entry "missing"]))))))
