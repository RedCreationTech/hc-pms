(ns com.ruoyi.rouyi.web.controllers.common-test
  "通用控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.common :as common]))

(deftest test-download
  (testing "下载文件"
    (let [request {:query-params {:fileName "test.txt"}}
          response (common/download {} request)]
      (is (map? response)))))
