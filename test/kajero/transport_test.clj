;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.transport-test
  "stdio and HTTP /mcp answer one message with the same text: the same
   lines go through serve-stdio and through web/mcp-endpoint, and every
   answer must match byte for byte, the parse errors included."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.core :as k]
            [kajero.mcp :as mcp]
            [kajero.skills :as skills]
            [kajero.test-temp :as temp]
            [kajero.web :as web]))

(use-fixtures :each temp/each)

(def ^:private writer {:actor "claude"})

;; built at run time, so the source holds no credential-shaped literal
(def ^:private token (str "t-" (random-uuid)))

(defn- line [msg] (json/generate-string msg))

(defn- tool-call [id tool args]
  {:jsonrpc "2.0" :id id :method "tools/call" :params {:name tool :arguments args}})

(def ^:private modern-meta
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientInfo" {"name" "test" "version" "1"}
   "io.modelcontextprotocol/clientCapabilities" {}})

(defn- nested [levels]
  (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"x\":"
       (str/join (repeat (- levels 2) "[")) (str/join (repeat (- levels 2) "]")) "}}"))

(defn- inputs
  "[line headers] pairs: what a client sends, and the MCP headers an HTTP
   client sends with it (stdio carries none)."
  [entry]
  (let [legacy (fn [msg] [(line msg) {}])]
    [(legacy {:jsonrpc "2.0" :id 1 :method "initialize" :params {:protocolVersion "2025-06-18"}})
     (legacy {:jsonrpc "2.0" :method "notifications/initialized"})
     (legacy {:jsonrpc "2.0" :id 2 :method "tools/list"})
     (legacy {:jsonrpc "2.0" :id 3 :method "no/such-method"})
     (legacy (tool-call 4 "kajero_get" {:entry entry}))
     (legacy (tool-call 5 "kajero_search" {:selector "label:kind=fact"}))
     (legacy (tool-call 6 "kajero_get" {:entry "00000000-0000-0000-0000-000000000000"}))
     (legacy (tool-call 7 "no_such_tool" {}))
     (legacy {:jsonrpc "2.0" :id "eight" :method "tools/call" :params {:name "kajero_labels" :arguments []}})
     [(line {:jsonrpc "2.0" :id 9 :method "tools/list" :params {:_meta modern-meta}})
      {"mcp-protocol-version" ["2026-07-28"] "mcp-method" ["tools/list"]}]
     ;; every kind of text briko.json refuses
     ["not json" {}]
     ["" {}]
     ["   " {}]
     ["{\"jsonrpc\":" {}]
     ["{} {}" {}]
     ["[1] x" {}]
     ["{\"a\":1}}" {}]
     ["'x'" {}]
     ["{a:1}" {}]
     ["[1,]" {}]
     ["NaN" {}]
     ["{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"method\":\"tools/call\"}" {}]
     [(str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"x\":\"a" (char 1) "b\"}}") {}]
     [(nested 65) {}]
     ["{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"x\":1e400}}" {}]
     [(str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"x\":"
           (str/join (repeat 101 "1")) "}}") {}]
     ;; the same messages, readable
     [(nested 64) {}]
     ["{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"x\":1e300}}" {}]]))

(defn- http-answer
  "The body /mcp answers for `body` with `headers`: \"\" for a
   notification, as stdio writes no line for one."
  [nb body headers]
  (let [r (web/mcp-endpoint nb {:token token :writer writer :port 7270}
                            {:method "POST" :authorization (str "Bearer " token)
                             :mcp-headers headers :body body})]
    (:body r)))

(defn- stdio-answers
  "The lines serve-stdio writes for each input line, one call per input,
   nil where it writes none."
  [nb lines]
  (let [server (mcp/server nb writer nil @skills/served nil)]
    (for [l lines]
      (let [out (with-out-str (with-in-str (str l "\n") (mcp/serve-stdio server)))]
        (when-not (str/blank? out)
          (is (str/ends-with? out "\n"))
          (is (= 1 (count (str/split-lines out))))
          (str/trim-newline out))))))

(deftest stdio-and-http-answer-every-line-with-the-same-text
  (let [nb (k/notebook {:path (str (temp/temp-dir) "/notes.db")})
        written (json/parse-string (get-in (mcp/handle nb writer (line (tool-call 0 "kajero_write"
                                                                                  {:title (str "a" (char 0x200B) "b")
                                                                                   :labels {:kind "fact"} :body "ü 😀"
                                                                                   :evidence ["echo ok"]})))
                                           [:result :content 0 :text]))
        pairs (inputs (get written "entry"))
        stdio (stdio-answers nb (map first pairs))
        parse-errors (atom 0)]
    (doseq [[[l headers] s] (map vector pairs stdio)]
      (testing (pr-str (subs l 0 (min 80 (count l))))
        (let [h (http-answer nb l headers)]
          (is (= (or s "") h))
          (when (and s (= -32700 (get-in (json/parse-string s) ["error" "code"])))
            (swap! parse-errors inc)))))
    (testing "control: the parse-error inputs reach both transports as -32700"
      (is (= 16 @parse-errors)))
    (testing "control: a changed answer is told apart"
      (let [[l headers] (first pairs)]
        (is (not= (str/replace (first stdio) "\"id\":1" "\"id\":2") (http-answer nb l headers)))))))

(deftest a-line-longer-than-the-bound-answers-on-stdio-and-the-next-line-is-read
  ;; HTTP refuses such a body with 413 before reading it (web/request-guard)
  (let [nb (k/notebook {:path (str (temp/temp-dir) "/notes.db")})
        server (mcp/server nb writer nil @skills/served nil)
        long-line (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"x\":\""
                       (str/join (repeat (inc mcp/max-message-bytes) "a")) "\"}}")
        ping (line {:jsonrpc "2.0" :id 2 :method "ping"})
        answers (str/split-lines (with-out-str (with-in-str (str long-line "\n" ping "\n") (mcp/serve-stdio server))))]
    (is (= 2 (count answers)))
    (is (= -32700 (get-in (json/parse-string (first answers)) ["error" "code"])))
    (is (str/includes? (first answers) "larger than 16777216 bytes"))
    (is (= {"jsonrpc" "2.0" "id" 2 "result" {}} (json/parse-string (second answers))))))
