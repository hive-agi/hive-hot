(ns hive-hot.alias-trap-test
  "The hot-reload alias trap, and the reload report naming its root cause.

   A reload removes a namespace and creates a NEW Namespace object under the
   same name. A namespace that survives the pass keeps an alias to the OLD
   object, and the next time its ns form runs `alias` throws
   'Alias x already exists', surfaced only as 'Syntax error macroexpanding'.

   Fixtures live in a temp dir created per test and are loaded at runtime;
   holders outside the tracked dirs are made with create-ns + .addAlias."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-hot.core :as hot]
            [hive-hot.diagnostic :as diagnostic]))

(def ^:private target 'hive.hot.alias-trap.target)
(def ^:private holder 'hive.hot.alias-trap.holder)
(def ^:private manual 'hive.hot.alias-trap.manual)
(def ^:private broken 'hive.hot.alias-trap.broken)

(def ^:dynamic *dir* nil)

(defn- forget! [sym]
  (remove-ns sym)
  (dosync (alter @#'clojure.core/*loaded-libs* disj sym)))

(defn- src-file ^java.io.File [sym]
  (io/file *dir* (str (-> (str sym) (str/replace "-" "_") (str/replace "." "/")) ".clj")))

(defn- write! [sym content]
  (let [f (src-file sym)]
    (io/make-parents f)
    (Thread/sleep 20)
    (spit f content)
    (Thread/sleep 20)
    f))

(defn- load-fixture!
  "Load a fixture namespace the way the image loads a lib: the temp root is not
   on the classpath, so load-file it and mark it loaded."
  [sym]
  (load-file (str (src-file sym)))
  (dosync (alter @#'clojure.core/*loaded-libs* conj sym)))

(defn- target-src [v]
  (str "(ns " target ")\n(def value " v ")\n"))

(def ^:private holder-src
  (str "(ns " holder "\n  (:require [" target " :as tgt]))\n"
       "(defn read-value [] tgt/value)\n"))

(defn- with-temp-root [f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "hive-hot-alias-trap-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    (binding [*dir* dir]
      (try
        (hot/reset-all!)
        (f)
        (finally
          (hot/reset-all!)
          (run! forget! [manual holder broken target])
          (run! #(.delete ^java.io.File %) (reverse (file-seq dir))))))))

(use-fixtures :each with-temp-root)

(defn- alias-target [holder-sym alias-sym]
  (get (ns-aliases holder-sym) alias-sym))

(deftest a-runtime-holder-outside-the-reload-set-is-re-pointed
  (write! target (target-src 1))
  (hot/init! {:dirs [(str *dir*)]})
  (load-fixture! target)
  (let [old (find-ns target)
        h   (create-ns manual)]
    (.addAlias h 'tgt old)
    (write! target (target-src 2))
    (let [r (hot/reload!)]
      (is (:success r) (pr-str (dissoc r :exception)))
      (is (contains? (set (:loaded r)) target))
      (testing "the target was replaced by a new Namespace object"
        (is (not (identical? old (find-ns target)))))
      (testing "the holder's alias follows the new object, not the removed one"
        (is (identical? (find-ns target) (alias-target manual 'tgt))))
      (testing "the report says what was repaired"
        (is (= [{:holder (str manual) :alias "tgt" :target (str target)
                 :action :realiased}]
               (:aliases-repaired r)))))))

(deftest a-surviving-tracked-holder-no-longer-fails-the-pass
  (testing "a :no-unload holder is reloaded without being removed, so its ns
            form re-runs `alias` against the stale object: the trap itself"
    (write! target (target-src 1))
    (write! holder holder-src)
    (hot/init! {:dirs [(str *dir*)] :no-unload #{holder}})
    (load-fixture! target)
    (load-fixture! holder)
    (write! target (target-src 2))
    (let [r (hot/reload!)]
      (is (:success r) (str (:error r)))
      (is (nil? (:failed r)))
      (is (= #{target holder} (set (:loaded r))))
      (is (= 2 ((resolve (symbol (str holder) "read-value")))))
      (is (identical? (find-ns target) (alias-target holder 'tgt)))
      (is (= [{:holder (str holder) :alias "tgt" :target (str target)
               :action :reloaded}]
             (:aliases-repaired r))))))

(deftest a-pass-with-no-stale-alias-reports-none
  (write! target (target-src 1))
  (write! holder holder-src)
  (hot/init! {:dirs [(str *dir*)]})
  (load-fixture! target)
  (load-fixture! holder)
  (write! target (target-src 2))
  (let [r (hot/reload!)]
    (is (:success r) (str (:error r)))
    (is (not (contains? r :aliases-repaired)))))

(deftest a-load-failure-reports-its-root-cause
  (write! broken (str "(ns " broken ")\n\n"
                      "(def value (throw (ex-info \"innermost fixture failure\" {})))\n"))
  (hot/init! {:dirs [(str *dir*)]})
  (let [r    (hot/reload! {:only :all})
        root (:root-cause r)]
    (is (false? (:success r)))
    (is (= "innermost fixture failure" (:message root)))
    (is (= "clojure.lang.ExceptionInfo" (:class root)))
    (is (= (str broken) (:ns root)))
    (is (str/ends-with? (str (:file root)) "broken.clj") (pr-str root))
    (is (= 3 (:line root)))
    (testing "the summary strings carry the root, not only the wrapper"
      (is (str/includes? (:error r) "innermost fixture failure"))
      (is (str/includes? (:error r) "broken.clj:3"))
      (is (str/includes? (get-in r [:diagnostic :message]) "innermost fixture failure")))))

(deftest root-cause-walks-the-whole-cause-chain
  (let [inner   (IllegalStateException. "Alias host-catchup already exists")
        located (clojure.lang.Compiler$CompilerException. "hive/addon.clj" 1 1 inner)
        outer   (ex-info "Failed to load namespace: hive.addon" {:failed 'hive.addon} located)
        root    (diagnostic/root-cause outer)]
    (is (= "Alias host-catchup already exists" (:message root)))
    (is (= "java.lang.IllegalStateException" (:class root)))
    (is (= "hive/addon.clj" (:file root)))
    (is (= 1 (:line root)))
    (is (= 1 (:column root))))
  (is (nil? (diagnostic/root-cause nil)))
  (is (= {:message "plain" :class "java.lang.RuntimeException"}
         (diagnostic/root-cause (RuntimeException. "plain")))))
