;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.verify
  "Whether a record's signature verifies, judged once per record and
   actors file and remembered.

   A record is in one of three states:

     :verified        its signature verifies as its actor against the
                      actors file read now
     :unsigned        it carries no signature
     :bad-signature   it carries one that does not verify: a key the actors
                      file does not give to that actor, a changed body, an
                      actor removed from the file, or no actors file at all

   A verdict is a pure function of the record, its signature and the text
   of the actors file, so it is cached under [record hash, sha256 of the
   signature, sha256 of the actors file]. Every judgement looks at the
   actors file's status - its change time, which only the kernel sets,
   size, inode and modification time - and reads the file again when any
   of them moved; the text is kept in memory while its digest stays the
   same, and a changed digest starts an empty cache, so an actor removed
   from the file reads as :bad-signature on the next judgement, never from
   a remembered verdict.

   A record signed over the bytes clojure.main's thread printed (see
   kajero.record/legacy-bytes) verifies too, and its verdict says
   :form :legacy; every other verified record says :form :canonical."
  (:require [clojure.java.io :as io]
            [kajero.record :as record]
            [tik.sign :as sign]
            [tik.sshsig :as sshsig])
  (:import (java.nio.file Files LinkOption NoSuchFileException Path)
           (java.nio.file.attribute BasicFileAttributes)
           (java.util ArrayList)
           (java.util.concurrent ConcurrentHashMap)
           (java.util.function Consumer Function)))

(defn check-signature
  "Whether `sig` is `actor`'s signature over `message` under the actors
   file whose text is `text` and whose path is `path`. Ed25519 is judged in
   process from the text; a key type tik.sshsig does not implement falls
   back to ssh-keygen over the file at `path`."
  [{:keys [text path]} ^bytes message ^bytes sig actor]
  (let [verdict (sshsig/verify text message sig actor record/signature-namespace)]
    (if (= ::sshsig/unsupported verdict)
      (boolean (sign/verify-bytes path message sig actor record/signature-namespace))
      (boolean verdict))))

(defn verifier
  "A verifier of signatures against the actors file at `actors`, which may
   be nil for a notebook without one. :check replaces check-signature,
   for tests."
  ([actors] (verifier actors {}))
  ([actors {:keys [check] :or {check check-signature}}]
   {:path (some-> actors str)
    :check check
    :registry (atom nil)}))

