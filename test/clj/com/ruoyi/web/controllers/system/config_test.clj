(ns com.ruoyi.web.controllers.system.config-test
  "参数设置控制器测试."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.web.controllers.system.config :as config]))


(def mock-config-service
  {:query-fn (fn [q p]
               (case q
                 :list-configs [{:config_id 1 :config_name "test"}]
                 :find-config-by-id {:config_id 1 :config_name "test"}
                 :create-config! [{:config_id 2}]
                 :update-config! nil
                 :delete-config! nil
                 []))})


(deftest test-list-configs
  (testing "查询参数列表"
    (let [request {:query-params {}}
          response (config/list-configs {:config-service mock-config-service} request)]
      (is (map? response))))
  (testing "字符串键查询条件传到 SQL 参数, 空值不过滤"
    (let [seen (atom nil)
          svc {:query-fn (fn [q p] (when (= q :list-configs) (reset! seen p)) [])}]
      (config/list-configs {:config-service svc} {:query-params {"config_name" "测试" "config_key" "" "config_type" "Y"}})
      (is (= {:config_name "测试" :config_key nil :config_type "Y"} @seen)))))


(deftest test-get-config
  (testing "获取参数详情"
    (let [request {:path-params {:id "1"}}
          response (config/get-config {:config-service mock-config-service} request)]
      (is (map? response)))))


(deftest test-create-config
  (testing "创建参数"
    (let [request {:body-params {:config_name "test" :config_key "test.key" :config_value "test" :config_type "Y"}}
          response (config/create-config {:config-service mock-config-service} request)]
      (is (map? response)))))


(deftest test-update-config
  (testing "更新参数"
    (let [request {:path-params {:id "1"} :body-params {:config_name "updated"}}
          response (config/update-config {:config-service mock-config-service} request)]
      (is (map? response)))))


(deftest test-delete-config
  (testing "删除参数"
    (let [request {:path-params {:id "1"}}
          response (config/delete-config {:config-service mock-config-service} request)]
      (is (map? response)))))
