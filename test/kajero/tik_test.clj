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