(defn- file-status
  "What changes whenever the file at `p` is written or replaced: its
   change time where the platform has one, its size, inode and
   modification time; nil when there is no file."
  [^Path p]
  (try
    (let [attrs (Files/readAttributes p BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" (make-array LinkOption 0))]
      [(try (Files/getAttribute p "unix:ctime" (make-array LinkOption 0))
            (catch UnsupportedOperationException _ nil)
            (catch IllegalArgumentException _ nil))
       (.size attrs) (.fileKey attrs) (.lastModifiedTime attrs)])
    (catch NoSuchFileException _ nil)))

(defn- read-actors
  "The actors file's bytes, or nil when there is none."
  ^bytes [^Path p]
  (try (Files/readAllBytes p)
       (catch NoSuchFileException _ nil)))

(defn- load-registry
  [path ^Path p status previous]
  (when-let [b (read-actors p)]
    (let [digest (record/sha256 b)]
      (if (= digest (:digest previous))
        (assoc previous :status status)
        {:path path :status status :digest digest :text (String. b "UTF-8")
         :verdicts (ConcurrentHashMap.)}))))

(defn registry
  "The actors file as it is now: {:path :text :digest :verdicts}, where
   :verdicts is the cache of judgements made under that text. The text and
   the cache are kept while the file's digest stays the same; nil when the
   verifier has no readable actors file."
  [{:keys [path registry]}]
  (when path
    (let [p (.toPath (io/file path))
          status (file-status p)
          current @registry]
      (cond
        (nil? status) nil
        (and current (= status (:status current))) current
        :else (let [loaded (load-registry path p status current)]
                ;; Two first readers may both load the file; the one that
                ;; stores second keeps the first one's cache when the text
                ;; is the same, so no verdict is judged twice.
                (swap! registry (fn [cur]
                                  (if (and cur loaded (= (:digest cur) (:digest loaded)))
                                    (assoc cur :status status)
                                    loaded))))))))

(defn- judge
  [check reg rec ^bytes canonical ^bytes sig]
  (let [actor (:actor rec)]
    (cond
      (and actor (check reg canonical sig actor))
      {:signature :verified :form :canonical}

      (when-let [legacy (and actor (record/legacy-bytes rec canonical))]
        (check reg legacy sig actor))
      {:signature :verified :form :legacy}

      :else {:signature :bad-signature})))

(defn verdict
  "{:signature :verified|:unsigned|:bad-signature} for the record, whose
   signature travels as :sig, with :form :canonical or :legacy when it
   verified. Judged once per record, signature and actors file text, even
   when several threads ask at once. `hash` is the record's hash when the
   caller has it already; `reg` is the registry of the request, read once
   for all the records it judges."
  ([v rec] (verdict v (registry v) rec nil))
  ([v rec hash] (verdict v (registry v) rec hash))
  ([{:keys [check]} reg rec hash]
   (let [^bytes sig (:sig rec)]
     (cond
       (nil? sig) {:signature :unsigned}
       (nil? reg) {:signature :bad-signature}
       :else
       (let [^ConcurrentHashMap cache (:verdicts reg)
             [canonical h] (if hash [nil hash] (record/bytes+hash rec))
             k [h (record/sha256 sig)]]
         (or (.get cache k)
             (.computeIfAbsent cache k
                               (reify Function
                                 (apply [_ _]
                                   (judge check reg rec (or canonical (record/->bytes rec)) sig))))))))))

(defn verified?
  "Whether the record's signature verifies now."
  [v rec]
  (= :verified (:signature (verdict v rec))))

(defn judge-all
  "[hash verdict] of every record of `records`, in their order, judged
   under the registry `reg` on the common fork-join pool. `hash-of` gives
   a record's hash, record/hash-of unless the caller remembers hashes."
  [v reg records hash-of]
  (let [recs (vec records)
        out (object-array (count recs))]
    (.forEach (.parallel (java.util.stream.IntStream/range 0 (count recs)))
              (reify java.util.function.IntConsumer
                (accept [_ i]
                  (let [rec (nth recs i)
                        h ((or hash-of record/hash-of) rec)]
                    (aset out i [h (verdict v reg rec h)])))))
    (vec out)))

(defn warm!
  "Judges every record of `records` under one reading of the actors file,
   on the common fork-join pool, so later judgements are cache hits.
   `hash-of` gives a record's hash, record/hash-of unless the caller
   remembers hashes. Returns the number judged."
  ([v records] (warm! v records nil))
  ([v records hash-of]
   (let [reg (registry v)
         recs (ArrayList. ^java.util.Collection (vec records))]
     (.forEach (.parallelStream recs)
               (reify Consumer
                 (accept [_ rec] (verdict v reg rec (when hash-of (hash-of rec))))))
     (.size recs))))

(defn verdict->str
  "A verdict as one word: verified:canonical, verified:legacy, unsigned
   or bad-signature."
  [{:keys [signature form]}]
  (if (= :verified signature)
    (str "verified:" (name form))
    (name signature)))

(defn str->verdict
  "The verdict verdict->str wrote as `s`, or nil for any other word."
  [s]
  (case s
    "verified:canonical" {:signature :verified :form :canonical}
    "verified:legacy" {:signature :verified :form :legacy}
    "unsigned" {:signature :unsigned}
    "bad-signature" {:signature :bad-signature}
    nil))

(defn seed!
  "Remembers verdicts judged earlier under the actors file whose digest is
   `actors`, from `leaves` of {:hash :sig :verdict} with :sig the sha256 of
   the signature, so judging those records again is a cache hit. Nothing
   is remembered unless `reg` is the registry of that same actors file, and
   a verdict already judged in this process is kept. Leaves without a
   signature need no judgement and are skipped. Returns the number taken."
  [reg actors leaves]
  (if-not (and reg actors (= actors (:digest reg)))
    0
    (let [^ConcurrentHashMap cache (:verdicts reg)]
      (reduce (fn [n {:keys [hash sig verdict]}]
                (let [v (str->verdict verdict)]
                  (if (and sig v (not= :unsigned (:signature v)))
                    (do (.putIfAbsent cache [hash sig] v) (inc n))
                    n)))
              0
              leaves))))
