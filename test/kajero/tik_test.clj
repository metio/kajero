;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.tik-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.tik :as tik]
            [kajero.test-temp :as temp]))

(use-fixtures :each temp/each)

(def ^:private known "5084203b-b89a-479e-9075-02fdf34806d9")
(def ^:private unknown "00000000-0000-0000-0000-000000000000")

(defn- fake-store [id]
  (when (= known id) {:ticket id :title "kajero: structured data" :current ["proven"] :process "skilled-change"}))

(defn- notebook [opts]
  (k/notebook (merge {:dir (str (temp/temp-dir) "/notes")} opts)))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(def ^:private entry
  {:title "malli refuses :fn schemas when sci is disabled" :labels {"kind" "gotcha" "system" "malli"} :actor "claude"})

(deftest evidence-cites-tickets-by-id
  (is (= [known] (tik/ticket-ids ["kubectl get nodes" (str "tik:" known) (str "tik:" known) "tik:nope"]))))

(deftest a-bound-notebook-checks-every-citation
  (let [nb (notebook {:tik-status fake-store})]
    (is (= :kajero/no-ticket (refusal #(k/write! nb (assoc entry :evidence [(str "tik:" unknown)])))))
    (let [{:keys [entry revision]} (k/write! nb (assoc entry :evidence [(str "tik:" known)]))]
      (is (= [{:ticket known :title "kajero: structured data" :current ["proven"] :process "skilled-change"}]
             (:tickets (k/entry nb entry))))
      (testing "observations and revisions are checked too"
        (is (= :kajero/no-ticket (refusal #(k/observe! nb entry {:outcome "held" :evidence [(str "tik:" unknown)] :actor "seb"}))))
        (is (= :kajero/no-ticket (refusal #(k/revise! nb entry {:base revision :body "x" :evidence [(str "tik:" unknown)] :actor "seb"}))))))))

(deftest an-unbound-notebook-takes-citations-unchecked
  (let [nb (notebook {})
        {:keys [entry]} (k/write! nb (assoc entry :evidence [(str "tik:" unknown)]))]
    (is (= [{:ticket unknown}] (:tickets (k/entry nb entry))))))

(deftest entries-are-selectable-by-the-ticket-they-cite
  (let [nb (notebook {:tik-status fake-store})
        {cited :entry} (k/write! nb (assoc entry :evidence [(str "tik:" known)]))
        {observed :entry} (k/write! nb (assoc entry :title "other"))]
    (k/observe! nb observed {:outcome "held" :evidence [(str "tik:" known)] :actor "seb"})
    (k/write! nb (assoc entry :title "unrelated"))
    (is (= #{cited observed} (set (map :entry (k/search nb (str "ticket=" known))))))))

;; ------------------------------------------------------------ the cache

(defn- counting-store
  "A ticket lookup that counts its calls, answering the tickets in the
   atom `tickets`."
  [tickets calls]
  (fn [id] (swap! calls inc) (get @tickets id)))

(deftest a-ticket-shown-is-asked-again-only-after-its-ttl
  (let [calls (atom 0)
        now (atom 0)
        lookup (tik/cached (counting-store (atom {known {:ticket known}}) calls) {:ttl-ms 60000 :clock #(deref now)})]
    (dotimes [_ 5] (is (= {:ticket known} (tik/for-display lookup known))))
    (is (= 1 @calls) "one tik status for five pages")
    (reset! now 59999)
    (tik/for-display lookup known)
    (is (= 1 @calls))
    (reset! now 60000)
    (tik/for-display lookup known)
    (is (= 2 @calls) "the answer expired")))

(deftest a-missing-ticket-is-remembered-for-display-but-never-for-a-write
  (let [calls (atom 0)
        tickets (atom {})
        lookup (tik/cached (counting-store tickets calls) {:clock (constantly 0)})]
    (is (nil? (tik/for-display lookup known)))
    (is (nil? (tik/for-display lookup known)))
    (is (= 1 @calls) "the page shows the ticket as missing from memory")
    (swap! tickets assoc known {:ticket known})
    (is (= {:ticket known} (tik/for-write lookup known)) "a write asks tik again and finds the new ticket")
    (is (= 2 @calls))
    (is (= {:ticket known} (tik/for-write lookup known)) "a ticket found is taken from memory")
    (is (= 2 @calls))))

(deftest a-write-citing-a-ticket-made-after-a-page-showed-it-missing-is-accepted
  (let [tickets (atom {})
        nb (notebook {:tik-status (tik/cached (counting-store tickets (atom 0)) {:clock (constantly 0)})})
        {e :entry} (k/write! nb entry)]
    (is (= :kajero/no-ticket (refusal #(k/observe! nb e {:outcome "held" :evidence [(str "tik:" known)] :actor "seb"}))))
    (swap! tickets assoc known {:ticket known :title "new"})
    (is (some? (k/observe! nb e {:outcome "held" :evidence [(str "tik:" known)] :actor "seb"})))
    (is (= [{:ticket known :title "new"}] (:tickets (k/entry nb e))))))

(deftest the-ticket-cache-is-bounded
  (let [calls (atom 0)
        lookup (tik/cached (fn [id] (swap! calls inc) {:ticket id}) {:bound 2 :clock (constantly 0)})]
    (doseq [id ["a" "b" "c"]] (tik/for-display lookup id))
    (is (= 3 @calls))
    (tik/for-display lookup "c")
    (is (= 3 @calls) "the most recent ticket is remembered")
    (tik/for-display lookup "a")
    (is (= 4 @calls) "the oldest one was forgotten")))
