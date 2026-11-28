;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.skills
  "The agent skills the MCP server serves through the MCP Skills extension
   (io.modelcontextprotocol/skills): a catalog read from the classpath, the
   skill entries of skills/list and skills/get with their file manifests,
   the files for resources/read, and the signature over each manifest.

   A catalog holds every file's bytes in memory, keyed by its exact
   skill:// URI. A read looks its URI up and never touches a file system,
   so a URI with .., an absolute or encoded path, or anything the build
   did not stage names nothing and is refused.

   The signature is the shared skill-manifest-v1 format of tik and kajero
   (kajero entry 77f9c463): an SSHSIG over the manifest's canonical bytes
   under the namespace kajero-skill-manifest, made with the key of the
   actor the server writes as, and carried in the entry's _meta under
   wtf.metio/skill-manifest. A server without a key says so there."
  (:require [briko.mcp :as rpc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kajero.skill-files :as files]
            [briko.ssh-sign :as sign])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
           (java.security MessageDigest)
           (java.util HexFormat)))

(def extension-id "io.modelcontextprotocol/skills")

(def meta-key
  "The _meta key of the manifest signature on a skill entry."
  "wtf.metio/skill-manifest")

(def manifest-format "skill-manifest-v1")

(def signature-namespace
  "The ssh-keygen -Y namespace of manifest signatures, so a manifest
   signature never passes as a record, checkpoint or tik signature."
  "kajero-skill-manifest")

(def ttl-ms
  "How long a client may cache a listing or a file: the files change only
   with a new build."
  300000)

(def max-file-bytes
  "Claude Code loads a skill only while every text file is at most 100 KB."
  100000)

(def max-skill-bytes
  "Claude Code loads a skill only while its files add up to at most 400 KB."
  400000)

;; ------------------------------------------------------------ frontmatter

(defn- refuse! [message data]
  (throw (ex-info message (merge {:reason :kajero/bad-skill} data))))

