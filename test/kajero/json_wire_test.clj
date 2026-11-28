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

(defn- parse-error-text
  "The -32700 line briko.mcp writes for a refusal with `message` and `data`."
  [message data]
  (str "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"parse error: " message
       "\",\"data\":" data "}}"))

(def ^:private malformed-at-1-1
  (parse-error-text "the input is not valid JSON at line 1, column 1" "{\"rule\":\"malformed\",\"line\":1,\"column\":1}"))

(def ^:private no-value
  (parse-error-text "the input holds no JSON value" "{\"rule\":\"no-value\"}"))

(deftest text-that-is-not-one-json-value-answers-a-parse-error-naming-the-rule
  (let [nb (notebook)
        trailing (parse-error-text "the input holds text after its JSON value" "{\"rule\":\"trailing-value\"}")
        malformed-at (fn [column]
                       (parse-error-text (str "the input is not valid JSON at line 1, column " column)
                                         (str "{\"rule\":\"malformed\",\"line\":1,\"column\":" column "}")))]
    (doseq [[bad expected] [["not json" malformed-at-1-1]
                            ["" no-value]
                            ["   " no-value]
                            ["{\"jsonrpc\":" (malformed-at 12)]
                            ["{} {}" trailing]
                            ["[1] x" trailing]
                            ["{\"a\":1}}" trailing]
                            ["'x'" malformed-at-1-1]
                            ["{a:1}" (malformed-at 2)]
                            ["[1,]" (malformed-at 4)]
                            ["NaN" (malformed-at 4)]]]
      (testing (pr-str bad)
        (is (= expected (json/generate-string (mcp/handle nb writer bad))))
        (is (= expected (http-body nb bad)))
        (testing "the answer quotes none of the text"
          (when (< 3 (count (str/trim bad)))
            (is (not (str/includes? (get-in (json/parse-string expected) ["error" "message"]) (str/trim bad))))))))))

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

(defn- error-message [response]
  (is (= -32700 (get-in response [:error :code])))
  (is (nil? (:id response)))
  (get-in response [:error :message]))

