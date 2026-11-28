;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.tik
  "tik tickets as evidence. An evidence item of the form tik:<ticket id> cites
   a ticket of the tik store the notebook is bound to; the citation is checked
   when it is written, and pages show the ticket's title and the stages it
   derives now, read in process through tik.api at the time of reading, or up
   to `display-ttl-ms` before it.

   A stage is shown only when it can be trusted: the ticket's events verify
   (tik's own verification ladder, every event signed by a key the store
   grants) and its pinned process definition is the one it derives under.
   Otherwise the ticket is shown with its title and the reasons under
   :problems, and without :current, so a page never presents a stage as if
   it held."
  (:require [briko.digest :as digest]
            [clojure.java.io :as io]
            [kajero.lru :as lru]
            [tik.api :as api]
            ;; tik.api resolves the SQLite driver of a tik.db store at run
            ;; time; requiring it here loads it when the native image is
            ;; built, where a run-time load would find nothing
            [tik.store.sqlite-jdbc])
  (:import (clojure.lang ExceptionInfo)))

(def ^:private uuid-pattern #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

(def ^:private ref-pattern (re-pattern (str "^tik:(" uuid-pattern ")$")))

(defn ticket-ids
  "The ticket ids an evidence list cites, in order."
  [evidence]
  (into [] (comp (keep #(second (re-matches ref-pattern (str %)))) (distinct)) evidence))

(defprotocol Lookup
  (for-display [lookup id]
    "The ticket `id` to show beside an entry, or nil when there is none.")
  (for-write [lookup id]
    "The ticket `id` when it exists, for checking a citation before it is
     written, or nil when it does not."))

(extend-protocol Lookup
  clojure.lang.IFn
  (for-display [f id] (f id))
  (for-write [f id] (f id)))

(defn- api-type
  "The tik.api refusal type of `e`, or nil when `e` is no tik.api refusal."
  [e]
  (when (instance? ExceptionInfo e)
    (let [t (:type (ex-data e))]
      (when (and (keyword? t) (= "tik.api" (namespace t))) t))))

(def unavailable
  "The problem named when the tik store itself cannot be read: the
   configured root is no store, holds both backends, or needs a driver the
   runtime lacks."
  "tik-unavailable")

(def ^:private trusted-signatures #{:verified :delegated})

(defn verified?
  "Whether a result of tik.api/verify-ticket lets kajero present the
   ticket's stage: every check of the ladder passed, and every event
   carries a signature the store grants. tik counts an unsigned event as a
   note, not a failure; a stage derived from an event nobody vouches for is
   not shown as holding."
  [{:keys [ok? events]}]
  (boolean (and ok? (seq events) (every? (comp trusted-signatures :signature) events))))

(def ^:private config-files
  "The files beside tickets/ whose contents decide whether a signature
   counts. A verdict is remembered only while they stay the same."
  ["actors" "pq-signers" "signatures.edn" "oidc.edn" "roles.edn" "delegation.edn"])

(defn- config-digest
  [root]
  (mapv (fn [n]
          (let [f (io/file root n)]
            (when (.isFile f) (digest/sha256-hex-file f))))
        config-files))

(def verify-ttl-ms
  "How long a verification verdict is reused for a ticket whose events and
   trust files have not changed. Identity bindings and delegations live in
   other tickets, so a change there is seen at the latest after this long."
  600000)

(defn store-lookup
  "The lookup of the tik store at `tik-root` (the directory holding
   tickets/ or tik.db), read in process through tik.api.

   Only a full ticket id is looked up; tik.api would resolve a prefix, but
   a citation names one ticket for good, and a prefix can come to name
   several. The store is opened once, on first use; a store that cannot be
   opened is tried again on the next lookup, so a mistyped root shows as
   `unavailable` until it is fixed instead of failing the notebook.

   - for-write answers {:ticket id} when the store holds the ticket, nil
     when it does not, and throws :kajero/tik-unavailable when the store
     cannot be read.
   - for-display answers {:ticket :title :process :current} for a ticket
     whose stage can be trusted (see the namespace docstring), the same
     without :current and with :problems for one whose stage cannot, and
     nil for a ticket the store does not hold. A problem is
     \"definition-mismatch\", \"unverified\", `unavailable`, or the name of
     the tik.api refusal (too-large, unreadable, definition-missing,
     malformed-roles, underivable). An exception that is no tik.api
     refusal is a bug in tik; for display it shows as \"tik-error\", so
     one ticket cannot fail every page citing it.

   Verification verdicts are remembered per ticket, keyed by the ids of its
   events and the digests of the trust files, for `verify-ttl-ms` at most,
   because tik.api/verify-ticket reads every ticket of the store to find
   the identity registry.

   There is no timeout: a lookup runs no process, and every read is bounded
   by tik.api's limits (1 MiB per event, 20000 events, 64 MiB per ticket,
   4 MiB per configuration or definition file), refused before reading."
  [tik-root & [{:keys [clock bound verify-ttl] :or {bound 1024 verify-ttl verify-ttl-ms}}]]
  (let [clock (or clock #(System/currentTimeMillis))
        handle (atom nil)
        store! (fn []
                 (or @handle
                     (let [h (api/open-store (str tik-root))]
                       (reset! handle h)
                       h)))
        verdicts (lru/cache bound)
        verified (fn [store id]
                   (let [k [(mapv :event/id (api/ticket store id)) (config-digest tik-root)]
                         now (clock)]
                     (or (when-let [[{:keys [at key ok]}] (lru/lookup verdicts id)]
                           (when (and (= key k) (< (- (long now) (long at)) (long verify-ttl)))
                             [ok]))
                         (let [ok (verified? (api/verify-ticket store id))]
                           (lru/put! verdicts id {:at now :key k :ok ok})
                           [ok]))))]
    (reify Lookup
      (for-write [_ id]
        (try
          (when-not (re-matches uuid-pattern (str id))
            (throw (ex-info "a tik citation names a full ticket id" {:type :tik.api/unknown-ticket})))
          {:ticket (str (api/resolve-id (store!) id))}
          (catch ExceptionInfo e
            (case (api-type e)
              :tik.api/unknown-ticket nil
              (:tik.api/not-a-store :tik.api/ambiguous-store :tik.api/backend-unavailable)
              (throw (ex-info (str "the tik store cannot be read: " (ex-message e))
                              {:reason :kajero/tik-unavailable :ticket id} e))
              (throw e)))))
      (for-display [_ id]
        (try
          (when-not (re-matches uuid-pattern (str id))
            (throw (ex-info "a tik citation names a full ticket id" {:type :tik.api/unknown-ticket})))
          (let [store (store!)
                d (api/derive store id {:allow-unpinned-definition true})
                {:keys [pinned derived-under]} (:definition d)
                problems (cond-> []
                           (not= pinned derived-under) (conj "definition-mismatch")
                           (not (first (verified store id))) (conj "unverified"))]
            (cond-> {:ticket id
                     :title (:title d)
                     :process (some-> (get-in d [:rules :process]) name)}
              (empty? problems) (assoc :current (mapv name (:current d)))
              (seq problems) (assoc :problems problems)))
          (catch ExceptionInfo e
            (case (api-type e)
              nil {:ticket id :problems ["tik-error"]}
              :tik.api/unknown-ticket nil
              (:tik.api/not-a-store :tik.api/ambiguous-store :tik.api/backend-unavailable)
              {:ticket id :problems [unavailable]}
              {:ticket id :problems [(name (api-type e))]}))
          (catch Exception _
            {:ticket id :problems ["tik-error"]}))))))

(def display-ttl-ms
  "How long a ticket read for display is shown before it is read again. An
   entry page shows every ticket its records cite, and each is derived and,
   unless remembered, verified."
  60000)

(def cache-bound
  "The most tickets remembered."
  1024)

(defn- found?
  "Whether a ticket answered for display shows that the store holds it."
  [t]
  (boolean (and t (not-any? #{unavailable} (:problems t)))))

(defn cached
  "The lookup `inner` - a Lookup, or a function from a ticket id to the
   ticket or nil - remembering its answers for display for `ttl-ms`, at
   most `bound` of them. A citation is checked for a write against a
   remembered answer only when that answer found the ticket; a ticket
   remembered as missing, or a store remembered as unreadable, is asked
   again, so a ticket made a moment ago is never refused from memory. A
   ticket a write found is remembered for writes without expiry: a tik
   store only grows. `clock` answers milliseconds, for tests."
  [inner & [{:keys [ttl-ms bound clock] :or {ttl-ms display-ttl-ms bound cache-bound}}]]
  (let [c (lru/cache bound)
        written (lru/cache bound)
        clock (or clock #(System/currentTimeMillis))
        remembered (fn [id]
                     (when-let [[{:keys [at ticket]}] (lru/lookup c id)]
                       (when (< (- (long (clock)) (long at)) (long ttl-ms)) [ticket])))
        ask! (fn [id] (let [at (clock) t (for-display inner id)] (lru/put! c id {:at at :ticket t}) t))]
    (reify Lookup
      (for-display [_ id]
        (if-let [[t] (remembered id)] t (ask! id)))
      (for-write [_ id]
        (let [t (first (remembered id))]
          (cond
            (found? t) t
            (lru/lookup written id) (first (lru/lookup written id))
            :else (let [t (for-write inner id)]
                    (when t (lru/put! written id t))
                    t)))))))
