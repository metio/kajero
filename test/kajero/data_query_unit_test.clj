;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.data-query-unit-test
  (:require [clojure.test :refer [deftest is]]
            [kajero.data-query :as dq]))

(def ^:private data
  {:cluster "c1"
   :hosts [{:domain "a" :ips [{:ip "1"} {:ip "2"}]}
           {"domain" "b" :ips []}
           "not a map"
           {:domain "c"}]})

(deftest contexts-are-get-in-paths-of-the-deepest-element
  (is (= [[]] (dq/contexts data (dq/parse "data:cluster=c1"))))
  (is (= [[:hosts 0]] (dq/contexts data (dq/parse "data:hosts.domain=a"))))
  (is (= [[:hosts 1]] (dq/contexts data (dq/parse "data:hosts.domain=b"))) "a string key, kept as the string")
  (is (= [[:hosts 0 :ips 1]] (dq/contexts data (dq/parse "data:hosts.ips.ip=2"))))
  (is (= [[:hosts 0] [:hosts 1] [:hosts 3]] (dq/contexts data (dq/parse "data:hosts.domain"))) "elements that are not maps are skipped")
  (is (= [] (dq/contexts data (dq/parse "data:hosts.domain=z")))))

(deftest a-top-level-list-gives-index-contexts
  (is (= [[1]] (dq/contexts [{:x 1} {:x 2}] (dq/parse "data:x=2"))))
  (is (= [] (dq/contexts "scalar" (dq/parse "data:x"))))
  (is (= [] (dq/contexts nil (dq/parse "data:x")))))

(deftest matching-picks-the-deepest-common-elements
  (is (= [{:ip "1"}] (dq/matching data [(dq/parse "data:cluster=c1") (dq/parse "data:hosts.ips.ip=1")])))
  (is (= [{"domain" "b" :ips []}] (dq/matching data [(dq/parse "data:hosts.domain=b")])))
  (is (= [data] (dq/matching data [(dq/parse "data:cluster")])))
  (is (= [] (dq/matching data [(dq/parse "data:hosts.domain=a") (dq/parse "data:hosts.domain=c")]))
      "two hosts never make one"))

(deftest a-present-nil-is-not-presence
  (is (= [] (dq/contexts {:x nil} (dq/parse "data:x"))))
  (is (= [] (dq/contexts {:x nil} (dq/parse "data:x=nil")))))
