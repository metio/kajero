;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.json-wire-test
  "The JSON kajero reads from MCP clients and writes back to them. The
   expected texts are printed by cheshire itself, so a response whose
   text holds no hidden code point must stay byte for byte what cheshire
   printed."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.mcp :as mcp]
            [kajero.test-temp :as temp]
            [kajero.web :as web]))

(use-fixtures :each temp/each)

(defn- notebook []
  (k/notebook {:path (str (temp/temp-dir) "/notes.db")}))

(def ^:private writer {:actor "claude"})

(defn- line [msg] (json/generate-string msg))

(defn- tool-call [id tool args]
  {:jsonrpc "2.0" :id id :method "tools/call" :params {:name tool :arguments args}})

(defn- text-of [response] (get-in response [:result :content 0 :text]))

(def ^:private token "test-token")

(defn- http-body
  "The body /mcp answers for request text `body`."
  [nb body]
  (:body (web/mcp-endpoint nb {:token token :writer writer :port 7270}
                           {:method "POST" :authorization (str "Bearer " token) :body body})))

(def ^:private parse-error-text
  "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"parse error: not a JSON value\"}}")

(deftest text-that-is-not-one-json-value-answers-the-same-parse-error
  (let [nb (notebook)]
    (doseq [bad ["not json" "" "   " "{\"jsonrpc\":" "{} {}" "[1] x" "{\"a\":1}}" "'x'" "{a:1}" "[1,]" "NaN"]]
      (testing (pr-str bad)
        (is (= {:jsonrpc "2.0" :id nil :error {:code -32700 :message "parse error: not a JSON value"}}
               (mcp/handle nb writer bad)))
        (is (= parse-error-text (http-body nb bad)))))))

(deftest tool-results-print-as-cheshire-prints-them
  (let [nb (notebook)
        written (json/parse-string (text-of (mcp/handle nb writer (line (tool-call 1 "kajero_write"
                                                                                    {:title "Größe of a table: 3 × 4"
                                                                                     :labels {:kind "fact" :system "calico"}
                                                                                     :body "line one\nline \"two\"\t</script>\\ é 😀"
                                                                                     :data_edn "{:rows [{:host \"w-1\" :cpus 8 :ratio 0.5 :big 123456789012345678901234567890 :at #inst \"2026-11-28T00:00:00Z\" :id #uuid \"4be4f685-64f7-4c5f-8a92-53599f9048d6\"}] :tags #{:a} :none nil}"
                                                                                     :evidence ["echo ok"]})))))
        id (get written "entry")]
    (is (string? id))
    (doseq [[tool args] [["kajero_get" {"entry" id}]
                         ["kajero_search" {"selector" "label:system=calico"}]
                         ["kajero_search" {"selector" "data:rows.host=w-1"}]
                         ["kajero_labels" {}]
                         ["kajero_history" {"entry" id}]]]
      (testing tool
        (let [expected (json/generate-string (mcp/call-tool nb writer tool args))
              response (mcp/handle nb writer (line (tool-call 2 tool args)))]
          (is (= expected (text-of response)))
          (is (= (json/generate-string response) (http-body nb (line (tool-call 2 tool args))))))))))

(deftest a-refusal-prints-as-cheshire-prints-it
  (let [nb (notebook)
        response (mcp/handle nb writer (line (tool-call 1 "kajero_get" {:entry "00000000-0000-0000-0000-000000000000"})))]
    (is (true? (get-in response [:result :isError])))
    (is (str/starts-with? (text-of response) "{\"refused\":\"no-entry\",\"message\":"))))

(deftest a-request-with-the-largest-recorded-payload-is-answered
  ;; 39626 bytes was the largest kajero_write request of the notebook on
  ;; 2026-11-28; this one is several times larger
  (let [nb (notebook)
        body (str/join "\n" (repeat 4000 "a line of an entry body with \"quotes\" and ümlauts"))
        data (pr-str {:rows (vec (for [i (range 3000)] {:host (str "w-" i) :cpus i}))})
        request (line (tool-call 1 "kajero_write" {:title "large" :labels {:kind "fact"} :body body :data_edn data}))]
    (is (< 150000 (count request)))
    (let [response (mcp/handle nb writer request)]
      (is (false? (get-in response [:result :isError])))
      (is (string? (get (json/parse-string (text-of response)) "entry"))))))

(deftest a-notification-answers-nothing-over-either-transport
  (let [nb (notebook)
        note (line {:jsonrpc "2.0" :method "notifications/initialized"})]
    (is (nil? (mcp/handle nb writer note)))
    (is (= "" (http-body nb note)))))
