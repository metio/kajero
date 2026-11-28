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

   The signature is the shared skill-manifest-v1 format of tik and kajero,
   signed and verified by briko.skill-manifest: an SSHSIG over the
   manifest's canonical bytes under the namespace kajero-skill-manifest,
   made with the key of the actor the server writes as, and carried in the
   entry's _meta under wtf.metio/skill-manifest. A server without a key
   says so there."
  (:require [briko.mcp :as rpc]
            [briko.skill-manifest :as sm]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kajero.skill-files :as files])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)))

(def extension-id "io.modelcontextprotocol/skills")

(def meta-key
  "The _meta key of the manifest signature on a skill entry."
  sm/meta-key)

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
                                             [path {:uri (sm/skill-uri skill path) :path path :bytes b :text text
                                                    :mimeType (mime-type path text)
                                                    :digest (sm/digest b) :size (alength b)}]))))
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
                                              [(subs uri (count (sm/skill-uri skill ""))) size]))
                  :total (reduce + (map :size resources))
                  :text? (every? #(some? (:text (get files (:uri %)))) resources)}])))

;; --------------------------------------------------------------- manifest

(def limits
  "The bounds a manifest is signed and verified under: Claude Code's
   per-file and per-skill sizes, and at most 256 files."
  {:max-files 256 :max-file-bytes max-file-bytes :max-skill-bytes max-skill-bytes})

(defn canonical-bytes
  "The skill-manifest-v1 canonical bytes of a skill entry (see
   briko.skill-manifest/canonical-bytes). Refuses an entry the format
   cannot write unambiguously, or one beyond `limits`."
  ^bytes [entry]
  (sm/canonical-bytes entry limits))

(defn sign-entry
  "The _meta value of the signature over `entry` by `writer`
   {:actor :key}: signed when the writer has both, else marked unsigned
   with the reason."
  [entry {:keys [actor key]}]
  (update-keys
   (cond
     (not key) (sm/unsigned "the server has no signing key")
     (str/blank? actor) (sm/unsigned "the server has a signing key but no actor to sign as")
     :else (try
             (sm/sign entry {:key-file key :actor actor :namespace signature-namespace :limits limits})
             (catch Exception e
               (sm/unsigned (str "signing failed: " (ex-message e))))))
   keyword))

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

(defn- reason
  "The reason kajero names for a verdict of briko.skill-manifest/verify
   that is not :verified."
  [{:keys [verdict] :as v} actors?]
  (case verdict
    :missing :no-signature
    :unsigned :unsigned
    :wrong-namespace :wrong-namespace
    :malformed (cond
                 (str/starts-with? (:reason v) "unknown manifest format") :wrong-format
                 (= "a signed value needs an actor and a signature" (:reason v)) :bad-signature
                 :else :bad-manifest)
    (:bad-signature :unchecked) (if actors? verdict :no-actors)))

(defn verify-entry
  "Whether the skill entry `entry` carries a manifest signature that
   verifies under the allowed-signers file `actors`, as
   {:verified? :actor :reason}. :reason names why it does not verify:
   :no-signature, :unsigned, :wrong-format, :wrong-namespace,
   :bad-manifest (a manifest the format refuses: a path with . or .., a
   skill without its SKILL.md, a file of another skill, beyond `limits`),
   :no-actors, :bad-signature, or :unchecked when no reader could judge
   the key. Keys may be keywords or strings, as JSON reads them."
  [actors entry]
  (let [f (some-> actors str io/file)
        actors? (boolean (and f (.isFile f)))
        v (sm/verify (if actors? (slurp f :encoding "UTF-8") "") entry
                     {:namespaces #{signature-namespace} :limits limits})]
    (if (= :verified (:verdict v))
      {:verified? true :actor (:actor v)}
      {:verified? false :actor (:actor v) :reason (reason v actors?)})))

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
               :when (not (and b (= size (alength b)) (= digest (sm/digest b))))]
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
