;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.print
  "EDN text that does not depend on the printing thread.

   pr-str follows the dynamic print vars of whichever thread calls it.
   clojure.main sets *print-namespace-maps* to true on its main thread - the
   CLI and the stdio MCP loop - while the HTTP server's threads see false,
   and a caller may bind *print-length* or *print-level*, which truncate the
   text. A hash, a signature or a stored record printed under such settings
   would differ by process, so every text that is hashed, signed or stored
   goes through edn-str, which binds each of them to one fixed value.")

(def settings
  "The print settings of every EDN text kajero hashes, signs or stores.
   Namespaced maps print in full (`{:a/x 1}`, never `#:a{:x 1}`) because
   the HTTP service, which wrote nearly every record, printed them so."
  {:namespace-maps false})

(defn edn-str
  "`x` as EDN text, printed under the fixed settings. With
   {:namespace-maps true} it prints namespaced maps in the `#:a{...}` form
   clojure.main's thread uses, which only the verification of records
   signed under that form asks for."
  ([x] (edn-str settings x))
  ([{:keys [namespace-maps]} x]
   (binding [*print-namespace-maps* (boolean namespace-maps)
             *print-length* nil
             *print-level* nil
             *print-meta* false
             *print-dup* false
             *print-readably* true]
     (pr-str x))))
