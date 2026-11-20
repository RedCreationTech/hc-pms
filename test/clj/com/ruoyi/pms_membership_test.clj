(ns com.ruoyi.pms-membership-test
  "com.ruoyi.domain.pms.membership 的表征测试: 锁定撤销成员前的跨业务责任阻塞清单 (治理/交付/计划/成本/工时/关闭/重开/移交) 与 409 门控, 作为重构行为保持门."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.domain.pms.membership :as membership]
    [com.ruoyi.domain.pms.rules :as rules]))


(defn- fake-removal-q
  "按查询名返回罐头数据的桩查询, 供 removal-ready! 纯表征测试 (不查库).
   :gov/list 与 :delivery/list 按 kind 取行, 覆盖两个跨域 blockers 的内部查询."
  [{:keys [tasks costs times approval items reopen gov delivery]}]
  (fn [name params]
    (case name
      :planning/tasks (or tasks [])
      :finance/versions (or costs [])
      :finance/times (or times [])
      :closure/approval approval
      :closure/items (or items [])
      :reopen/latest reopen
      :gov/list (get (or gov {}) (:kind params) [])
      :delivery/list (get (or delivery {}) (:kind params) [])
      nil)))


(defn- ready
  "在给定罐头数据下求撤销结果: 通过返回 true, 被阻塞返回 [409 消息]."
  [stub]
  (try (membership/removal-ready! (fake-removal-q stub) {:project_id 1} 9501)
       (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))


;; removal-ready! 重构前的行为钉: 全部八类阻塞消息与放行分支, 纯桩不依赖数据库.
(deftest removal-blockers-cover-every-responsibility
  (testing "无未交接责任时放行"
    (is (true? (ready {})))
    (is (true? (ready {:tasks [{:owner_id 9501 :status "done"}]
                       :costs [{:reviewer_id 9501 :status "approved"}]
                       :approval {:reviewer_id 9501 :status "approved"}
                       :delivery {"material" [{:owner_id 9501 :status "approved"}]}}))))
  (testing "治理域: 待办审核人与未验证关闭问题的负责人"
    (is (= [409 "该成员仍是待办审核或未完成Gate的指定审核人"]
           (ready {:gov {"issue" [{:reviewer_id 9501 :status "in_review"}]}})))
    (is (= [409 "该成员仍是待办审核或未完成Gate的指定审核人"]
           (ready {:gov {"gate" [{:reviewer_id 9501 :status "draft" :kind "gate"}]}})))
    (is (= [409 "该成员仍负责未验证关闭的问题"]
           (ready {:gov {"issue" [{:owner_id 9501 :status "open"}]}}))))
  (testing "交付域: 未完成记录的负责人与指定审批人"
    (is (= [409 "该成员仍负责未完成的交付执行记录"]
           (ready {:delivery {"material" [{:owner_id 9501 :status "draft"}]}})))
    (is (= [409 "该成员仍是未完成交付执行记录的指定审批人"]
           (ready {:delivery {"bom" [{:reviewer_id 9501 :status "in_review"}]}})))
    (is (true? (ready {:delivery {"shipment" [{:owner_id 9501 :status "received"}
                                              {:reviewer_id 9501 :status "closed"}]}}))))
  (testing "计划与财务: 未完成任务, 成本版本审批人, 待审工时"
    (is (= [409 "该成员仍负责未完成计划任务,请先交接"]
           (ready {:tasks [{:owner_id 9501 :status "in_progress"}]})))
    (is (= [409 "该成员仍是未完成成本版本的审批人"]
           (ready {:costs [{:reviewer_id 9501 :status "submitted"}]})))
    (is (= [409 "该成员仍有待审核工时"]
           (ready {:times [{:reviewer_id 9501 :status "submitted"}]}))))
  (testing "收尾域: 关闭审批人, 待审重开指定审批人, 未确认移交事项"
    (is (= [409 "该成员仍是关闭审批人"]
           (ready {:approval {:reviewer_id 9501 :status "submitted"}})))
    (is (= [409 "该成员仍是待审重开申请的指定审批人"]
           (ready {:reopen {:reviewer_id 9501 :status "submitted"}})))
    (is (= [409 "该成员仍有未确认移交事项"]
           (ready {:items [{:owner_id 9501 :status "open"}]}))))
  (testing "多类阻塞按 跨域 -> 计划财务 -> 收尾 顺序拼接, 取第一条"
    (is (= [409 "该成员仍是待办审核或未完成Gate的指定审核人"]
           (ready {:gov {"issue" [{:reviewer_id 9501 :status "in_review"}]}
                   :tasks [{:owner_id 9501 :status "in_progress"}]
                   :items [{:owner_id 9501 :status "open"}]})))))


;; H17 精细职责分离: 委派到期后成员行即使仍在, 也不得再授予任何读取或编辑范围.
(deftest expired-member-loses-access-scope
  (let [project {:project_id 1 :manager_id 9500}
        ;; :long-term 表示无到期日的长期成员, :revoked 表示成员行已不存在.
        query-fn (fn [ends-on]
                   (fn [name _params]
                     (case name
                       :pms/member (case ends-on
                                     :revoked nil
                                     :long-term {:user_id 9501 :role "editor" :ends_on nil}
                                     {:user_id 9501 :role "editor" :ends_on ends-on})
                       :approval/participant nil
                       nil)))
        actor {:user_id 9501}
        today (java.time.LocalDate/parse "2026-11-20")
        active? (fn [ends-on]
                  (some? (rules/active-member (query-fn ends-on) actor project today)))
        scope (fn [ends-on write?]
                (try (rules/access! (query-fn ends-on) actor project write?) :allowed
                     (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))]
    (testing "到期日为空即长期有效, 到期日在今天及以后仍授予读写范围"
      (is (active? :long-term))
      (is (active? "2026-11-20"))
      (is (active? "2026-12-31"))
      (is (= :allowed (scope :long-term true)))
      (is (= :allowed (scope "2026-12-31" false))))
    (testing "委派已过期则立即失效, 读写都 403"
      (is (not (active? "2026-11-19")))
      (is (= 403 (scope "2026-11-19" false)))
      (is (= 403 (scope "2026-11-19" true))))
    (testing "撤销后成员行不存在, 读写都 403"
      (is (= 403 (scope :revoked true))))
    (testing "项目经理与管理员不受成员委派到期影响"
      (is (= :allowed (try (rules/access! (fn [_ _] nil) {:user_id 9500} project true) :allowed
                           (catch clojure.lang.ExceptionInfo e (:status (ex-data e))))))
      (is (= :allowed (try (rules/access! (query-fn "2026-11-19") {:user_id 9502 :admin? true} project true)
                           :allowed
                           (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))))))
