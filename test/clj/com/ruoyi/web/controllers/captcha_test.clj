(ns com.ruoyi.web.controllers.captcha-test
  "验证码控制器测试."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.web.controllers.captcha :as captcha]))


(use-fixtures :each
  (fn [test]
    (reset! captcha/captcha-store {})
    (test)
    (reset! captcha/captcha-store {})))


(deftest test-captcha-image
  "生成验证码图片."
  (testing "无 r 参数时返回图片与 UUID"
    (let [request {:query-params {}}
          response (captcha/captcha-image {} request)]
      (is (= 200 (:status response)))
      (is (= "image/png" (get-in response [:headers "Content-Type"])))
      (is (string? (get-in response [:headers "Captcha-UUID"])))
      (is (pos? (count (:body response))))
      (let [uuid (get-in response [:headers "Captcha-UUID"])
            stored (get @captcha/captcha-store uuid)]
        (is (map? stored))
        (is (= 4 (count (:code stored))))
        (is (> (:expire stored) (System/currentTimeMillis)))))))


(deftest test-captcha-image-with-r
  "使用指定 r 参数生成验证码."
  (testing "r 参数作为 UUID 并写入验证码缓存"
    (let [request {:query-params {"r" "abc123"}}
          response (captcha/captcha-image {} request)]
      (is (= 200 (:status response)))
      (is (= "abc123" (get-in response [:headers "Captcha-UUID"])))
      (is (pos? (count (:body response))))
      (is (contains? @captcha/captcha-store "abc123"))
      (let [stored (get @captcha/captcha-store "abc123")]
        (is (= 4 (count (:code stored))))
        (is (> (:expire stored) (System/currentTimeMillis)))))))
