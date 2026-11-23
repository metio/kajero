;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.cli-test
  (:require [clojure.test :refer [deftest is]]
            [kajero.cli :as cli]
            [kajero.core :as k])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- notebook []
  (k/notebook {:path (str (Files/createTempDirectory "kajero" (make-array FileAttribute 0)) "/notes.db")}))

(deftest observe-many-reads-its-observations-as-edn-from-stdin
  (let [nb (notebook)
        a (:entry (k/write! nb {:title "one" :labels {"kind" "fact"} :actor "seb"}))
        b (:entry (k/write! nb {:title "two" :labels {"kind" "fact"} :actor "seb"}))
        results (with-redefs [cli/writer (constantly {:actor "claude"})]
                  (with-in-str (pr-str [{:entry a :outcome "held" :evidence ["book"]}
                                        {:entry b :outcome "held"}])
                    (#'cli/run-command nb ["observe-many" "-"])))]
    (is (= [a b] (mapv :entry results)))
    (is (= [1 1] (mapv #(get-in (k/entry nb %) [:standing :held]) [a b])))))
