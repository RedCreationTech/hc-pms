(ns build
  (:require [clojure.string :as string]
            [clojure.tools.build.api :as b]
            [clojure.java.io :as io]))

(def lib 'com.ruoyi/rouyi)
(def main-cls "com.ruoyi.core")
(def version (format "0.0.1-SNAPSHOT"))
(def target-dir "target")
(def class-dir (str target-dir "/" "classes"))
(def uber-file (format "%s/%s-standalone.jar" target-dir (name lib)))
(def basis (b/create-basis {:project "deps.edn"}))

(defn clean
  "Delete the build target directory"
  [_]
  (println (str "Cleaning " target-dir))
  (b/delete {:path target-dir}))

(defn prep [_]
  (println "Writing Pom...")
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src/clj"]})
  (b/copy-dir {:src-dirs ["src/clj" "resources" "env/prod/resources" "env/prod/clj"]
               :target-dir class-dir}))

(defn uber [_]
  (println "Compiling Clojure...")
  (b/compile-clj {:basis basis
                  :src-dirs ["src/clj" "resources" "env/prod/resources" "env/prod/clj"]
                  :class-dir class-dir})
  (println "Making uberjar...")
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :main main-cls
           :basis basis}))

(defn all [_]
  (do (clean nil) (prep nil) (uber nil)))

;; ─── Testing ──────────────────────────────────────────────────────

(defn test
  "Run unit tests"
  [_]
  (println "Running unit tests...")
  (b/process {:command-args ["bash" "run_tests.sh"]
              :dir "."}))

(defn coverage
  "Run tests with coverage"
  [_]
  (println "Running tests with coverage...")
  (let [coverage-dir "target/coverage"]
    (.mkdirs (io/file coverage-dir))
    (b/process {:command-args ["clojure" "-A:test" "-e"
                               (str "(require '[cloverage.coverage :as cov])"
                                    "(cov/run-main {"
                                    "  :src-ns-path [\"src/clj\"]"
                                    "  :test-ns-path [\"test/clj\"]"
                                    "  :ns-exclude-regex [\".*core.*|.*request-test.*\"]"
                                    "  :output \"" coverage-dir "\""
                                    "  :html true"
                                    "  :emma-xml true"
                                    "  :text true"
                                    "})")]
                :dir "."})
    (println (str "Coverage report: " coverage-dir "/index.html"))))
