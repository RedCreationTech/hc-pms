(ns com.ruoyi.pms-codec-test
  "com.ruoyi.domain.pms.integration.codec 的表征测试: 锁定系统/实体白名单, system! 校验, encode 的稳定键序序列化, payload! 的结构与非空/大小门控及 payload_hash 自洽, dto 运维投影脱敏. 作为 ub-mode 后续重构外部接口编解码路径的行为保持门."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.domain.pms.finance-money :as money]
    [com.ruoyi.domain.pms.integration.codec :as codec]))

(defn- err-data [f]
  (try (f) :no-throw (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest system-and-entity-vocabularies-are-fixed
  (is (= #{"crm" "oa" "erp" "plm" "mes" "srm" "sales_material" "bi"} codec/systems))
  (is (= #{"order" "material" "field_progress" "cost"} codec/entities))
  (is (codec/systems "erp"))
  (is (not (codec/systems "bogus"))))

(deftest system!-accepts-known-rejects-unknown
  (is (= "crm" (codec/system! "crm")))
  (is (= "bi" (codec/system! "bi")))
  (testing "非白名单系统 400"
    (is (= 400 (:status (err-data #(codec/system! "bogus")))))
    (is (= 400 (:status (err-data #(codec/system! nil)))))))

(deftest encode-is-key-order-independent-and-deterministic
  (is (= (codec/encode {:a 1 :b 2}) (codec/encode {:b 2 :a 1})))
  (is (= "{\"a\":1,\"b\":2}" (codec/encode {:b 2 :a 1})))
  (testing "嵌套 map 的键同样按字符串升序稳定输出"
    (is (= (codec/encode {:z 1 :a {:y 2 :b 3}})
           (codec/encode {:a {:b 3 :y 2} :z 1})))
    (is (= "{\"a\":{\"b\":3,\"y\":2},\"z\":1}" (codec/encode {:z 1 :a {:y 2 :b 3}})))))

(deftest payload!-validates-encodes-and-self-hashes
  (let [{:keys [payload_json payload_hash]} (codec/payload! {:k "v" :n 2})]
    (is (= (codec/encode {:k "v" :n 2}) payload_json))
    (is (= (money/digest payload_json) payload_hash))
    (is (= 64 (count payload_hash)))
    (is (= {:k "v" :n 2} (json/parse-string payload_json true)))))

(deftest payload!-rejects-empty-non-map-and-oversize
  (is (= 400 (:status (err-data #(codec/payload! {})))))
  (is (= 400 (:status (err-data #(codec/payload! "x")))))
  (is (= 400 (:status (err-data #(codec/payload! [1 2 3])))))
  (testing "超过 64KiB 拒绝"
    (is (= 400 (:status (err-data #(codec/payload! {:big (apply str (repeat 70000 "x"))})))))))

(deftest dto-projects-ops-view-without-leaking-payload
  (is (nil? (codec/dto nil)))
  (is (= {:message_id 9 :x 1 :id 9} (codec/dto {:message_id 9 :payload_json "secret" :lease_id "L" :x 1})))
  (is (= {:fact_id 7 :id 7} (codec/dto {:fact_id 7 :payload_json "secret"})))
  (testing "投影后不含 payload_json / lease_id"
    (let [d (codec/dto {:message_id 1 :payload_json "p" :lease_id "L" :extra :e})]
      (is (not (contains? d :payload_json)))
      (is (not (contains? d :lease_id)))
      (is (= :e (:extra d))))))
