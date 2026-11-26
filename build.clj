;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns build
  "The AOT uberjar the native binary is built from: clojure -T:build uber"
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/kajero.jar")

(defn uber [_]
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:native]})]
    (b/delete {:path "target"})
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :ns-compile '[kajero.main]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'kajero.main})))
