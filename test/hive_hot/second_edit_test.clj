(ns hive-hot.second-edit-test
  "HOT-MISSED-EDIT (kanban 20261006223724-388fa26b): edit, reload, edit AGAIN,
   reload — the second edit must be live. Measured on a host 2026-10-06: after
   `hot activate` loaded edit 1 of a namespace, edit 2 + `hot reload` answered
   :unchanged? true and kept edit 1.

   Each scenario drives the entry points the hot tool takes: a load outside
   hive-hot (what `hot activate` does to a dormant addon's namespace) and
   `reload-scoped!` over the addon's root (what `hot reload addon=...` does
   through hive-addon.hot/ns-reloader). The per-file baseline (`seen`) is
   advanced from the mtime clj-reload READ each file at, so no scenario here
   compares a second edit against a stale baseline.

   The one shape that does answer :unchanged? true for a real edit is pinned
   last: the edited file lies under a tracked root OTHER than the reload's
   (another checkout of the addon), and the scoped reload names it under
   :skipped rather than loading it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-hot.core :as hot]))

(def ^:private target 'hive.hot.second-edit.target)
(def ^:private other 'hive.hot.second-edit.other)

(def ^:dynamic *dir* nil)
(def ^:dynamic *other-dir* nil)

(defn- forget! [sym]
  (remove-ns sym)
  (dosync (alter @#'clojure.core/*loaded-libs* disj sym)))

(defn- src-file ^java.io.File [dir sym]
  (io/file dir (str (-> (str sym) (str/replace "-" "_") (str/replace "." "/")) ".clj")))

(defn- write! [dir sym content]
  (let [f (src-file dir sym)]
    (io/make-parents f)
    (Thread/sleep 20)
    (spit f content)
    (Thread/sleep 20)
    f))

(defn- edit! [v]
  (write! *dir* target (str "(ns " target ")\n(defn value [] " v ")\n")))

(defn- activate!
  "What `hot activate` does to a dormant namespace: load it outside hive-hot."
  []
  (load-file (str (src-file *dir* target)))
  (dosync (alter @#'clojure.core/*loaded-libs* conj target)))

(defn- value [] ((ns-resolve target 'value)))

(defn- temp-dir [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- with-temp-roots [f]
  (let [dir (temp-dir "hive-hot-second-edit-")
        od  (temp-dir "hive-hot-second-edit-other-")]
    (binding [*dir* dir *other-dir* od]
      (try
        (hot/reset-all!)
        (f)
        (finally
          (hot/reset-all!)
          (run! forget! [target other])
          (doseq [d [dir od]]
            (run! #(.delete ^java.io.File %) (reverse (file-seq d)))))))))

(use-fixtures :each with-temp-roots)

(defn- reload! []
  (hot/reload-scoped! [(str *dir*)]))

(defn- assert-live [r v]
  (is (:success r) (pr-str (dissoc r :exception)))
  (is (false? (:unchanged? r)) (str "the edit to " v " was not seen: " (pr-str (dissoc r :exception))))
  (is (= v (value))))

(deftest a-second-edit-after-a-reload-is-loaded
  (edit! :v0)
  (hot/init! {:dirs [(str *dir*)]})
  (activate!)
  (edit! :edit1)
  (assert-live (reload!) :edit1)
  (edit! :edit2)
  (assert-live (reload!) :edit2))

(deftest a-second-edit-after-activate-is-loaded
  (testing "dormant at init, edit 1 loaded by activate, edit 2 by reload"
    (edit! :v0)
    (hot/init! {:dirs [(str *dir*)]})
    (edit! :edit1)
    (activate!)
    (is (= :edit1 (value)))
    (edit! :edit2)
    (assert-live (reload!) :edit2)
    (edit! :edit3)
    (assert-live (reload!) :edit3)))

(deftest a-pass-while-dormant-does-not-hide-a-later-edit
  (testing "an unrelated pass runs while the namespace is dormant"
    (edit! :v0)
    (write! *other-dir* other (str "(ns " other ")\n(def x 0)\n"))
    (hot/init! {:dirs [(str *dir*) (str *other-dir*)]})
    (edit! :edit1)
    (write! *other-dir* other (str "(ns " other ")\n(def x 1)\n"))
    (hot/reload-scoped! [(str *other-dir*)])
    (hot/reload!)
    (activate!)
    (is (= :edit1 (value)))
    (edit! :edit2)
    (assert-live (reload!) :edit2)))

(deftest a-root-claimed-after-the-first-edit-still-sees-the-second
  (testing "the addon root is claimed (extend-init!) when activate runs"
    (write! *other-dir* other (str "(ns " other ")\n(def x 0)\n"))
    (edit! :v0)
    (hot/ensure-init! {:dirs [(str *other-dir*)]})
    (edit! :edit1)
    (hot/extend-init! {:dirs [(str *dir*)] :owner :addon})
    (activate!)
    (is (= :edit1 (value)))
    (edit! :edit2)
    (assert-live (reload!) :edit2)
    (edit! :edit3)
    (assert-live (reload!) :edit3)))

(deftest an-edit-loaded-outside-hive-hot-after-a-reload-does-not-mask-the-next
  (testing "reload loads edit 1, something loads edit 2 directly, edit 3 reloads"
    (edit! :v0)
    (hot/init! {:dirs [(str *dir*)]})
    (activate!)
    (edit! :edit1)
    (assert-live (reload!) :edit1)
    (edit! :edit2)
    (activate!)
    (edit! :edit3)
    (assert-live (reload!) :edit3)))

(deftest an-edit-under-another-tracked-root-is-skipped-not-lost
  (testing "a scoped reload of root B answers :unchanged? for an edit under
            root A, names it under :skipped, and a plain reload! loads it"
    (edit! :v0)
    (hot/init! {:dirs [(str *dir*) (str *other-dir*)]})
    (activate!)
    (edit! :edit1)
    (let [r (hot/reload-scoped! [(str *other-dir*)])]
      (is (:success r) (pr-str (dissoc r :exception)))
      (is (true? (:unchanged? r)))
      (is (= [(str target)] (:skipped r)))
      (is (= :v0 (value))))
    (let [r (hot/reload!)]
      (is (:success r) (pr-str (dissoc r :exception)))
      (is (false? (:unchanged? r)))
      (is (= :edit1 (value))))))
