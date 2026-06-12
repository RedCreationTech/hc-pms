(ns com.ruoyi.rouyi.web.controllers.captcha-test
  "验证码控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.captcha :as captcha]))

(deftest test-captcha-image
  (testing "获取验证码图片"
    (let [request {:query-params {:uuid "test-uuid"}}
          response (captcha/captcha-image {} request)]
      (is (map? response)))))
