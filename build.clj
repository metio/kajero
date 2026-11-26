;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns build
  "The AOT uberjar the native binary is built from: clojure -T:build uber

   The jar carries the repository's skills/ tree as classpath resources
   under kajero-skills/, which the MCP server serves (kajero.skills), and
   resources/, whose native-image metadata registers them for the binary."
  (:require [clojure.tools.build.api :as b]
            [kajero.skill-files :as skill-files]))

(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/kajero.jar")

(defn uber [_]
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:native]})]
    (b/delete {:path "target"})
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (skill-files/stage! "skills" class-dir)
    (b/compile-clj {:basis basis
                    :ns-compile '[kajero.main]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'kajero.main})))
