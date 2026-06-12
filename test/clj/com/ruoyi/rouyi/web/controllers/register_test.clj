(ns com.ruoyi.rouyi.web.controllers.register-test
  "注册控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.register :as register]))

(deftest test-register
  (testing "用户注册"
    (let [request {:body-params {:username "newuser" :password "123456" :code "1234"}}
          response (register/register {} request)]
      (is (map? response)))))
