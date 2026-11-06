(ns com.ruoyi.pms-rules-test
  "com.ruoyi.domain.pms.rules 纯校验/授权原语的表征测试: 锁定 positive-id!, text!, date!, object!, page-params!, version!, changed!, editable!, permit!, access-params, actor, access! 与 project-input! 的返回值形状, 边界判定与 ex-data :status 码, 作为 ub-mode 后续重构 rules.clj 的行为保持门."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.domain.pms.rules :as r]))

(defn- err-data
  "调用 f, 若抛出携带 ex-data 的业务异常则返回其 ex-data, 否则返回 :no-throw."
  [f]
  (try
    (f)
    :no-throw
    (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- fake-query
  "按 :pms/user / :pms/user-roles / :pms/user-perms / :pms/member / :approval/participant / :pms/dept 返回固定桩数据的查询函数, 覆盖 rules 中所有查询分支."
  [{:keys [user roles perms member participant dept] :as _stub}]
  (fn [name _params]
    (case name
      :pms/user user
      :pms/user-roles roles
      :pms/user-perms perms
      :pms/member member
      :approval/participant participant
      :pms/dept dept
      nil)))

(deftest positive-id!-accepts-int-and-numeric-string-rejects-rest
  (is (= 5 (r/positive-id! 5 "x")))
  (is (= 42 (r/positive-id! "42" "x")))
  (testing "非正, 非数字串, nil 一律 400"
    (is (= 400 (:status (err-data #(r/positive-id! 0 "x")))))
    (is (= 400 (:status (err-data #(r/positive-id! -1 "x")))))
    (is (= 400 (:status (err-data #(r/positive-id! "abc" "x")))))
    (is (= 400 (:status (err-data #(r/positive-id! nil "x")))))
    (is (= 400 (:status (err-data #(r/positive-id! 1.5 "x")))))))

(deftest text!-trims-enforces-length-and-required
  (is (= "hi" (r/text! "  hi  " "名称" 10 false)))
  (is (= "" (r/text! "   " "名称" 10 false)))
  (is (= "ok" (r/text! "ok" "名称" 5 false)))
  (testing "超限, 必填空, 非文本"
    (is (= 400 (:status (err-data #(r/text! "abcdefghij" "名称" 5 false)))))
    (is (= 400 (:status (err-data #(r/text! nil "名称" 10 true)))))
    (is (= 400 (:status (err-data #(r/text! 123 "名称" 10 false)))))))

(deftest date!-normalizes-empty-validates-iso-calendar-date
  (is (nil? (r/date! nil "日期")))
  (is (nil? (r/date! "" "日期")))
  (is (= "2026-01-02" (r/date! "2026-01-02" "日期")))
  (testing "格式与真实日期双重校验"
    (is (= 400 (:status (err-data #(r/date! "2026-1-2" "日期")))))      ; 非零填充正则不匹配
    (is (= 400 (:status (err-data #(r/date! "2026-13-01" "日期")))))    ; 正则过但 LocalDate/parse 抛
    (is (= 400 (:status (err-data #(r/date! "not-a-date" "日期")))))))

(deftest object!-requires-map-and-disallows-unknown-fields
  (is (= {:name "a"} (r/object! {:name "a"} [:name])))
  (is (= {} (r/object! {} [:name])))
  (testing "非对象与越权字段"
    (is (= 400 (:status (err-data #(r/object! "str" [:name])))))
    (is (= 400 (:status (err-data #(r/object! {:name "a" :evil 1} [:name])))))))

(deftest page-params!-normalizes-pagination-and-status-filter
  (is (= {:page_size 10 :offset 0 :status nil :q nil} (r/page-params! {})))
  (is (= {:page_size 20 :offset 20 :status nil :q nil} (r/page-params! {:page 2 :size 20})))
  (is (= "draft" (:status (r/page-params! {:status "draft"}))))
  (is (= "kw" (:q (r/page-params! {:q "kw"}))))
  (testing "越界与非法状态"
    (is (= 400 (:status (err-data #(r/page-params! {:size 200})))))
    (is (= 400 (:status (err-data #(r/page-params! {:page 1000001})))))
    (is (= 400 (:status (err-data #(r/page-params! {:status "bogus"})))))))

(deftest version!-and-changed!-guard-optimistic-lock
  (is (= 3 (r/version! {:version 3} 3)))
  (is (nil? (r/changed! 1)))
  (testing "版本陈旧与写入行数异常均 409"
    (is (= 409 (:status (err-data #(r/version! {:version 3} 2)))))
    (is (= 400 (:status (err-data #(r/version! {:version 3} "x")))))
    (is (= 409 (:status (err-data #(r/changed! 0)))))
    (is (= 409 (:status (err-data #(r/changed! 2)))))))

(deftest editable!-blocks-paused-terminal-states
  (is (nil? (r/editable! {:status "execution"})))
  (is (nil? (r/editable! {:status "draft"})))
  (testing "paused/closed/cancelled 拒绝修改"
    (is (= 409 (:status (err-data #(r/editable! {:status "paused"})))))
    (is (= 409 (:status (err-data #(r/editable! {:status "closed"})))))
    (is (= 409 (:status (err-data #(r/editable! {:status "cancelled"})))))))

(deftest permit!-admin-wildcard-and-explicit-permission-pass
  (is (nil? (r/permit! {:admin? true} :anything)))
  (is (nil? (r/permit! {:permissions #{"a"}} "a")))
  (is (nil? (r/permit! {:permissions #{"a" "*:*:*"}} :zzz)))
  (is (nil? (r/permit! {:permissions #{"a" "b"}} ["a" "c"])))
  (testing "缺权限 403"
    (is (= 403 (:status (err-data #(r/permit! {:permissions #{"a"}} :b)))))
    (is (= 403 (:status (err-data #(r/permit! {:permissions #{}} :b)))))))

(deftest access-params!-builds-isolation-condition
  (is (= {:admin 1 :user_id 7} (r/access-params {:admin? true :user_id 7})))
  (is (= {:admin 0 :user_id 7} (r/access-params {:admin? false :user_id 7}))))

(deftest actor!-loads-live-identity-and-flags-admin
  (let [q (fake-query {:user {:user_id 1 :name "u"}
                       :roles [{:role_key "admin"}]
                       :perms [{:perms "pms:read"} {:perms "x:y"}]})]
    (let [a (r/actor q {:user-id 1})]
      (is (:admin? a))
      (is (= #{"pms:read" "x:y"} (:permissions a)))))
  (testing "非 admin 角色与只读权限"
    (let [q (fake-query {:user {:user_id 2} :roles [{:role_key "common"}] :perms [{:perms "pms:read"}]})
          a (r/actor q {:user-id 2})]
      (is (false? (:admin? a)))
      (is (= #{"pms:read"} (:permissions a)))))
  (testing "未登录与用户不存在 401"
    (is (= 401 (:status (err-data #(r/actor (fake-query {:user {:user_id 1}}) {})))))
    (is (= 401 (:status (err-data #(r/actor (fake-query {:user nil}) {:user-id 99})))))))

(deftest access!-enforces-scope-for-read-write-and-approver
  (let [project {:project_id 1 :manager_id 5}]
    (testing "项目经理读写皆通过"
      (is (= project (r/access! (fake-query {}) {:user_id 5} project false)))
      (is (= project (r/access! (fake-query {}) {:user_id 5} project true))))
    (testing "编辑成员写通过, 只读成员读通过"
      (let [q (fake-query {:member {:role "editor"}})]
        (is (= project (r/access! q {:user_id 9} project true))))
      (let [q (fake-query {:member {:role "viewer"}})]
        (is (= project (r/access! q {:user_id 9} project false)))))
    (testing "审批参与人可只读查看非成员项目"
      (let [q (fake-query {:member nil :participant {:task_id 1}})]
        (is (= project (r/access! q {:user_id 9} project false)))))
    (testing "超管无视范围"
      (is (= project (r/access! (fake-query {}) {:user_id 42 :admin? true} project true))))
    (testing "越权 403 与项目缺失 404"
      (is (= 403 (:status (err-data #(r/access! (fake-query {:member nil :participant nil}) {:user_id 9} project false)))))
      (is (= 403 (:status (err-data #(r/access! (fake-query {:member {:role "viewer"}}) {:user_id 9} project true)))))
      (is (= 404 (:status (err-data #(r/access! (fake-query {}) {:user_id 9} nil false))))))))

(deftest project-input!-normalizes-validates-and-selects-fields
  (let [q (fake-query {:user {:user_id 1} :dept {:dept_id 2}})
        base {:project_no "P1" :name "项目一" :customer "c" :contract_no "ct"
              :manager_id 1 :dept_id 2 :start_date "2026-01-01" :end_date "2026-12-31"
              :project_type "equipment"}
        out (r/project-input! q base)]
    (is (= "P1" (:project_no out)))
    (is (= "equipment" (:project_type out)))
    (is (= 1 (:manager_id out)))
    (is (= 2 (:dept_id out)))
    (is (= (set r/project-fields) (set (keys out))))
    (testing "类型默认 equipment, 可选文本可空"
      (let [o (r/project-input! q (dissoc base :project_type :customer :contract_no))]
        (is (= "equipment" (:project_type o)))
        (is (= "" (:customer o)))))
    (testing "非法类型/日期区间/缺失主数据/多余字段"
      (is (= 400 (:status (err-data #(r/project-input! q (assoc base :project_type "bogus"))))))
      (is (= 400 (:status (err-data #(r/project-input! q (assoc base :end_date "2025-12-31"))))))
      (is (= 400 (:status (err-data #(r/project-input! (fake-query {:user nil :dept {:dept_id 2}}) base)))))
      (is (= 400 (:status (err-data #(r/project-input! (fake-query {:user {:user_id 1} :dept nil}) base)))))
      (is (= :no-throw (err-data #(r/project-input! q (assoc base :evil 1)))))
      (testing "多余字段被 select-keys 静默丢弃"
        (is (not (:evil (r/project-input! q (assoc base :evil 1)))))))))
