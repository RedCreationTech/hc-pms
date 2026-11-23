(ns com.ruoyi.business.office-test
  "办公三模块 (HRM 员工 / CRM 客户 / OA 日程与会议) 领域层与控制器的单元测试.

   这三组命名空间在覆盖率报告中长期低于 30%, 原因是领域层只做参数归一后转发 HugSQL,
   此前没有任何测试直接调用它们. 本测试用内存版 query-fn 覆盖真实的边界行为:
   分页计算, 字符串参数的类型加固, 默认值填充, 操作人留痕, 以及控制器在
   记录不存在时返回 404 而不是 200 空体."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.domain.business.crm :as crm]
    [com.ruoyi.domain.business.hrm :as hrm]
    [com.ruoyi.domain.business.oa :as oa]
    [com.ruoyi.domain.business.paging :as paging]
    [com.ruoyi.domain.business.leave :as leave]
    [com.ruoyi.domain.business.reimburse :as reimburse]
    [com.ruoyi.domain.business.bpm-mgmt :as bpm-mgmt]
    [com.ruoyi.web.controllers.business.crm :as crm-web]
    [com.ruoyi.web.controllers.business.hrm :as hrm-web]
    [com.ruoyi.web.controllers.business.oa :as oa-web]))


(defn- recorder
  "返回 [svc calls], calls 按调用顺序记录 [query-key params]."
  [handler]
  (let [calls (atom [])]
    [{:query-fn (fn [k params]
                  (swap! calls conj [k params])
                  (handler k params))}
     calls]))


