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
  (:import (sun.misc Signal SignalHandler))
  (:gen-class))

(def ^:private usage
  "Usage: kajero serve | mcp | <command> [args]

  serve       the pages on http://127.0.0.1:7270 (KAJERO_PORT), and MCP on
              /mcp when KAJERO_MCP_TOKEN_FILE names a token file
  mcp         the MCP server over stdio
  help        this text

  Any other first argument is a command of the command line; run kajero
  without arguments to list them.")

(def ^:private signal-exits
  "The status a signal ends a server with, as the JVM's own handlers set
   it: 128 plus the signal's number."
  {"TERM" 143 "INT" 130 "HUP" 129})

(defn exit-on-signals!
  "Ends the process on SIGTERM, SIGINT and SIGHUP through System/exit,
   which runs the shutdown hooks that flush the usage counts and sign the
   last checkpoint. The JVM does this by itself, but a native binary built
   without runtime option parsing installs no such handlers and would die
   on the signal without running a hook. A signal the platform does not
   let us handle is left alone."
  []
  (doseq [[sig status] signal-exits]
    (try (Signal/handle (Signal. ^String sig)
                        (reify SignalHandler
                          (handle [_ _] (System/exit (int status)))))
         (catch IllegalArgumentException _ nil))))

(defn -main
  [& args]
  (case (first args)
    "serve" (do (exit-on-signals!) (web/-main))
    "mcp" (do (exit-on-signals!)
              (mcp/-main)
              ;; clojure.java.shell, which runs ssh-keygen and tik status,
              ;; reads their output on agent threads that would keep the
              ;; process alive for a minute after the client's stdin closes.
              (shutdown-agents))
    ("help" "--help" "-h") (println usage)
    (apply cli/-main args)))
