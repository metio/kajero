;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.checkpoint
  "Signed checkpoints of the Merkle tree over a notebook's records and
   their signature verdicts - see kajero.merkle.

   A checkpoint says: one verifier judged exactly this set of records
   against the actors file of this digest and reached these verdicts. It
   is signed by a checkpoint key of its own, whose public key the
   operator pins in the configuration; the key signs nothing else, and a
   key the actors file grants to a record signer is refused, so holding a
   writer's key is never enough to vouch for verdicts. Each record keeps
   its own signature: the checkpoint only spares a later start from
   judging again what it covers.

   A checkpoint is two files in a directory beside the store, never inside
   it, so no sync, git checkout or store listing treats one as a record:

     <time>-<root>.checkpoint       the header, then one line per leaf
     <time>-<root>.checkpoint.sig   the signature over the header

   The header is one line of canonical EDN:

     {:format 1 :root :actors :tree :leaves :missing :missing-tree :at}

   :tree is the digest of the leaves below it, :root the sha256 of format,
   :actors and :tree (merkle/root), and :missing-tree the digest of the
   records an earlier checkpoint held that were gone when this one was
   signed. A leaf line is `L <hash> <sig> <verdict> <kind> <entries>` and a
   missing record's line `M ...` with the same fields; an absent signature
   or no entries is `-`, and entries are URL-encoded and comma-separated.

   A checkpoint is taken whole or not at all: a wrong signature, an unknown
   format, a malformed line or any digest that does not match the lines
   makes the whole file ignored with a warning, and nothing of it is used."
  (:require [briko.ssh-sign :as sign]
            [briko.sshsig :as sshsig]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kajero.merkle :as merkle]
            [kajero.record :as record]
            [kajero.verify :as verify])
  (:import (java.io File)
           (java.net URLDecoder URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files StandardCopyOption)
           (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def signature-namespace
  "The ssh-keygen -Y namespace of checkpoint signatures, so a checkpoint
   signature can never pass as a record's signature or the reverse."
  "kajero-checkpoint")

(def ^:private principal "kajero-checkpoint")

(def ^:private suffix ".checkpoint")

(def defaults
  "How often the service signs a checkpoint, after how many writes, and
   how many it keeps."
  {:interval-ms (* 10 60 1000) :writes 64 :keep 8})

;; ------------------------------------------------------------------ leaves

(defn record-entries
  "The entries a record of `kind` belongs to: its entry for a revision or
   an observation, source and target for a link, none for a page."
  [kind rec]
  (case kind
    (:revision :observation) [(str (:entry rec))]
    :link (vec (distinct [(str (:source rec)) (str (:target rec))]))
    :page []))

(defn leaf
  "The Merkle leaf of the record `rec` of `kind`, whose hash is `hash` and
   whose verdict is `verdict`."
  [kind rec hash verdict]
  {:hash hash
   :sig (some-> ^bytes (:sig rec) record/sha256)
   :verdict (verify/verdict->str verdict)
   :kind kind
   :entries (record-entries kind rec)})

(defn- enc [s] (URLEncoder/encode (str s) StandardCharsets/UTF_8))
(defn- dec* [s] (URLDecoder/decode (str s) StandardCharsets/UTF_8))

(defn- leaf-line
  [tag {:keys [hash sig verdict kind entries]}]
  (str tag " " hash " " (or sig "-") " " verdict " " (name kind) " "
       (if (seq entries) (str/join "," (map enc entries)) "-")))

(def ^:private kinds {"revision" :revision "observation" :observation "link" :link "page" :page})

(defn- parse-leaf
  "The leaf of the fields of one line, or nil when any field is malformed."
  [[hash sig verdict kind entries]]
  (when (and (merkle/valid-key? hash)
             (or (= "-" sig) (merkle/valid-key? sig))
             (verify/str->verdict verdict)
             (kinds kind)
             (some? entries))
    {:hash hash :sig (when-not (= "-" sig) sig) :verdict verdict :kind (kinds kind)
     :entries (if (= "-" entries) [] (mapv dec* (str/split entries #",")))}))

;; ------------------------------------------------------------- configuration

(defn beside
  "The directory beside a store for its checkpoints: <dir>.checkpoints for
   a flat-file notebook, <database>.checkpoints for SQLite."
  [{:keys [dir path]}]
  (str (str/replace (str (or dir path)) #"/+$" "") ".checkpoints"))

(defn- pubkey-line
  "The public key line `v` names: `v` itself when it is one, else the
   first line of the file at `v`."
  [v]
  (let [v (str/trim (str v))]
    (if (str/starts-with? v "ssh-")
      v
      (some->> (slurp v) str/split-lines (map str/trim) (remove str/blank?) first))))

(defn settings
  "The checkpoint settings of the environment `env` (a function of a
   variable's name) for the store `store-opts`, or nil when checkpoints
   are off:

     KAJERO_CHECKPOINT_PUBKEY    the pinned public key, as its line or the
                                 path of a file holding it; checkpoints are
                                 trusted only under this key
     KAJERO_CHECKPOINT_KEY       the private key that signs checkpoints; the
                                 public key defaults to its own
     KAJERO_CHECKPOINT_DIR       where checkpoints live, by default beside
                                 the store
     KAJERO_CHECKPOINT_INTERVAL  seconds between checkpoints (600)
     KAJERO_CHECKPOINT_WRITES    writes that cause a checkpoint (64)
     KAJERO_CHECKPOINT_KEEP      how many checkpoints are kept (8)"
  [env store-opts]
  (let [pub (env "KAJERO_CHECKPOINT_PUBKEY")
        k (env "KAJERO_CHECKPOINT_KEY")
        n (fn [v d] (or (some->> (env v) (re-matches #"[1-9][0-9]{0,8}") parse-long) d))]
    (when (or pub k)
      {:dir (or (env "KAJERO_CHECKPOINT_DIR") (beside store-opts))
       :key k
       :pubkey (when pub (pubkey-line pub))
       :interval-ms (* 1000 (n "KAJERO_CHECKPOINT_INTERVAL" (quot (long (:interval-ms defaults)) 1000)))
       :writes (n "KAJERO_CHECKPOINT_WRITES" (:writes defaults))
       :keep (n "KAJERO_CHECKPOINT_KEEP" (:keep defaults))})))

(defn- key-blob [line] (some-> (sshsig/key-blob line) seq))

(defn- granted-in-actors?
  [pubkey actors-text]
  (let [want (key-blob pubkey)]
    (boolean (and want (some #(= want (key-blob %)) (str/split-lines (str actors-text)))))))

(defn prepare
  "The settings `cfg` checked against the actors file text `actors-text`:
   {:config cfg :warnings [...]}, :config nil when checkpoints can not be
   trusted at all and without :key when this process can not sign.

   The public key must be an ed25519 key, judged in process without any
   fallback, and must not be granted by the actors file. A signing key
   whose public key is not the pinned one signs nothing."
  [cfg actors-text]
  (when cfg
    (let [derived (when-let [k (:key cfg)]
                    (try (sign/pubkey k)
                         (catch Exception e {:error (ex-message e)})))
          pub (or (:pubkey cfg) (when (string? derived) derived))
          off (fn [w] {:config nil :warnings [w]})]
      (cond
        (map? derived) (if (:pubkey cfg)
                         {:config (dissoc cfg :key)
                          :warnings [(str "the checkpoint key can not be read, so no checkpoint is signed: " (:error derived))]}
                         (off (str "the checkpoint key can not be read: " (:error derived))))
        (not (str/starts-with? (str pub) "ssh-ed25519 ")) (off "the checkpoint key is not an ed25519 key; checkpoints are off")
        (granted-in-actors? pub actors-text) (off "the actors file grants the checkpoint key to a record signer; checkpoints are off")
        (and derived (not= (key-blob derived) (key-blob pub)))
        {:config (assoc (dissoc cfg :key) :pubkey pub)
         :warnings ["the checkpoint key is not the pinned public key, so no checkpoint is signed"]}
        :else {:config (assoc cfg :pubkey pub) :warnings []}))))

;; ------------------------------------------------------------ the format

(defn- header-bytes
  ^bytes [header]
  (record/->bytes header))

(defn- allowed-signers [pubkey]
  (str principal " namespaces=\"" signature-namespace "\" " pubkey "\n"))

(defn- check-header
  "nil when the header's own fields are consistent, else the reason."
  [{:keys [format root actors tree leaves missing missing-tree at]}]
  (cond
    (not= merkle/format-version format) (str "unknown format " (pr-str format))
    (not (and (merkle/valid-key? root) (merkle/valid-key? tree) (merkle/valid-key? missing-tree))) "malformed digests"
    (not (or (nil? actors) (merkle/valid-key? actors))) "malformed actors digest"
    (not (and (nat-int? leaves) (nat-int? missing))) "malformed counts"
    (not (string? at)) "malformed time"
    (not= root (merkle/root format actors tree)) "the root does not match format, actors and tree"))

(defn- parse-body
  "[leaves missing-leaves] of the lines after the header, or nil when a
   line is malformed."
  [lines]
  (loop [[l & more] lines ls (transient []) ms (transient [])]
    (if (nil? l)
      [(persistent! ls) (persistent! ms)]
      (let [[tag & fields] (str/split l #" ")
            parsed (when (= 5 (count fields)) (parse-leaf fields))]
        (cond
          (str/blank? l) (recur more ls ms)
          (nil? parsed) nil
          (= "L" tag) (recur more (conj! ls parsed) ms)
          (= "M" tag) (recur more ls (conj! ms parsed))
          :else nil)))))

(defn read-checkpoint
  "The checkpoint in `file` checked against the pinned key `pubkey`:
   {:valid? true :checkpoint {:header :leaves :missing-leaves :file}}, or
   {:valid? false :file :reason}."
  [pubkey ^File file]
  (let [bad (fn [reason] {:valid? false :file (.getName file) :reason reason})
        sig-file (io/file (str file ".sig"))]
    (try
      (let [[first-line & body] (str/split-lines (slurp file :encoding "UTF-8"))
            header (edn/read-string first-line)]
        (cond
          (not (map? header)) (bad "the header is not a map")
          (not (.isFile sig-file)) (bad "no signature")
          (not (true? (sshsig/verify (allowed-signers pubkey) (header-bytes header)
                                     (Files/readAllBytes (.toPath sig-file)) principal signature-namespace)))
          (bad "the signature does not verify under the pinned checkpoint key")
          :else
          (if-let [reason (check-header header)]
            (bad reason)
            (if-let [[leaves missing] (parse-body body)]
              (cond
                (not= (:leaves header) (count leaves)) (bad "the leaf count does not match")
                (not= (:missing header) (count missing)) (bad "the missing count does not match")
                (not= (:tree header) (merkle/digest (merkle/build leaves))) (bad "the leaves do not match the tree digest")
                (not= (:missing-tree header) (merkle/digest (merkle/build missing))) (bad "the missing records do not match their digest")
                :else {:valid? true :checkpoint {:header header :leaves leaves :missing-leaves missing :file (.getName file)}})
              (bad "a malformed line")))))
      (catch Exception e (bad (str "unreadable: " (or (ex-message e) (.getName (class e)))))))))

(defn- checkpoint-files
  "The checkpoint files in `dir`, newest first."
  [dir]
  (->> (.listFiles (io/file dir))
       (filter #(and (.isFile ^File %) (str/ends-with? (.getName ^File %) suffix)))
       (sort-by #(.getName ^File %) #(compare %2 %1))))

(defn latest
  "{:latest checkpoint :warnings [{:file :reason}]}: the newest checkpoint
   in the configured directory that is valid under the pinned key, and a
   warning for every newer one that is not. :latest is nil when none is."
  [{:keys [dir pubkey]}]
  (loop [[f & more] (checkpoint-files dir) warnings []]
    (if (nil? f)
      {:latest nil :warnings warnings}
      (let [{:keys [valid? checkpoint] :as r} (read-checkpoint pubkey f)]
        (if valid?
          {:latest checkpoint :warnings warnings}
          (recur more (conj warnings (select-keys r [:file :reason]))))))))

;; --------------------------------------------------------- what it covers

(defn missing
  "The leaves of `checkpoint`, and the missing records it carried, whose
   record hash `tree` lacks: records that were there when it was signed,
   or were already missing then, and are gone now."
  [checkpoint tree]
  (->> (concat (:leaves checkpoint) (:missing-leaves checkpoint))
       (remove #(merkle/lookup tree (:hash %)))
       (reduce (fn [m l] (if (contains? m (:hash l)) m (assoc m (:hash l) l))) (sorted-map))
       vals
       vec))

(defn covered
  "How many leaves of `tree` the checkpoint holds with the same signature."
  [checkpoint tree]
  (count (filter #(when-let [l (merkle/lookup tree (:hash %))] (= (:sig l) (:sig %)))
                 (:leaves checkpoint))))

;; ----------------------------------------------------------------- signing

(def ^:private stamp (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSS'Z'") ZoneOffset/UTC))

(defn- move!
  [^File from ^File to]
  (try (Files/move (.toPath from) (.toPath to) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
       (catch AtomicMoveNotSupportedException _
         (Files/move (.toPath from) (.toPath to) (make-array CopyOption 0)))))

(defn- write-new!
  "Writes `bytes` to `f` through a temporary file in the same directory,
   so a reader sees the whole file or none."
  [^File f ^bytes bytes]
  (let [tmp (File/createTempFile ".kajero-checkpoint" ".tmp" (.getParentFile f))]
    (try (Files/write (.toPath tmp) bytes ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))
         (move! tmp f)
         (finally (.delete tmp)))))

(defn- prune!
  "Deletes all but the newest `keep` checkpoints and their signatures."
  [dir keep]
  (doseq [^File f (drop keep (checkpoint-files dir))]
    (.delete (io/file (str f ".sig")))
    (.delete f)))

(defn sign!
  "Signs a checkpoint of `tree`, whose verdicts were judged under the
   actors file of digest `actors`, carrying `missing-leaves`, at the
   instant `at`, and writes it into the configured directory, keeping the
   newest :keep. Returns the checkpoint as `latest` reads it."
  [{:keys [dir key keep]} {:keys [tree actors missing-leaves ^Instant at]}]
  (let [leaves (vec (merkle/leaves tree))
        missing-tree (merkle/build missing-leaves)
        tree-digest (merkle/digest tree)
        header {:format merkle/format-version
                :root (merkle/root merkle/format-version actors tree-digest)
                :actors actors
                :tree tree-digest
                :leaves (count leaves)
                :missing (count missing-leaves)
                :missing-tree (merkle/digest missing-tree)
                :at (str at)}
        hb (header-bytes header)
        sig (sign/sign-bytes key hb signature-namespace)
        body (str (String. hb StandardCharsets/UTF_8) "\n"
                  (str/join (map #(str (leaf-line "L" %) "\n") leaves))
                  (str/join (map #(str (leaf-line "M" %) "\n") (merkle/leaves missing-tree))))
        d (io/file dir)
        f (io/file d (str (.format ^DateTimeFormatter stamp at) "-" (subs (:root header) 7 23) suffix))]
    (.mkdirs d)
    (write-new! (io/file (str f ".sig")) sig)
    (write-new! f (.getBytes body StandardCharsets/UTF_8))
    (prune! dir (long (or keep (:keep defaults))))
    {:header header :leaves leaves :missing-leaves (vec (merkle/leaves missing-tree)) :file (.getName f)}))
