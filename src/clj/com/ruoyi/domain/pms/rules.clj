(ns com.ruoyi.domain.pms.rules
  "项目领域的输入校验,身份权限和生命周期规则."
  (:require [clojure.string :as str])
  (:import [java.time LocalDate]
           [java.time.format DateTimeParseException]))

(def project-fields
  "项目允许由客户端维护的字段."
  [:project_no :name :customer :contract_no :project_type :manager_id
   :dept_id :start_date :end_date])

(def statuses
  "可识别的项目状态."
  #{"draft" "initiated" "planning" "execution" "paused" "closing" "closed" "cancelled"})

(defn fail!
  "抛出带 HTTP 状态的可预期业务异常."
  [status message]
  (throw (ex-info message {:status status :pms-error true})))

(defn actor
  "从数据库读取当前有效用户及权限,避免依赖过期的 JWT 角色快照."
  [query-fn identity]
  (when-not (:user-id identity) (fail! 401 "请先登录"))
  (let [params {:user_id (:user-id identity)}
        user (query-fn :pms/user params)]
    (when-not user (fail! 401 "用户不存在或已停用"))
    (assoc user
           :admin? (boolean (some #(= "admin" (:role_key %))
                                  (query-fn :pms/user-roles params)))
           :permissions (set (map :perms (query-fn :pms/user-perms params))))))

(defn permit!
  "检查当前用户的功能权限."
  [actor permission]
  (when-not (or (:admin? actor)
                (contains? (:permissions actor) "*:*:*")
                (some (:permissions actor)
                      (if (sequential? permission) permission [permission])))
    (fail! 403 "没有操作权限")))

(defn access-params
  "构造列表和统计共用的数据隔离条件."
  [actor]
  {:admin (if (:admin? actor) 1 0) :user_id (:user_id actor)})

(defn access!
  "检查当前项目成员和管理者的读取或编辑范围,撤销成员立即失效."
  [query-fn actor project write?]
  (when-not project (fail! 404 "项目不存在"))
  (let [uid (:user_id actor)
        member (query-fn :pms/member {:project_id (:project_id project) :user_id uid})
        manager? (= uid (:manager_id project))
        reader? (or manager? member)
        writer? (or manager? (contains? #{"manager" "editor"} (:role member)))]
    (when-not (or (:admin? actor) (if write? writer? reader?))
      (fail! 403 "没有该项目的数据访问权限")))
  project)

(defn editable!
  "暂停及终态项目不允许普通业务修改."
  [project]
  (when (contains? #{"paused" "closed" "cancelled"} (:status project))
    (fail! 409 "项目已暂停,结束或取消,不允许修改")))

(defn object!
  "要求请求体为对象且不包含不可写字段."
  [body allowed]
  (when-not (map? body) (fail! 400 "请求体必须是 JSON 对象"))
  (when (seq (remove (set allowed) (keys body)))
    (fail! 400 "请求包含不支持或不可修改的字段"))
  body)

(defn positive-id!
  "校验用户或部门标识."
  [value field]
  (let [id (cond (integer? value) value
                 (and (string? value) (re-matches #"[0-9]+" value)) (parse-long value)
                 :else nil)]
    (when-not (and id (pos? id) (<= id Long/MAX_VALUE))
      (fail! 400 (str field "必须是正整数")))
    id))

(defn text!
  "校验必填或可选文本的类型和长度."
  [value field limit required?]
  (let [value (if (nil? value) "" value)]
    (when-not (string? value) (fail! 400 (str field "必须是文本")))
    (let [value (str/trim value)]
      (when (or (> (count value) limit) (and required? (str/blank? value)))
        (fail! 400 (str field "不能为空或超出长度限制")))
      value)))

(defn date!
  "校验真实的 ISO 日历日期并规范空值."
  [value field]
  (if (or (nil? value) (= "" value)) nil
      (do
        (when-not (and (string? value) (re-matches #"[0-9]{4}-[0-9]{2}-[0-9]{2}" value))
          (fail! 400 (str field "格式必须为 YYYY-MM-DD")))
        (try (LocalDate/parse value)
             (catch DateTimeParseException _ (fail! 400 (str field "不是有效日期"))))
        value)))

(defn project-input!
  "归一化项目字段并校验日期区间与关联主数据."
  [query-fn body]
  (let [m (-> body
              (assoc :project_no (text! (:project_no body) "项目编号" 64 true)
                     :name (text! (:name body) "项目名称" 200 true)
                     :customer (text! (:customer body) "客户" 200 false)
                     :contract_no (text! (:contract_no body) "合同号" 100 false)
                     :manager_id (positive-id! (:manager_id body) "项目经理")
                     :dept_id (positive-id! (:dept_id body) "部门")
                     :start_date (date! (:start_date body) "开始日期")
                     :end_date (date! (:end_date body) "结束日期")
                     :project_type (or (:project_type body) "equipment")))]
    (when-not (contains? #{"equipment" "line" "service" "new_product" "new_technology" "special_rd" "dept_affairs"} (:project_type m))
      (fail! 400 "项目类型必须为 equipment,line,service,new_product,new_technology,special_rd 或 dept_affairs"))
    (when (and (:start_date m) (:end_date m)
               (pos? (compare (:start_date m) (:end_date m))))
      (fail! 400 "结束日期不能早于开始日期"))
    (when-not (query-fn :pms/user {:user_id (:manager_id m)})
      (fail! 400 "项目经理不存在或已停用"))
    (when-not (query-fn :pms/dept {:dept_id (:dept_id m)})
      (fail! 400 "部门不存在或已停用"))
    (select-keys m project-fields)))

(defn version!
  "要求客户端携带当前版本,拒绝陈旧写入."
  [project version]
  (let [version (positive-id! version "版本号")]
    (when-not (= version (:version project))
      (fail! 409 "项目已被其他操作更新,请刷新后重试"))
    version))

(defn changed!
  "检查乐观锁写入结果."
  [count]
  (when-not (= 1 count) (fail! 409 "项目已被其他操作更新,请刷新后重试")))

(defn page-params!
  "校验并转换分页与筛选参数."
  [params]
  (let [page (positive-id! (or (:page params) 1) "页码")
        size (positive-id! (or (:size params) 10) "每页数量")
        status (not-empty (text! (:status params) "状态" 20 false))]
    (when (> size 100) (fail! 400 "每页数量不能超过 100"))
    (when (> page 1000000) (fail! 400 "页码超出允许范围"))
    (when (and status (not (statuses status))) (fail! 400 "无效项目状态"))
    {:page_size size :offset (* (dec page) size) :status status
     :q (not-empty (text! (:q params) "搜索内容" 200 false))}))
