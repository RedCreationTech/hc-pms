(ns com.ruoyi.web.middleware.params-test
  "入口参数解析中间件测试: 数字串转整数, 保留会失真的原样, 多部件不受影响."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.web.controllers.business.util :as bu]
    [com.ruoyi.web.middleware.params :as params]))


(defn- routed
  "构造带路由声明的请求; parse? 决定路由组是否声明了解析开关."
  [parse? query]
  {:reitit.core/match {:data {:params/parse? parse?}}
   :request-method :get
   :uri "/api/business/oa/leave"
   :query-params query
   :params query})


(defn- probe
  "穿过中间件, 返回处理函数实际看到的请求."
  [request]
  (let [seen (atom nil)]
    ((params/wrap-parse-params (fn [req] (reset! seen req) {:status 200}))
     request)
    @seen))


(deftest parse-params-value-test
  (testing "数字串转整数, 键转 keyword"
    (is (= {:page 2 :size 20}
           (:query-params (params/parse-params {:query-params {"page" "2" "size" "20"}})))))
  (testing "会失真的写法保持字符串原样"
    (is (= {:a "007" :b "1.0" :c "1e3" :d "" :e "-" :f "abc"}
           (:query-params (params/parse-params
                            {:query-params {"a" "007" "b" "1.0" "c" "1e3"
                                            "d" "" "e" "-" "f" "abc"}})))))
  (testing "负数照常转整数"
    (is (= {:page -2}
           (:query-params (params/parse-params {:query-params {"page" "-2"}})))))
  (testing ":params 用 keyword 键, multipart 部件保持不动"
    (let [part {:filename "a.csv" :tempfile (java.io.File. "/tmp/x") :size 3}
          req (params/parse-params {:query-params {"page" "3"}
                                    :params {"file" part "size" "9"}})]
      (is (= 3 (get-in req [:params :page])))
      (is (= part (get-in req [:params :file])))
      (is (= 9 (get-in req [:params :size])))))
  (testing "空请求不炸"
    (is (= {} (:query-params (params/parse-params {}))))))


(deftest wrap-parse-params-test
  (testing "路由声明了开关时解析"
    (let [seen (probe (routed true {"page" "4" "size" "15"}))]
      (is (= {:page 4 :size 15} (:query-params seen)))))
  (testing "路由未声明开关时保持原样"
    (let [seen (probe (routed nil {"page" "4"}))]
      (is (= {"page" "4"} (:query-params seen)))))
  (testing "已解析过的请求不会被二次解析而丢值"
    (let [once (probe (routed true {"page" "4"}))
          twice (probe (assoc once :reitit.core/match {:data {:params/parse? true}}))]
      (is (= {:page 4} (:query-params twice))))))


(deftest kquery-test
  (testing "入口已解析时 kquery 幂等"
    (is (= {:page 2 :user_id 7}
           (bu/kquery {:query-params {:page 2 :user_id 7}}))))
  (testing "控制器单测传 string 键时归一成 keyword 并转整数"
    (is (= {:page 2 :user_id 7}
           (bu/kquery {:query-params {"page" "2" "user_id" "7"}}))))
  (testing "数字串状态码被解析成整数, 文本参数保持原样"
    (is (= {:status 1 :name "张"}
           (bu/kquery {:query-params {"status" "1" "name" "张"}}))))
  (testing "表单字段与查询串合并, 同名时表单字段生效"
    (is (= {:page 3 :name "李"}
           (bu/kquery {:query-params {"page" "2"} :params {"page" "3" "name" "李"}}))))
  (testing "非法数字保持原样, 由领域层退化为默认值"
    (is (= {:page "abc"} (bu/kquery {:query-params {"page" "abc"}}))))
  (testing "无查询串返回空 map"
    (is (= {} (bu/kquery {})))))

(deftest parse-params-priority-test
  (testing "表单优先时同名参数取表单值"
    (is (= {:page 3}
           (:query-params (params/parse-params
                            {:query-params {"page" "2"} :params {"page" "3"}} :form)))))
  (testing "查询串优先时同名参数取查询串值"
    (is (= {:page 2}
           (:query-params (params/parse-params
                            {:query-params {"page" "2"} :params {"page" "3"}} :query)))))
  (testing "不同名参数两边都保留"
    (is (= {:page 2 :name "李"}
           (:query-params (params/parse-params
                            {:query-params {"page" "2"} :params {"name" "李"}} :query))))))
