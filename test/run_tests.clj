(ns run-tests
  (:require [clojure.test :refer [run-tests]]))

(defn run-all-tests [& args]
  (println "")
  (println "=== RuoYi Clojure Unit Tests ===")
  (println "")
  
  ;; Load test files
  (load-file "test/clj/com/ruoyi/rouyi/domain/gen_test.clj")
  (load-file "test/clj/com/ruoyi/rouyi/domain/system/config_test.clj")
  (load-file "test/clj/com/ruoyi/rouyi/domain/system/dept_test.clj")
  (load-file "test/clj/com/ruoyi/rouyi/domain/system/dict_test.clj")
  (load-file "test/clj/com/ruoyi/rouyi/domain/system/log_test.clj")
  (load-file "test/clj/com/ruoyi/rouyi/domain/system/menu_test.clj")
  (load-file "test/clj/com/ruoyi/rouyi/domain/system/post_test.clj")
  (load-file "test/clj/com/ruoyi/rouyi/domain/system/role_test.clj")
  (load-file "test/clj/com/ruoyi/rouyi/domain/system/user_test.clj")
  (load-file "test/clj/com/ruoyi/rouyi/infra/cache_test.clj")
  (load-file "test/clj/com/ruoyi/rouyi/infra/db_test.clj")
  (load-file "test/clj/com/ruoyi/rouyi/infra/security_test.clj")
  
  ;; Run tests
  (let [result (run-tests
                'com.ruoyi.rouyi.domain.gen-test
                'com.ruoyi.rouyi.domain.system.config-test
                'com.ruoyi.rouyi.domain.system.dept-test
                'com.ruoyi.rouyi.domain.system.dict-test
                'com.ruoyi.rouyi.domain.system.log-test
                'com.ruoyi.rouyi.domain.system.menu-test
                'com.ruoyi.rouyi.domain.system.post-test
                'com.ruoyi.rouyi.domain.system.role-test
                'com.ruoyi.rouyi.domain.system.user-test
                'com.ruoyi.rouyi.infra.cache-test
                'com.ruoyi.rouyi.infra.db-test
                'com.ruoyi.rouyi.infra.security-test)]
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
