;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.main
  "The single entry point of the uberjar and the native binary. The first
   argument picks the surface; everything else reads the same environment
   as `clojure -M:web`, `-M:mcp` and `-M:run`:

     kajero serve          the pages, and MCP on /mcp (kajero.web)
     kajero mcp            the MCP server over stdio (kajero.mcp)
     kajero <command> ...  the command line (kajero.cli)

   Requiring every surface here also puts each namespace on the table when
   native-image analyses the program, so no surface is loaded by name at
   run time."
  (:require [kajero.cli :as cli]
            [kajero.mcp :as mcp]
            [kajero.web :as web])
  (:gen-class))

(def ^:private usage
  "Usage: kajero serve | mcp | <command> [args]

  serve       the pages on http://127.0.0.1:7270 (KAJERO_PORT), and MCP on
              /mcp when KAJERO_MCP_TOKEN_FILE names a token file
  mcp         the MCP server over stdio
  help        this text

  Any other first argument is a command of the command line; run kajero
  without arguments to list them.")

(defn -main
  [& args]
  (case (first args)
    "serve" (web/-main)
    "mcp" (do (mcp/-main)
              ;; clojure.java.shell, which runs ssh-keygen and tik status,
              ;; reads their output on agent threads that would keep the
              ;; process alive for a minute after the client's stdin closes.
              (shutdown-agents))
    ("help" "--help" "-h") (println usage)
    (apply cli/-main args)))
