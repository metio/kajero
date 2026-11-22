;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.paths-test
  (:require [clojure.test :refer [deftest is testing]]
            [kajero.paths :as paths]))

(deftest the-paths-a-text-names
  (is (= #{"helm-charts/cluster-info/templates/_cert-issuer.tpl"}
         (paths/cited "The issuer comes from `helm-charts/cluster-info/templates/_cert-issuer.tpl`.")))
  (is (= #{"templates/_cert-issuer.tpl" "values.yaml"}
         (paths/cited "see templates/_cert-issuer.tpl, and values.yaml)")))
  (is (= #{"src/kajero/core.clj"} (paths/cited "src/kajero/core.clj:42 says so")))
  (is (= #{"./bin/run"} (paths/cited "run ./bin/run")) "a relative path keeps its dot")
  (is (empty? (paths/cited "plain words, no path at all."))))

(deftest a-path-names-the-same-file-at-a-segment-boundary
  (let [full "helm-charts/cluster-info/templates/_cert-issuer.tpl"]
    (testing "a cited suffix of the asked path"
      (is (paths/same-file? "templates/_cert-issuer.tpl" full))
      (is (paths/same-file? "_cert-issuer.tpl" full)))
    (testing "an asked suffix of the cited path"
      (is (paths/same-file? full "templates/_cert-issuer.tpl"))
      (is (paths/same-file? full "_cert-issuer.tpl")))
    (testing "only at a segment boundary"
      (is (not (paths/same-file? full "cert-issuer.tpl")))
      (is (not (paths/same-file? "x_cert-issuer.tpl" "_cert-issuer.tpl"))))
    (testing "a leading ./ and a trailing :line do not matter"
      (is (paths/same-file? "./templates/_cert-issuer.tpl" full))
      (is (paths/same-file? "src/kajero/core.clj:42" "core.clj")))
    (is (not (paths/same-file? full "templates/other.tpl")))))
