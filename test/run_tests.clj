(ns run-tests
  (:require [clojure.test :refer [run-tests]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- file->ns-symbol [root file]
  (let [rel (-> (.getPath file)
                (str/replace (re-pattern (str "^" (str/replace root #"\\" "\\\\") "/")) ""))
        base (str/replace rel #"_test\.clj$" "")
        parts (str/split base #"/")
        parts (update parts (dec (count parts)) #(str/replace % #"_" "-"))]
    (symbol (str/join "." parts))))

(defn run-all-tests [& _args]
  (println "")
  (println "=== RuoYi Clojure Unit Tests ===")
  (println "")

  (let [root "test/clj"
        files (->> (file-seq (io/file root))
                   (filter #(str/ends-with? (.getName %) "_test.clj"))
                   (sort-by #(.getPath %)))
        namespaces (mapv #(file->ns-symbol root %) files)]
    (doseq [f files]
      (load-file (.getPath f)))

    (let [result (apply run-tests namespaces)]
      (println "")
      (println "=== Test Summary ===")
      (println (str "Namespaces: " (:test result)))
      (println (str "Assertions: " (+ (:pass result) (:fail result) (:error result))))
      (println (str "Pass: " (:pass result)))
      (println (str "Fail: " (:fail result)))
      (println (str "Error: " (:error result)))
      (println "====================")
      (when (and (zero? (:fail result)) (zero? (:error result)))
        (println "All tests passed!"))))

  (shutdown-agents))
