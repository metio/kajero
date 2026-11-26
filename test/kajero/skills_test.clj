;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.skills-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kajero.skill-files :as files]
            [kajero.skills :as skills]
            [kajero.test-keys :as tk]
            [kajero.test-temp :as temp]
            [tik.sign :as sign])
  (:import (java.net URLClassLoader)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(use-fixtures :each temp/each)

(defn- staged-loader
  "A class loader over a temporary directory the build's staging step
   filled from the repository's skills/."
  []
  (let [classes (temp/temp-dir)]
    (files/stage! "skills" classes)
    (URLClassLoader. (into-array java.net.URL [(.toURL (.toURI (io/file classes)))]) nil)))

(defn- repo-files
  "{relative path bytes} of the repository's skills/kajero."
  []
  (let [root (.toPath (io/file "skills/kajero"))]
    (into (sorted-map)
          (for [^java.io.File f (file-seq (io/file "skills/kajero"))
                :when (.isFile f)]
            [(str/replace (str (.relativize root (.toPath f))) java.io.File/separator "/")
             (vec (Files/readAllBytes (.toPath f)))]))))

(defn- served-files [catalog]
  (into (sorted-map)
        (for [[_ {:keys [path bytes]}] (:files catalog)]
          [path (vec bytes)])))

(deftest the-served-tree-is-the-repository-skill-byte-for-byte
  (let [catalog (skills/from-classpath (staged-loader))]
    (is (= ["kajero"] (keys (:skills catalog))))
    (is (= (repo-files) (served-files catalog)))
    (testing "the catalog of the checkout's directory is the same"
      (is (= (:skills catalog) (:skills (skills/from-directory "skills")))))))

(deftest a-classpath-without-staged-skills-serves-none
  (is (nil? (skills/from-classpath (URLClassLoader. (into-array java.net.URL []) nil)))))

(deftest the-native-image-registers-the-staged-resources
  (let [meta (json/parse-string (slurp "resources/META-INF/native-image/io.github.metio/kajero/reachability-metadata.json") true)]
    (is (= [{:glob (str files/resource-root "/**")}] (:resources meta)))))

(deftest every-file-fits-claude-codes-limits
  (let [sizes (skills/sizes (skills/from-directory "skills"))]
    (println "skill sizes:" (pr-str sizes))
    (doseq [[skill {:keys [files total text?]}] sizes]
      (testing skill
        (is text? "Claude Code loads text files only")
        (doseq [[path size] files]
          (is (<= size skills/max-file-bytes) (str path " is " size " bytes")))
        (is (<= total skills/max-skill-bytes) (str skill " is " total " bytes"))))))

(deftest the-manifest-lists-every-file-with-digest-and-size
  (let [{:keys [skills files]} (skills/from-directory "skills")
        entry (get skills "kajero")
        repo (repo-files)]
    (is (= "skill://kajero/SKILL.md" (:uri entry)))
    (is (= "kajero" (get-in entry [:frontmatter "name"])))
    (is (string? (get-in entry [:frontmatter "description"])))
    (is (= (set (map #(str "skill://kajero/" %) (keys repo))) (set (map :uri (:resources entry)))))
    (doseq [{:keys [uri digest size]} (:resources entry)
            :let [b (byte-array (get repo (subs uri (count "skill://kajero/"))))]]
      (is (= size (alength b)) uri)
      (is (re-matches #"sha256:[0-9a-f]{64}" digest))
      (is (= digest (skills/digest b)) uri)
      (is (= size (:size (get files uri)))))))

(deftest a-known-digest
  (is (= "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
         (skills/digest (byte-array 0)))))

(def ^:private md
  (.getBytes "---\nname: demo\ndescription: A demo skill.\n---\n\n# Demo\n\nRead `references/a.md`.\n" "UTF-8"))

(defn- demo-catalog []
  (skills/catalog {"demo" {"SKILL.md" md "references/a.md" (.getBytes "# A\n" "UTF-8")}}))

(deftest the-canonical-bytes-are-the-shared-format
  (let [entry (get-in (demo-catalog) [:skills "demo"])
        d (fn [s] (skills/digest (.getBytes ^String s "UTF-8")))]
    (is (= (str "skill-manifest v1\n"
                "skill skill://demo/SKILL.md\n"
                "file " (skills/digest md) " " (alength ^bytes md) " skill://demo/SKILL.md\n"
                "file " (d "# A\n") " 4 skill://demo/references/a.md\n")
           (String. (skills/canonical-bytes entry) "UTF-8")))
    (testing "the order of the resources does not change the bytes"
      (is (= (vec (skills/canonical-bytes entry))
             (vec (skills/canonical-bytes (update entry :resources reverse))))))
    (testing "an entry the format cannot write unambiguously is refused"
      (doseq [bad [(assoc entry :uri "skill://demo/SKILL.md\nfile x")
                   (assoc-in entry [:resources 0 :uri] "skill://demo/a b")
                   (assoc-in entry [:resources 0 :digest] "sha256:XYZ")
                   (assoc-in entry [:resources 0 :size] -1)
                   (assoc entry :resources [])
                   (update entry :resources #(conj % (first %)))]]
        (is (thrown? clojure.lang.ExceptionInfo (skills/canonical-bytes bad)))))))

(deftest the-frontmatter-reader-refuses-what-it-would-misread
  (is (= {"description" "A demo skill." "name" "demo"}
         (skills/frontmatter "---\nname: demo\ndescription: A demo skill.\n---\nbody")))
  (doseq [bad ["no frontmatter"
               "---\nname: demo\n"
               "---\nname: \"demo\"\n---\n"
               "---\nname: demo\ndescription: |\n  block\n---\n"
               "---\nname: demo\nname: again\n---\n"
               "---\nname: demo # comment\n---\n"
               "---\nmeta:\n  nested: x\n---\n"
               "---\nname: a: b\n---\n"]]
    (is (thrown? clojure.lang.ExceptionInfo (skills/frontmatter bad)) bad)))

(deftest a-skill-md-must-name-its-directory
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names the skill"
                        (skills/catalog {"other" {"SKILL.md" md}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no SKILL.md"
                        (skills/catalog {"demo" {"README.md" md}}))))

(deftest the-signature-verifies-and-tampering-fails
  (if-let [{:keys [key actors]} (tk/keypair "claude")]
    (let [entry (get-in (demo-catalog) [:skills "demo"])
          ;; the actors line must admit the manifest namespace
          _ (spit actors (str/replace (slurp actors) "namespaces=\"kajero\"" "namespaces=\"kajero,kajero-skill-manifest\""))
          signed ((skills/signer {:actor "claude" :key key}) entry)
          m (get-in signed [:_meta skills/meta-key])]
      (is (= {:format "skill-manifest-v1" :signed true :namespace "kajero-skill-manifest" :actor "claude"}
             (dissoc m :signature)))
      (is (str/starts-with? (:signature m) "-----BEGIN SSH SIGNATURE-----"))
      (is (= {:verified? true :actor "claude"} (skills/verify-entry actors signed)))
      (testing "it verifies after a JSON round trip, as a client reads it"
        (is (:verified? (skills/verify-entry actors (json/parse-string (json/generate-string signed))))))
      (testing "a changed digest, size, URI or added file fails"
        (doseq [tampered [(assoc-in signed [:resources 0 :digest] (skills/digest (.getBytes "evil" "UTF-8")))
                          (update-in signed [:resources 0 :size] inc)
                          (update signed :resources conj {:uri "skill://demo/evil.md" :digest (skills/digest (byte-array 0)) :size 0})
                          (update signed :resources (comp vec rest))
                          (assoc signed :uri "skill://demo/references/a.md")]]
          (is (= :bad-signature (:reason (skills/verify-entry actors tampered))))))
      (testing "another actor's claim fails"
        (is (= :bad-signature (:reason (skills/verify-entry actors (assoc-in signed [:_meta skills/meta-key :actor] "seb"))))))
      (testing "a key the actors file does not hold fails"
        (let [{other :key} (tk/keypair "claude")
              forged ((skills/signer {:actor "claude" :key other}) entry)]
          (is (= :bad-signature (:reason (skills/verify-entry actors forged))))))
      (testing "a signature under another namespace fails, even with the namespace claim changed"
        (is (= :wrong-namespace (:reason (skills/verify-entry actors (assoc-in signed [:_meta skills/meta-key :namespace] "kajero")))))
        (let [record-sig (String. ^bytes (sign/sign-bytes key (skills/canonical-bytes entry) "kajero") "UTF-8")]
          (is (= :bad-signature (:reason (skills/verify-entry actors (assoc-in signed [:_meta skills/meta-key :signature] record-sig)))))))
      (testing "an actors line restricted to the record namespace does not admit a manifest signature"
        (spit actors (str/replace (slurp actors) "namespaces=\"kajero,kajero-skill-manifest\"" "namespaces=\"kajero\""))
        (is (= :bad-signature (:reason (skills/verify-entry actors signed)))))
      (testing "without an actors file nothing verifies"
        (is (= :no-actors (:reason (skills/verify-entry nil signed))))
        (is (= :no-actors (:reason (skills/verify-entry (str actors ".missing") signed))))))
    (println "skipped: no ssh-keygen")))

(deftest the-unsigned-mode-is-explicit
  (let [entry (get-in (demo-catalog) [:skills "demo"])]
    (doseq [[writer reason] [[{} #"no signing key"]
                             [{:actor "claude"} #"no signing key"]
                             [{:key "/nonexistent"} #"no actor"]
                             [{:actor "claude" :key "/nonexistent/key"} #"signing failed"]]]
      (let [m (get-in ((skills/signer writer) entry) [:_meta skills/meta-key])]
        (is (= {:format "skill-manifest-v1" :signed false} (dissoc m :reason)) (pr-str writer))
        (is (re-find reason (:reason m)))
        (is (= :unsigned (:reason (skills/verify-entry "/dev/null" ((skills/signer writer) entry)))))))
    (is (= :no-signature (:reason (skills/verify-entry "/dev/null" entry))))))

(deftest a-skill-uri-names-only-a-served-file
  (let [catalog (demo-catalog)]
    (is (= "# A\n" (get-in (skills/read-result catalog "skill://demo/references/a.md") [:contents 0 :text])))
    (doseq [uri ["skill://demo/../demo/SKILL.md"
                 "skill://demo/references/../SKILL.md"
                 "skill://demo/./SKILL.md"
                 "skill://demo//SKILL.md"
                 "skill://demo/%2e%2e/SKILL.md"
                 "skill://demo/references%2fa.md"
                 "skill://demo/references%2Fa.md"
                 "skill://demo/references\\a.md"
                 "skill:///etc/passwd"
                 "skill://demo/etc/passwd"
                 "skill://demo//etc/passwd"
                 "file:///etc/passwd"
                 "/etc/passwd"
                 "skill://demo/SKILL.md/"
                 "skill://DEMO/SKILL.md"
                 "skill://unknown/SKILL.md"
                 "skill://demo/missing.md"
                 ""
                 nil]]
      (let [e (try (skills/read-result catalog uri) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= -32602 (:code (ex-data e))) (pr-str uri))))))

(deftest skills-get-knows-only-the-skill-md-uris
  (let [catalog (demo-catalog)
        sign identity]
    (is (= "skill://demo/SKILL.md" (get-in (skills/get-result catalog sign "skill://demo/SKILL.md") [:skill :uri])))
    (doseq [uri ["skill://unknown/SKILL.md" "skill://demo/references/a.md" "skill://demo" "skill://demo/../demo/SKILL.md" nil]]
      (let [e (try (skills/get-result catalog sign uri) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= -32602 (:code (ex-data e))) (pr-str uri))))))

(defn- dir! [] (temp/temp-dir))

(deftest staging-refuses-a-symlink-and-unsafe-names
  (let [skills-dir (dir!)
        skill (io/file skills-dir "demo")]
    (.mkdirs (io/file skill "references"))
    (io/copy md (io/file skill "SKILL.md"))
    (is (= {"demo" ["SKILL.md"]} (files/skills skills-dir)))
    (testing "a symbolic link to a file outside the skill"
      (let [link (.toPath (io/file skill "references" "passwd"))]
        (Files/createSymbolicLink link (.toPath (io/file "/etc/passwd")) (make-array FileAttribute 0))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbolic link" (files/stage! skills-dir (dir!))))
        (Files/delete link)))
    (testing "a symbolic link to a directory"
      (let [link (.toPath (io/file skill "linked"))]
        (Files/createSymbolicLink link (.toPath (io/file "/etc")) (make-array FileAttribute 0))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbolic link" (files/skills skills-dir)))
        (Files/delete link)))
    (testing "a skill directory that is itself a symbolic link"
      (let [other (dir!)
            link (.toPath (io/file other "demo"))]
        (Files/createSymbolicLink link (.toPath skill) (make-array FileAttribute 0))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbolic link" (files/skills other)))))
    (testing "a file name a URI would need to escape"
      (let [f (io/file skill "references" "a b.md")]
        (spit f "x")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name a skill may not serve" (files/skills skills-dir)))
        (.delete f)))
    (is (= {"demo" ["SKILL.md"]} (files/skills skills-dir)))))

(deftest an-index-naming-an-unsafe-path-is-refused
  (doseq [bad [{:skills {"demo" ["../etc/passwd"]}}
               {:skills {"demo" ["/etc/passwd"]}}
               {:skills {"demo" ["a//b"]}}
               {:skills {"../x" ["SKILL.md"]}}]]
    (is (thrown? clojure.lang.ExceptionInfo (files/read-index (pr-str bad))) (pr-str bad))))

(deftest the-instructions-name-the-skill-md-uri
  (is (str/includes? (skills/instructions (skills/from-directory "skills")) "skill://kajero/SKILL.md")))