(defn- call-of
  "从调用记录中取指定 query-key 的第一次调用."
  [calls k]
  (first (filter #(= k (first %)) @calls)))


(defn- default-handler
  "默认响应: 列表返回带 total 的空页, 其余返回 {:ok true}."
  [query-key _]
  (if (re-find #"-(count)$" (name query-key))
    {:total 0}
    {:ok true}))


(def ^:private sample-employee
  {:employee_id 7 :name "张三" :dept_id 3 :status "1"})


;; ── HRM ────────────────────────────────────────────────────────────────

(deftest employee-list-pagination-test
  (testing "默认第 1 页每页 10 条, 偏移为 0"
    (let [[svc calls] (recorder (fn [k _] (if (= k :hrm/employee-count) {:total 42} [])))]
      (is (= {:rows [] :total 42} (hrm/employee-list svc {})))
      (is (= [[:hrm/employee-list {:name nil :dept_id nil :status nil
                                   :page_size 10 :offset 0}]
              [:hrm/employee-count {:name nil :dept_id nil :status nil
                                    :page_size 10 :offset 0}]]
             @calls))))
  (testing "第 3 页每页 20 条, 偏移为 40; 部门与状态按字符串转数字"
    (let [[svc calls] (recorder default-handler)]
      (hrm/employee-list svc {:page "3" :size "20" :dept_id "5" :status "1"})
      (is (= [:hrm/employee-list {:name nil :dept_id 5 :status "1"
                                  :page_size 20 :offset 40}]
             (first @calls)))))
  (testing "非法 page/size 回退到第 1 页每页 10 条, 不抛异常"
    (let [[svc calls] (recorder default-handler)]
      (is (map? (hrm/employee-list svc {:page "abc" :size "xyz"})))
      (is (= {:name nil :dept_id nil :status nil :page_size 10 :offset 0}
             (second (call-of calls :hrm/employee-list))))))
  (testing "分页参数为数字类型时同样可用"
    (let [[svc calls] (recorder default-handler)]
      (hrm/employee-list svc {:page 2 :size 5})
      (is (= 5 (:offset (second (call-of calls :hrm/employee-list)))))))
  (testing "非数字的 dept_id 被忽略而不是抛 NumberFormatException"
    (let [[svc calls] (recorder default-handler)]
      (is (map? (hrm/employee-list svc {:dept_id "全部" :size "10"})))
      (is (nil? (:dept_id (second (call-of calls :hrm/employee-list))))))))


(deftest employee-crud-test
  (testing "按 id 读取透传 employee_id"
    (let [[svc calls] (recorder (fn [_ p] (when (= 7 (:employee_id p)) sample-employee)))]
      (is (= sample-employee (hrm/employee-get svc 7)))
      (is (= [:hrm/find-employee-by-id {:employee_id 7}] (first @calls)))))
  (testing "缺失的字段在创建与更新时都填默认值, 并记录操作人"
    (let [[svc calls] (recorder default-handler)]
      (hrm/employee-create svc {:name "李四" :employee_id 9} "admin")
      (hrm/employee-update svc {:name "李四" :employee_id 9} "editor")
      (is (= {:emp_no "" :name "李四" :dept_id 0 :post_id 0 :gender "0"
              :phone "" :email "" :id_card "" :hire_date nil :status "1"
              :salary_base 0 :remark "" :create_by "admin"}
             (second (first @calls))))
      (is (= "editor" (:update_by (second (second @calls)))))
      (is (= 9 (:employee_id (second (second @calls)))))))
  (testing "调用方给出的字段优先于默认值"
    (let [[svc calls] (recorder default-handler)]
      (hrm/employee-create svc {:emp_no "E01" :status "0" :salary_base 8000 :dept_id 3} "admin")
      (is (= {:emp_no "E01" :status "0" :salary_base 8000 :dept_id 3}
             (select-keys (second (first @calls)) [:emp_no :status :salary_base :dept_id])))))
  (testing "删除按 id 透传"
    (let [[svc calls] (recorder default-handler)]
      (hrm/employee-delete svc 12)
      (is (= [:hrm/delete-employee {:employee_id 12}] (first @calls))))))


;; ── CRM ────────────────────────────────────────────────────────────────

(deftest customer-list-filters-test
  (testing "客户列表按名称/等级/负责人过滤, 负责人转数字"
    (let [[svc calls] (recorder (fn [k _] (if (= k :crm/customer-count) {:total 3} [])))]
      (is (= {:rows [] :total 3}
             (crm/customer-list svc {:name "红创" :level "2" :owner_id "88" :page "2" :size "5"})))
      (is (= [:crm/customer-list {:name "红创" :level "2" :owner_id 88
                                  :page_size 5 :offset 5}]
             (first @calls)))))
  (testing "无过滤条件时三项均为 nil"
    (let [[svc calls] (recorder default-handler)]
      (crm/customer-list svc {})
      (is (= {:name nil :level nil :owner_id nil :page_size 10 :offset 0}
             (second (first @calls))))))
  (testing "非数字 owner_id 被忽略而不是抛异常"
    (let [[svc calls] (recorder default-handler)]
      (is (map? (crm/customer-list svc {:owner_id "me"})))
      (is (nil? (:owner_id (second (call-of calls :crm/customer-list))))))))


(deftest customer-crud-test
  (let [[svc calls] (recorder default-handler)]
    (testing "创建时等级与状态默认 1, 归属人默认 0"
      (crm/customer-create svc {:name "某公司"} "admin")
      (is (= {:name "某公司" :phone "" :email "" :company "" :level "1"
              :source "" :owner_id 0 :status "1" :remark "" :create_by "admin"}
             (second (first @calls)))))
    (testing "更新带 customer_id 与 update_by"
      (crm/customer-update svc {:customer_id 4 :name "改名"} "editor")
      (is (= {:customer_id 4 :update_by "editor" :level "1"}
             (select-keys (second (second @calls)) [:customer_id :update_by :level]))))
    (testing "删除与读取"
      (crm/customer-delete svc 4)
      (is (= [:crm/delete-customer {:customer_id 4}] (last @calls))))))


;; ── OA ─────────────────────────────────────────────────────────────────

(deftest calendar-crud-test
  (let [[svc calls] (recorder default-handler)]
    (testing "日程列表非数字 user_id 被忽略"
      (is (nil? (:user_id (second (call-of calls :oa/calendar-list))))))
    (testing "日程列表分页偏移"
      (oa/calendar-list svc {:page "2" :size "15" :user_id "3"})
      (is (= {:user_id 3 :page_size 15 :offset 15}
             (second (call-of calls :oa/calendar-list)))))
    (testing "创建日程全天默认否, 颜色默认空, 记录创建人"
      (oa/calendar-create svc {:title "周会" :start_time "t1" :end_time "t2"} "admin")
      (is (= {:title "周会" :content "" :start_time "t1" :end_time "t2"
              :all_day "0" :color "" :user_id 0 :create_by "admin"}
             (second (call-of calls :oa/insert-calendar)))))
    (testing "更新日程保留 calendar_id"
      (oa/calendar-update svc {:calendar_id 5 :title "周会改"} "editor")
      (is (= {:calendar_id 5 :title "周会改" :all_day "0" :user_id 0
              :update_by "editor"}
             (select-keys (second (call-of calls :oa/update-calendar))
                          [:calendar_id :title :all_day :user_id :update_by]))))
    (testing "读取与删除"
      (oa/calendar-get svc 5)
      (oa/calendar-delete svc 5)
      (is (= [:oa/find-calendar-by-id {:calendar_id 5}] (call-of calls :oa/find-calendar-by-id)))
      (is (= [:oa/delete-calendar {:calendar_id 5}] (call-of calls :oa/delete-calendar))))))


(deftest meeting-crud-test
  (let [[svc calls] (recorder default-handler)]
    (testing "会议列表按主题过滤并分页"
      (oa/meeting-list svc {:subject "评审" :page "3" :size "10"})
      (is (= {:subject "评审" :page_size 10 :offset 20}
             (second (call-of calls :oa/meeting-list)))))
    (testing "创建会议状态默认 1"
      (oa/meeting-create svc {:subject "评审会" :participants "1,2"} "admin")
      (is (= {:subject "评审会" :location "" :participants "1,2" :content ""
              :status "1" :create_by "admin"}
             (dissoc (second (call-of calls :oa/insert-meeting)) :start_time :end_time))))
    (testing "更新会议带 meeting_id"
      (oa/meeting-update svc {:meeting_id 8 :subject "改期" :status "2"} "editor")
      (is (= {:meeting_id 8 :subject "改期" :status "2" :update_by "editor"}
             (select-keys (second (call-of calls :oa/update-meeting))
                          [:meeting_id :subject :status :update_by]))))
    (testing "读取与删除"
      (oa/meeting-get svc 8)
      (oa/meeting-delete svc 8)
      (is (= [:oa/find-meeting-by-id {:meeting_id 8}] (call-of calls :oa/find-meeting-by-id)))
      (is (= [:oa/delete-meeting {:meeting_id 8}] (call-of calls :oa/delete-meeting))))))


;; ── 控制器: 记录不存在时必须 404 而不是 200 空体 ─────────────────────────

(defn- body-of
  [resp]
  (let [b (:body resp)]
    (if (string? b) (read-string b) b)))


(deftest controller-missing-record-test
  (testing "HRM 员工不存在返回 404"
    (let [[svc _] (recorder (fn [_ _] nil))
          resp (hrm-web/get-employee {:hrm-service svc} {:path-params {:id "99"}})]
      (is (= 404 (:code (body-of resp))))
      (is (= "员工不存在" (:msg (body-of resp))))))
  (testing "CRM 客户不存在返回 404"
    (let [[svc _] (recorder (fn [_ _] nil))
          resp (crm-web/get-customer {:crm-service svc} {:path-params {:id "99"}})]
      (is (= 404 (:code (body-of resp))))))
  (testing "OA 日程不存在返回 404"
    (let [[svc _] (recorder (fn [_ _] nil))
          resp (oa-web/get-calendar {:oa-service svc} {:path-params {:id "99"}})]
      (is (= 404 (:code (body-of resp)))))))


(deftest controller-query-params-test
  (testing "控制器把字符串 query-params 转成关键字再交给领域层"
    (let [[svc calls] (recorder (fn [k _] (if (= k :hrm/employee-count) {:total 0} [])))]
      (hrm-web/list-employees {:hrm-service svc}
                              {:query-params {"page" "2" "size" "5" "name" "张"}})
      (is (= {:name "张" :dept_id nil :status nil :page_size 5 :offset 5}
             (second (first @calls))))))
  (testing "创建接口以登录身份写入 create_by"
    (let [[svc calls] (recorder default-handler)]
      (hrm-web/create-employee {:hrm-service svc}
                               {:body-params {:name "王五"}
                                :identity {:user-name "admin"}})
      (is (= "admin" (:create_by (second (first @calls)))))))
  (testing "更新接口把路径 id 覆盖进 body"
    (let [[svc calls] (recorder default-handler)]
      (hrm-web/update-employee {:hrm-service svc}
                               {:path-params {:id "42"}
                                :body-params {:name "王五"}
                                :identity {:user-name "editor"}})
      (is (= 42 (:employee_id (second (first @calls)))))))
  (testing "领域层抛错时控制器返回 500 与原始消息"
    (let [[svc _] (recorder (fn [_ _] (throw (ex-info "boom" {}))))
          resp (hrm-web/list-employees {:hrm-service svc} {:query-params {}})]
      (is (= 500 (:code (body-of resp)))))))

;; ── 共用分页归一 (domain.business.paging) ──────────────────────────────
(deftest paging-params-test
  (testing "缺失页码与每页数量退化为 1/10"
    (is (= {:page 1 :size 10 :offset 0} (paging/params {}))))
  (testing "字符串页码换算成偏移"
    (is (= {:page 3 :size 20 :offset 40} (paging/params {:page "3" :size "20"}))))
  (testing "数字类型同样可用"
    (is (= {:page 2 :size 5 :offset 5} (paging/params {:page 2 :size 5}))))
  (testing "非数字退化为默认值而不是抛异常"
    (is (= {:page 1 :size 10 :offset 0} (paging/params {:page "abc" :size "xyz"}))))
  (testing "零与负数页码按 1 处理, 保证 offset 不为负"
    (is (= {:page 1 :size 10 :offset 0} (paging/params {:page "0" :size "0"})))
    (is (= {:page 1 :size 10 :offset 0} (paging/params {:page "-2" :size "-5"})))))


(deftest paging-optional-id-test
  (testing "缺失返回 nil"
    (is (nil? (paging/optional-id nil))))
  (testing "字符串与数字都转成整数"
    (is (= 42 (paging/optional-id "42")))
    (is (= 42 (paging/optional-id 42))))
  (testing "非数字返回 nil 而不是抛异常"
    (is (nil? (paging/optional-id "abc")))
    (is (nil? (paging/optional-id "")))))


;; ── 请假 / 报销 / BPM 管理原本的非法分页参数崩溃 ──────────────────────
(deftest leave-reimburse-illegal-paging-test
  (testing "请假列表遇到非数字 page/size 不再抛 NumberFormatException"
    (let [[svc calls] (recorder default-handler)]
      (is (map? (leave/leave-list svc {:page "abc" :size "xyz"})))
      (is (= {:user_id nil :status nil :page_size 10 :offset 0}
             (second (call-of calls :oa/leave-list))))))
  (testing "请假列表正常分页与非数字 user_id"
    (let [[svc calls] (recorder default-handler)]
      (leave/leave-list svc {:page "2" :size "5" :user_id "7" :status "1"})
      (is (= {:user_id 7 :status "1" :page_size 5 :offset 5}
             (second (call-of calls :oa/leave-list)))))
    (let [[svc calls] (recorder default-handler)]
      (is (map? (leave/leave-list svc {:user_id "abc"})))
      (is (nil? (:user_id (second (call-of calls :oa/leave-list)))))))
  (testing "报销列表遇到非数字 page/size/user_id 不再抛异常"
    (let [[svc calls] (recorder default-handler)]
      (is (map? (reimburse/reimburse-list svc {:page "abc" :size "xyz" :user_id "abc"})))
      (is (= {:user_id nil :status nil :page_size 10 :offset 0}
             (second (call-of calls :oa/reimburse-list))))))
  (testing "报销列表正常分页"
    (let [[svc calls] (recorder default-handler)]
      (reimburse/reimburse-list svc {:page "3" :size "10" :user_id "9"})
      (is (= {:user_id 9 :status nil :page_size 10 :offset 20}
             (second (call-of calls :oa/reimburse-list)))))))


(deftest bpm-mgmt-illegal-paging-test
  (testing "BPM 管理列表遇到非数字分页参数时回退默认值"
    (let [[svc calls] (recorder default-handler)]
      (is (map? (bpm-mgmt/list-items svc :user-group {:page "abc" :size "xyz"})))
      (is (= {:name nil :type nil :page_size 10 :offset 0}
             (second (call-of calls :bpmmgmt/group-list))))))
  (testing "BPM 管理列表正常分页"
    (let [[svc calls] (recorder default-handler)]
      (bpm-mgmt/list-items svc :user-group {:page "4" :size "25" :name "运维"})
      (is (= {:name "运维" :type nil :page_size 25 :offset 75}
             (second (call-of calls :bpmmgmt/group-list)))))))
