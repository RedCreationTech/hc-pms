(ns com.ruoyi.business.json-field-test
  "业务表 JSON 列解析与按路径取值的单元测试(叶子逻辑,无引擎/数据库依赖)."
  (:require
    [clojure.test :refer [deftest testing is]]
    [com.ruoyi.domain.business.json-field :as jf]))


(deftest parse-field-test
  (testing "nil / 空串"
    (is (nil? (jf/parse-field nil)))
    (is (nil? (jf/parse-field ""))))
  (testing "JSON 文本解析成关键字 key 的数据"
    (is (= {:a 1} (jf/parse-field "{\"a\":1}")))
    (is (= [1 2] (jf/parse-field "[1,2]"))))
  (testing "非法 JSON 返回 nil"
    (is (nil? (jf/parse-field "{oops"))))
  (testing "已是数据则原样返回"
    (let [m {:a 1}]
      (is (identical? m (jf/parse-field m))))
    (is (= [1] (jf/parse-field [1])))))


(deftest parse-columns-test
  (testing "只解析指定列,其余列原样保留"
    (is (= {:form_json {:x 1} :name "n"}
           (jf/parse-columns {:form_json "{\"x\":1}" :name "n"} [:form_json]))))
  (testing "缺失的列不新增 key,值为 nil 的列保持 nil"
    (is (= {:id 1} (jf/parse-columns {:id 1} [:form_json])))
    (is (= {:id 1 :form_json nil} (jf/parse-columns {:id 1 :form_json nil} [:form_json]))))
  (testing "非法 JSON 保留原文,不抛异常"
    (is (= {:form_json "{oops"} (jf/parse-columns {:form_json "{oops"} [:form_json])))))


(deftest get-path-test
  (testing "关键字 map 逐层下钻"
    (is (= 3 (jf/get-path {:data {:level 3}} "data.level"))))
  (testing "字符串 key 的 map 同样可取"
    (is (= 3 (jf/get-path {"data" {"level" 3}} "data.level"))))
  (testing "序号下标取数组元素"
    (is (= "b" (jf/get-path {:list [{:name "a"} {:name "b"}]} "list.1.name"))))
  (testing "越界 / 非数字下标 / 不存在路径返回 nil"
    (is (nil? (jf/get-path {:list [1]} "list.5")))
    (is (nil? (jf/get-path {:list [1]} "list.x")))
    (is (nil? (jf/get-path {:data 1} "data.level")))
    (is (nil? (jf/get-path {} "missing.path"))))
  (testing "非集合中间值后不再下钻"
    (is (nil? (jf/get-path {:a "str"} "a.b")))))