(defn frontmatter
  "The YAML frontmatter of a SKILL.md as a map of strings.

   Only the subset our skills use is read: one `key: value` per line with a
   plain single-line value. Anything else - a quoted, block or flow value,
   a nested key, a comment - is refused rather than read differently from
   the YAML parser of a client, which compares the entry's frontmatter
   with the file field by field."
  [^String text]
  (let [lines (str/split-lines text)]
    (when-not (= "---" (first lines))
      (refuse! "SKILL.md does not begin with --- frontmatter" {}))
    (let [body (take-while #(not= "---" %) (rest lines))]
      (when (= (count body) (dec (count lines)))
        (refuse! "SKILL.md frontmatter has no closing ---" {}))
      (reduce (fn [m line]
                (let [[_ k v] (re-matches #"([a-z][a-z0-9_-]*): (.*\S)" line)]
                  (when (or (nil? k)
                            (re-find #"^[\"'|>\[{&*!%@`#-]|: | #" v)
                            (contains? m k))
                    (refuse! (str "SKILL.md frontmatter has a line this server does not read: " line) {}))
                  (assoc m k v)))
              (sorted-map) body))))

;; ---------------------------------------------------------------- catalog

(defn digest
  "`sha256:` and the lowercase hex sha256 of `b`."
  [^bytes b]
  (str "sha256:" (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") b))))

(defn- utf-8-text
  "`b` as a string when it is valid UTF-8, else nil."
  [^bytes b]
  (try (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                       (.onMalformedInput CodingErrorAction/REPORT)
                       (.onUnmappableCharacter CodingErrorAction/REPORT))
                     (ByteBuffer/wrap b)))
       (catch CharacterCodingException _ nil)))

(defn- mime-type [path text]
  (cond
    (str/ends-with? path ".md") "text/markdown"
    text "text/plain"
    :else "application/octet-stream"))

(defn skill-uri [skill path] (str "skill://" skill "/" path))

(defn catalog
  "A catalog from {skill {relative path bytes}}: per skill its entry
   without the signature, and per URI the file to serve."
  [skills]
  (let [built (for [[skill fs] (sort skills)
                    :let [_ (when-not (files/safe-name? skill)
                              (refuse! (str "not a skill name: " skill) {}))
                          docs (into (sorted-map)
                                     (for [[path ^bytes b] fs]
                                       (do (when-not (files/safe-path? path)
                                             (refuse! (str "not a path a skill may serve: " path) {}))
                                           (let [text (utf-8-text b)]
                                             [path {:uri (skill-uri skill path) :path path :bytes b :text text
                                                    :mimeType (mime-type path text)
                                                    :digest (digest b) :size (alength b)}]))))
                          skill-md (or (get docs "SKILL.md") (refuse! (str "the skill " skill " has no SKILL.md") {}))
                          fm (frontmatter (or (:text skill-md) (refuse! "SKILL.md is not UTF-8" {})))]]
                (do (when-not (= skill (get fm "name"))
                      (refuse! (str "SKILL.md of " skill " names the skill " (pr-str (get fm "name"))) {}))
                    (when-not (get fm "description")
                      (refuse! (str "SKILL.md of " skill " has no description") {}))
                    {:skill skill
                     :entry {:uri (:uri skill-md)
                             :frontmatter fm
                             :resources (mapv #(select-keys % [:uri :digest :size]) (vals docs))}
                     :docs (vals docs)}))]
    {:skills (into (sorted-map) (map (juxt :skill :entry)) built)
     :files (into {} (for [{:keys [docs]} built, d docs] [(:uri d) d]))}))

(defn from-directory
  "The catalog of a skills/ directory of the repository."
  [skills-dir]
  (catalog (into {} (for [[skill paths] (files/skills skills-dir)]
                      [skill (into {} (for [p paths]
                                        [p (java.nio.file.Files/readAllBytes (.toPath (io/file skills-dir skill p)))]))]))))

(defn- resource-bytes ^bytes [^ClassLoader loader path]
  (when-let [u (.getResource loader path)]
    (with-open [in (io/input-stream u)]
      (.readAllBytes in))))

(defn from-classpath
  "The catalog the build staged onto the classpath of `loader` (the
   context class loader by default), or nil when the classpath holds
   none, as in a checkout run without the build."
  ([] (from-classpath (.getContextClassLoader (Thread/currentThread))))
  ([^ClassLoader loader]
   (when-let [index (some-> (resource-bytes loader files/index-resource)
                            (String. StandardCharsets/UTF_8)
                            files/read-index)]
     (catalog (into {} (for [[skill paths] (:skills index)]
                         [skill (into {} (for [p paths]
                                           [p (or (resource-bytes loader (str files/resource-root "/" skill "/" p))
                                                  (refuse! (str "the skill index names " skill "/" p
                                                                " but the classpath does not hold it") {}))]))]))))))

(def served
  "The catalog of the classpath, read on first use: at run time, so the
   native binary reads the resources it registered."
  (delay (from-classpath)))

(defn sizes
  "Per skill, every file's size and the total, for the size gate."
  [{:keys [files skills]}]
  (into (sorted-map)
        (for [[skill {:keys [resources]}] skills]
          [skill {:files (into (sorted-map) (for [{:keys [uri size]} resources]
                                              [(subs uri (count (skill-uri skill ""))) size]))
                  :total (reduce + (map :size resources))
                  :text? (every? #(some? (:text (get files (:uri %)))) resources)}])))

;; --------------------------------------------------------------- manifest

(def ^:private uri-pattern #"skill://[a-z0-9]+(-[a-z0-9]+)*(/[A-Za-z0-9._-]+)+")

(defn canonical-bytes
  "The skill-manifest-v1 canonical bytes of a skill entry, built from its
   uri and resources alone. Refuses an entry whose URIs, digests or sizes
   the format cannot write unambiguously."
  ^bytes [{:keys [uri resources]}]
  (when-not (and (string? uri) (re-matches uri-pattern uri))
    (refuse! (str "the skill URI " (pr-str uri) " cannot be signed") {}))
  (when-not (and (sequential? resources) (seq resources))
    (refuse! "the skill entry has no file manifest" {}))
  (let [lines (for [{:keys [uri digest size]} (sort-by :uri resources)]
                (do (when-not (and (string? uri) (re-matches uri-pattern uri)
                                   (string? digest) (re-matches #"sha256:[0-9a-f]{64}" digest)
                                   (integer? size) (<= 0 size))
                      (refuse! (str "the manifest resource " (pr-str uri) " cannot be signed") {}))
                    (str "file " digest " " size " " uri "\n")))]
    (when-not (apply distinct? (map :uri resources))
      (refuse! "the manifest names a file twice" {}))
    (.getBytes (str "skill-manifest v1\n" "skill " uri "\n" (str/join lines)) StandardCharsets/UTF_8)))

(defn sign-entry
  "The _meta value of the signature over `entry` by `writer`
   {:actor :key}: signed when the writer has both, else marked unsigned
   with the reason."
  [entry {:keys [actor key]}]
  (let [unsigned (fn [reason] {:format manifest-format :signed false :reason reason})]
    (cond
      (not key) (unsigned "the server has no signing key")
      (not actor) (unsigned "the server has a signing key but no actor to sign as")
      :else (try
              {:format manifest-format :signed true :namespace signature-namespace :actor actor
               :signature (String. (sign/sign-bytes key (canonical-bytes entry) signature-namespace)
                                   StandardCharsets/UTF_8)}
              (catch Exception e
                (unsigned (str "signing failed: " (ex-message e))))))))

(defn signer
  "A function from a skill entry to the entry carrying `writer`'s
   signature in _meta. Each entry is signed once, on first use, because a
   signature is an ssh-keygen run."
  [writer]
  (let [memo (atom {})]
    (fn [entry]
      (let [m (or (get @memo entry)
                  (let [m (sign-entry entry writer)]
                    (swap! memo assoc entry m)
                    m))]
        (assoc entry :_meta {meta-key m})))))

(defn verify-entry
  "Whether the skill entry `entry` carries a manifest signature that
   verifies under the allowed-signers file `actors`, as
   {:verified? :actor :reason}. :reason names why it does not verify:
   :no-signature, :unsigned, :wrong-format, :wrong-namespace,
   :bad-manifest, :no-actors or :bad-signature. Keys may be keywords or
   strings, as JSON reads them."
  [actors entry]
  (let [kw (fn kw [x] (cond (map? x) (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) (kw v)])) x)
                            (sequential? x) (mapv kw x)
                            :else x))
        entry (kw (if (map? entry) entry {}))
        m (get-in entry [:_meta (keyword meta-key)])
        {:keys [format signed namespace actor signature]} m
        fail (fn [reason] {:verified? false :actor actor :reason reason})]
    (cond
      (not (map? m)) (fail :no-signature)
      (not= manifest-format format) (fail :wrong-format)
      (not (true? signed)) (fail :unsigned)
      (not= signature-namespace namespace) (fail :wrong-namespace)
      (not (and (string? actor) (string? signature))) (fail :bad-signature)
      (not (and actors (.isFile (io/file (str actors))))) (fail :no-actors)
      :else (let [message (try (canonical-bytes entry) (catch clojure.lang.ExceptionInfo _ nil))]
              (cond
                (nil? message) (fail :bad-manifest)
                (true? (sign/verify-bytes (str actors) message (.getBytes ^String signature StandardCharsets/UTF_8)
                                          actor signature-namespace))
                {:verified? true :actor actor}
                :else (fail :bad-signature))))))

(defn verify-files
  "The manifest resources of `entry` whose bytes, as `read-bytes` returns
   them for a path relative to the skill's root, are missing or differ in
   digest or size; empty when every file matches."
  [entry read-bytes]
  (let [{:keys [uri resources]} entry
        root (subs uri 0 (inc (str/last-index-of uri "/")))]
    (vec (for [{r-uri :uri :keys [digest size]} resources
               :let [path (when (str/starts-with? r-uri root) (subs r-uri (count root)))
                     ^bytes b (when (files/safe-path? path) (read-bytes path))]
               :when (not (and b (= size (alength b)) (= digest (kajero.skills/digest b))))]
           {:uri r-uri :reason (if b :mismatch :missing)}))))

;; ------------------------------------------------------------ the protocol

(defn- invalid! [message]
  (rpc/rpc-error! rpc/invalid-params message))

(defn list-result
  "The result of skills/list."
  [{:keys [skills]} sign]
  {:resultType "complete" :skills (mapv sign (vals skills)) :ttlMs ttl-ms :cacheScope "public"})

(defn get-result
  "The result of skills/get for the URI of a served skill's SKILL.md."
  [{:keys [skills]} sign uri]
  (let [entry (some #(when (= uri (:uri %)) %) (vals skills))]
    (when-not entry (invalid! (str "no skill found at " uri)))
    {:resultType "complete" :skill (sign entry) :ttlMs ttl-ms :cacheScope "public"}))

(defn read-result
  "The result of resources/read for the exact URI of a served file."
  [{:keys [files]} uri]
  (let [{:keys [mimeType text ^bytes bytes] :as f} (when (string? uri) (get files uri))]
    (when-not f (invalid! (str "no resource at " uri)))
    {:resultType "complete"
     :contents [(cond-> {:uri uri :mimeType mimeType}
                  text (assoc :text text)
                  (not text) (assoc :blob (.encodeToString (java.util.Base64/getEncoder) bytes)))]
     :ttlMs ttl-ms :cacheScope "public"}))

(defn resource-list
  "Every served file as an ordinary resource, for clients without the
   extension."
  [{:keys [files]}]
  (mapv (fn [{:keys [uri path mimeType size]}]
          {:uri uri :name (subs uri (count "skill://")) :title path :mimeType mimeType :size size})
        (sort-by :uri (vals files))))

(defn instructions
  "The server instructions naming each skill's SKILL.md URI, for clients
   that read resources but not the extension."
  [{:keys [skills]}]
  (when (seq skills)
    (str "kajero serves its agent skill" (when (next skills) "s") " as MCP resources: "
         (str/join ", " (map (fn [[skill {:keys [uri]}]] (str skill " at " uri)) skills))
         ". Read the SKILL.md with resources/read before using the kajero tools; it describes"
         " the selector grammar and how to write, revise and observe entries.")))
