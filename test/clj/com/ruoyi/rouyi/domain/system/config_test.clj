(ns com.ruoyi.rouyi.domain.system.config-test
  "参数设置领域服务测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.domain.system.config :as config]))

(def mock-configs
  [{:config_id 1 :config_name "主框架页-默认皮肤" :config_key "sys.index.skinName" :config_value "skin-blue" :config_type "Y"}
   {:config_id 2 :config_name "用户管理-账号初始密码" :config_key "sys.user.initPassword" :config_value "123456" :config_type "Y"}])

(defn- mock-query-fn [query-name params]
  (case query-name
    :list-configs mock-configs
    :find-config-by-id (first mock-configs)
    :find-config-by-key (first mock-configs)
    :create-config! [{:config_id 3}]
    :update-config! nil
    :delete-config! nil
    []))

(def mock-service {:query-fn mock-query-fn})

(deftest test-list-configs
  (testing "查询参数列表"
    (let [result (config/list-configs mock-service {})]
      (is (seq result))
      (is (= 2 (count result))))))

(deftest test-find-config-by-id
  (testing "根据ID查询参数"
    (let [result (config/find-config-by-id mock-service 1)]
      (is (some? result))
      (is (= "主框架页-默认皮肤" (:config_name result))))))

(deftest test-find-config-by-key
  (testing "根据Key查询参数"
    (let [result (config/find-config-by-key mock-service "sys.index.skinName")]
      (is (some? result))
      (is (= "skin-blue" (:config_value result))))))
