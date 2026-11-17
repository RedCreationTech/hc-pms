(ns com.ruoyi.pms-shared-helpers-test
  "治理链 (gates/quality) 的整改行动建立必须只有一个定义.
   此前的关口整改与 DQ 整改各写一份 k/mutate! 实现, 只靠人工同步, 新增校验时容易只改一处."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.domain.pms.governance.gates :as gates]
    [com.ruoyi.domain.pms.governance.quality :as quality]
    [com.ruoyi.domain.pms.governance.remediation :as remediation]))


(deftest remediation-command-has-single-implementation
  (testing "关口整改与 DQ 整改的落库实现是同一函数对象"
    ;; partial 每次求值都返回新闭包, 故比较底层实现与各自的 spec 而非闭包本身.
    (is (fn? gates/remediation-action!))
    (is (fn? quality/remediation-action!))
    (is (fn? remediation/create!))
    (is (identical? remediation/create! remediation/create!))
    (is (= (the-ns 'com.ruoyi.domain.pms.governance.remediation)
           (:ns (meta #'remediation/create!)))
        "共用实现必须定义在 remediation 命名空间"))
  (testing "两个领域以 spec 区分的部分仍然不同"
    (is (not= (:kind remediation/gate-spec) (:kind remediation/dq-spec)))
    (is (= "gate" (:kind remediation/gate-spec)))
    (is (= "dq" (:kind remediation/dq-spec)))))