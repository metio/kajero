;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.test-temp
  "Temporary directories for the tests. All of them live under one root
   directory per JVM in java.io.tmpdir. The `each` fixture deletes the ones a
   test made when the test ends, whether it passes, fails or throws, and a
   shutdown hook deletes the root, so a run leaves java.io.tmpdir as it found
   it and a JVM killed outright leaves at most the root behind."
  (:import (java.nio.file FileVisitResult Files LinkOption Path SimpleFileVisitor)
           (java.nio.file.attribute FileAttribute)))

(defn- delete-tree!
  "Deletes `path` and everything below it without following symbolic links."
  [^Path path]
  (when (Files/exists path (make-array LinkOption 0))
    (Files/walkFileTree path (proxy [SimpleFileVisitor] []
                               (visitFile [file _]
                                 (Files/delete file)
                                 FileVisitResult/CONTINUE)
                               (postVisitDirectory [dir _]
                                 (Files/delete dir)
                                 FileVisitResult/CONTINUE)))))

(defonce ^:private run-root
  (delay
    (let [dir (Files/createTempDirectory "kajero-test" (make-array FileAttribute 0))]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (delete-tree! dir))))
      dir)))

(defn root
  "The directory this JVM's temp dirs live under."
  ^Path []
  @run-root)

(def ^:private ^:dynamic *made*
  "The temp dirs the running test made, while `each` wraps it."
  nil)

(defn temp-dir
  "A fresh empty directory under the run root, as a string. Inside `each` it
   is deleted when the test ends; outside, when the JVM exits."
  []
  (let [dir (Files/createTempDirectory (root) "t" (make-array FileAttribute 0))]
    (some-> *made* (swap! conj dir))
    (str dir)))

(defn each
  "A test fixture that deletes the temp dirs the test made once it ends."
  [run-test]
  (binding [*made* (atom [])]
    (try (run-test)
         (finally (run! delete-tree! @*made*)))))
