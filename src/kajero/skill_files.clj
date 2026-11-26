;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.skill-files
  "The repository's skills/ tree, copied onto the classpath the jar and the
   native binary serve it from.

   The build stages skills/<name>/... as kajero-skills/<name>/... and
   writes kajero-skills/index.edn naming every file, because neither a jar
   nor a native image can list a directory of resources reliably. The
   server reads only the files the index names, so what it serves is
   exactly what the build staged.

   A skill's files are refused, failing the build, when a path holds a
   symbolic link, names something other than a regular file or a
   directory, or has a character outside A-Z a-z 0-9 . _ - /: the served
   URIs then need no escaping, and the canonical bytes of a signed
   manifest cannot be made ambiguous by a file name. This namespace needs
   nothing beyond Clojure and the JDK, so build.clj can require it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file Files LinkOption Path)))

(def resource-root
  "The classpath directory the staged skills live in."
  "kajero-skills")

(def index-resource
  "The classpath resource naming every staged file."
  (str resource-root "/index.edn"))

(def ^:private safe-segment #"[A-Za-z0-9._-]+")

(defn safe-path?
  "Whether `p`, a path relative to a skill's root with / separators, is
   one a skill may serve: non-empty segments of A-Z a-z 0-9 . _ - that are
   neither . nor .."
  [p]
  (and (string? p)
       (not (str/blank? p))
       (every? #(and (re-matches safe-segment %) (not (#{"." ".."} %)))
               (str/split p #"/" -1))))

(defn safe-name?
  "Whether `n` is a skill name: lowercase letters, digits and hyphens, as
   the Agent Skills specification asks of `name`."
  [n]
  (boolean (and (string? n) (re-matches #"[a-z0-9]+(-[a-z0-9]+)*" n))))

(defn- refuse! [message data]
  (throw (ex-info message (assoc data :reason :kajero/unsafe-skill-file))))

(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn tree
  "Every file below the skill directory `dir`, as sorted relative paths
   with / separators. Refuses a symbolic link anywhere below `dir`, or
   `dir` itself being one, anything that is neither a regular file nor a
   directory, and a path safe-path? refuses."
  [dir]
  (let [root (.toPath (io/file dir))]
    (when (Files/isSymbolicLink root)
      (refuse! (str "the skill directory " root " is a symbolic link") {:path (str root)}))
    (when-not (Files/isDirectory root no-follow)
      (refuse! (str "the skill directory " root " is not a directory") {:path (str root)}))
    (letfn [(walk [^Path p]
              (with-open [s (Files/newDirectoryStream p)]
                (vec (mapcat (fn [^Path child]
                               (let [rel (str/replace (str (.relativize root child)) java.io.File/separator "/")]
                                 (cond
                                   (Files/isSymbolicLink child)
                                   (refuse! (str "the skill file " rel " is a symbolic link") {:path rel})
                                   (not (safe-path? rel))
                                   (refuse! (str "the skill file " rel " has a name a skill may not serve") {:path rel})
                                   (Files/isDirectory child no-follow) (walk child)
                                   (Files/isRegularFile child no-follow) [rel]
                                   :else (refuse! (str "the skill file " rel " is not a regular file") {:path rel}))))
                             s))))]
      (vec (sort (walk root))))))

(defn skills
  "{name [relative path ...]} for every skill directory below `skills-dir`,
   each holding a SKILL.md."
  [skills-dir]
  (into (sorted-map)
        (for [^java.io.File d (sort-by #(.getName ^java.io.File %) (.listFiles (io/file skills-dir)))
              :let [n (.getName d)]]
          (do (when-not (safe-name? n)
                (refuse! (str "the skill directory " n " is not a skill name") {:path n}))
              (let [files (tree d)]
                (when-not (some #{"SKILL.md"} files)
                  (refuse! (str "the skill " n " has no SKILL.md") {:path n}))
                [n files])))))

(defn stage!
  "Copies every skill below `skills-dir` into `classes-dir` under
   resource-root and writes the index; returns the index."
  [skills-dir classes-dir]
  (let [index {:skills (skills skills-dir)}
        target (io/file classes-dir resource-root)]
    (doseq [[n files] (:skills index)
            f files
            :let [to (io/file target n f)]]
      (io/make-parents to)
      (io/copy (io/file skills-dir n f) to))
    (io/make-parents (io/file target "index.edn"))
    (spit (io/file target "index.edn") (pr-str index))
    index))

(defn read-index
  "The index text parsed, checking that every name and path in it is one
   stage! could have written; nil for nil."
  [text]
  (when text
    (let [{:keys [skills] :as index} (edn/read-string text)]
      (doseq [[n files] skills]
        (when-not (and (safe-name? n) (every? safe-path? files))
          (refuse! (str "the skill index names an unsafe skill or path under " n) {:path n})))
      index)))
