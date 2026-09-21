(ns com.ruoyi.web.routes.utils-test
  "路由工具函数测试."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.web.routes.utils :as route-utils]))


(def sample-request
  {:reitit.core/match {:data {:handler :my-handler
                              :middleware [:auth]
                              :roles #{:admin}}}})


(deftest test-route-data
  (testing "提取路由数据"
    (is (= {:handler :my-handler :middleware [:auth] :roles #{:admin}}
           (route-utils/route-data sample-request)))
    (is (nil? (route-utils/route-data {})))
    (is (nil? (route-utils/route-data nil)))))


(deftest test-route-data-key
  (testing "提取指定键的路由数据"
    (is (= :my-handler (route-utils/route-data-key sample-request :handler)))
    (is (= [:auth] (route-utils/route-data-key sample-request :middleware)))
    (is (= #{:admin} (route-utils/route-data-key sample-request :roles)))
    (is (nil? (route-utils/route-data-key sample-request :not-found)))
    (is (nil? (route-utils/route-data-key {} :handler)))
    (is (nil? (route-utils/route-data-key nil :handler)))))