(deftest hidden-code-points-in-a-result-are-escaped
  (let [nb (notebook)
        title (str "right" (char 0x202E) "to-left and zero" (char 0x200B) "width")
        written (json/parse-string (text-of (mcp/handle nb writer (line (tool-call 1 "kajero_write"
                                                                                   {:title title :labels {:kind "fact"}
                                                                                    :body "b" :evidence ["echo ok"]})))))
        get-line (line (tool-call 2 "kajero_get" {:entry (get written "entry")}))
        text (text-of (mcp/handle nb writer get-line))
        http (http-body nb get-line)]
    (is (str/includes? text "right\\u202Eto-left and zero\\u200Bwidth"))
    (is (not (re-find #"[‮​]" text)))
    (is (= title (get (json/parse-string text) "title")))
    (is (not (re-find #"[‮​]" http)))
    (is (= title (get (json/parse-string (text-of (json/parse-string http true))) "title")))))

(deftest a-message-naming-a-key-twice-is-refused
  (let [nb (notebook)
        twice "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"method\":\"tools/call\"}"]
    (is (str/starts-with? (error-message (mcp/handle nb writer twice))
                          "parse error: the input names an object key twice"))
    (testing "control: the same message without the second key is answered"
      (is (seq (get-in (mcp/handle nb writer "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                       [:result :tools]))))))

(deftest a-raw-control-character-in-a-string-is-refused
  (let [nb (notebook)
        raw (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"x\":\"a" (char 1) "b\"}}")]
    (is (= "parse error: the input is not valid JSON at line 1, column 63" (error-message (mcp/handle nb writer raw))))
    (is (= {:rule "malformed" :line 1 :column 63} (get-in (mcp/handle nb writer raw) [:error :data])))
    (testing "control: the escaped character is read"
      (is (seq (get-in (mcp/handle nb writer (str/replace raw (str (char 1)) "\\u0001")) [:result :tools]))))))

(defn- nested [levels]
  ;; the message object and params are two levels
  (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"x\":"
       (str/join (repeat (- levels 2) "[")) (str/join (repeat (- levels 2) "]")) "}}"))

(deftest a-message-nested-deeper-than-64-levels-is-refused
  (let [nb (notebook)]
    (is (seq (get-in (mcp/handle nb writer (nested 64)) [:result :tools])))
    (is (= "parse error: the input nests deeper than 64 levels" (error-message (mcp/handle nb writer (nested 65)))))))

(deftest a-number-beyond-a-double-is-refused
  (let [nb (notebook)
        msg #(str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"x\":" % "}}")]
    (is (seq (get-in (mcp/handle nb writer (msg "1e300")) [:result :tools])))
    (is (= "parse error: a number literal is too large for a double" (error-message (mcp/handle nb writer (msg "1e400")))))))

(deftest a-message-longer-than-the-bound-is-refused
  (let [nb (notebook)
        padded (fn [n]
                 (let [head "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"x\":\""
                       tail "\"}}"]
                   (str head (str/join (repeat (- n (count head) (count tail)) "a")) tail)))]
    (is (= 16777216 mcp/max-message-bytes))
    (is (seq (get-in (mcp/handle nb writer (padded mcp/max-message-bytes)) [:result :tools])))
    (is (= "parse error: the input is larger than 16777216 bytes"
           (error-message (mcp/handle nb writer (padded (inc mcp/max-message-bytes))))))))

(deftest data-longer-than-one-mebibyte-is-written-and-read-back
  ;; briko.json's default bound is 1 MiB; kajero.data reads up to 16 MiB
  (let [nb (notebook)
        data (pr-str {:rows (vec (for [i (range 80000)] {:host (str "worker-" i) :cpus (mod i 64)}))})
        request (line (tool-call 1 "kajero_write" {:title "wide" :labels {:kind "fact"} :data_edn data}))
        _ (is (< (* 2 1024 1024) (count request)))
        written (json/parse-string (text-of (mcp/handle nb writer request)))
        read (json/parse-string (text-of (mcp/handle nb writer (line (tool-call 2 "kajero_get" {:entry (get written "entry")})))))]
    (is (= data (get read "data_edn")))))

(deftest a-result-whose-keys-collide-in-json-is-a-refusal
  (let [nb (notebook)
        written (json/parse-string (text-of (mcp/handle nb writer (line (tool-call 1 "kajero_write"
                                                                                   {:title "collide" :labels {:kind "fact"}
                                                                                    :data_edn "{nil 1 \"\" 2}"})))))
        response (mcp/handle nb writer (line (tool-call 2 "kajero_get" {:entry (get written "entry")})))
        refusal (json/parse-string (text-of response))]
    (is (string? (get written "entry")))
    (is (true? (get-in response [:result :isError])))
    (is (= "unwritable-result" (get refusal "refused")))
    (testing "control: data whose keys stay apart reads"
      (let [w (json/parse-string (text-of (mcp/handle nb writer (line (tool-call 3 "kajero_write"
                                                                                 {:title "apart" :labels {:kind "fact"}
                                                                                  :data_edn "{nil 1 \"b\" 2}"})))))]
        (is (false? (get-in (mcp/handle nb writer (line (tool-call 4 "kajero_get" {:entry (get w "entry")})))
                            [:result :isError])))))))

(deftest stdio-answers-one-line-per-request-and-nothing-for-a-notification
  (let [nb (notebook)
        server (mcp/server nb writer nil nil nil)
        input (str/join "\n" [(line {:jsonrpc "2.0" :id 1 :method "initialize" :params {:protocolVersion "2025-06-18"}})
                              (line {:jsonrpc "2.0" :method "notifications/initialized"})
                              "not json"
                              (line (tool-call 2 "kajero_write" {:title (str "a" (char 0x200B) "b") :labels {:kind "fact"}}))
                              ""
                              ""])
        out (with-out-str (with-in-str input (mcp/serve-stdio server)))
        answers (str/split-lines out)]
    (is (= 4 (count answers)))
    (is (= 1 (get (json/parse-string (first answers)) "id")))
    (is (= malformed-at-1-1 (nth answers 1)))
    (is (= 2 (get (json/parse-string (nth answers 2)) "id")))
    (is (= no-value (nth answers 3)))))

(deftest a-response-json-cannot-hold-answers-an-internal-error-for-its-id
  (let [answer (json/parse-string (mcp/write-message {:jsonrpc "2.0" :id 3 :result {nil 1 "" 2}}))]
    (is (= 3 (get answer "id")))
    (is (= -32603 (get-in answer ["error" "code"])))
    (is (string? (get-in answer ["error" "data" "rule"]))))
  (testing "control: a response whose keys stay apart is written as it is"
    (is (= "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"a\":1,\"b\":2}}"
           (mcp/write-message {:jsonrpc "2.0" :id 3 :result {"a" 1 "b" 2}})))))
