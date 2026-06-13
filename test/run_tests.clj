(ns run-tests
  (:require [clojure.test :refer [run-tests]]))

(defn run-all-tests [& args]
  (println "")
  (println "=== RuoYi Clojure Unit Tests ===")
  (println "")

  ;; Load test files
  (load-file "test/clj/com/ruoyi/domain/gen_test.clj")
  (load-file "test/clj/com/ruoyi/domain/system/config_test.clj")
  (load-file "test/clj/com/ruoyi/domain/system/dept_test.clj")
  (load-file "test/clj/com/ruoyi/domain/system/dict_test.clj")
  (load-file "test/clj/com/ruoyi/domain/system/log_test.clj")
  (load-file "test/clj/com/ruoyi/domain/system/menu_test.clj")
  (load-file "test/clj/com/ruoyi/domain/system/post_test.clj")
  (load-file "test/clj/com/ruoyi/domain/system/role_test.clj")
  (load-file "test/clj/com/ruoyi/domain/system/user_test.clj")
  (load-file "test/clj/com/ruoyi/infra/cache_test.clj")
  (load-file "test/clj/com/ruoyi/infra/db_test.clj")
  (load-file "test/clj/com/ruoyi/infra/security_test.clj")

  ;; Run tests
  (let [result (run-tests
                'com.ruoyi.domain.gen-test
                'com.ruoyi.domain.system.config-test
                'com.ruoyi.domain.system.dept-test
                'com.ruoyi.domain.system.dict-test
                'com.ruoyi.domain.system.log-test
                'com.ruoyi.domain.system.menu-test
                'com.ruoyi.domain.system.post-test
                'com.ruoyi.domain.system.role-test
                'com.ruoyi.domain.system.user-test
                'com.ruoyi.infra.cache-test
                'com.ruoyi.infra.db-test
                'com.ruoyi.infra.security-test)]
    (println "")
    (println "=== Test Summary ===")
    (println (str "Namespaces: " (:test result)))
    (println (str "Assertions: " (+ (:pass result) (:fail result) (:error result))))
    (println (str "Pass: " (:pass result)))
    (println (str "Fail: " (:fail result)))
    (println (str "Error: " (:error result)))
    (println "====================")
    (when (and (zero? (:fail result)) (zero? (:error result)))
      (println "All tests passed!")))

  (shutdown-agents))
