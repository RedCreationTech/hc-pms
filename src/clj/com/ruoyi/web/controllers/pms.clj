(ns com.ruoyi.web.controllers.pms
  "项目管理 HTTP 控制器,保留真实 HTTP 错误状态."
  (:require [clojure.tools.logging :as log]
            [com.ruoyi.domain.pms.service :as pms]
            [com.ruoyi.web.controllers.business.util :as util]
            [ring.util.response :as response]))

(defn- reply
  "封装统一响应体并设置 HTTP 状态."
  [status message data]
  (-> (response/response {:code status :msg message :data data})
      (response/status status)
      (response/content-type "application/json")))

(defn- invoke
  "解析数据库身份并返回安全错误,内部异常只记录到服务日志."
  [{:keys [pms-service]} request f]
  (try
    (reply 200 "操作成功" (f pms-service (pms/actor pms-service (:identity request))))
    (catch clojure.lang.ExceptionInfo e
      (if (:pms-error (ex-data e))
        (reply (:status (ex-data e)) (.getMessage e) nil)
        (do (log/error e "PMS 请求失败")
            (reply 500 "服务暂时不可用,请稍后重试" nil))))
    (catch Exception e
      (log/error e "PMS 请求失败")
      (reply 500 "服务暂时不可用,请稍后重试" nil))))

(defn- id
  "读取项目路由标识."
  [request]
  (get-in request [:path-params :id]))

(defn projects
  "查询项目台账."
  [opts request]
  (invoke opts request #(pms/projects %1 %2 (util/kquery request))))

(defn project
  "查询项目详情."
  [opts request]
  (invoke opts request #(pms/project %1 %2 (id request))))

(defn create-project
  "创建项目草稿."
  [opts request]
  (invoke opts request #(pms/create-project! %1 %2 (:body-params request))))

(defn update-project
  "按版本更新项目资料."
  [opts request]
  (invoke opts request #(pms/update-project! %1 %2 (id request) (:body-params request))))

(defn transition
  "推进或取消项目."
  [opts request]
  (invoke opts request #(pms/transition-project! %1 %2 (id request) (:body-params request))))

(defn nodes
  "读取项目结构."
  [opts request]
  (invoke opts request #(pms/nodes %1 %2 (id request))))

(defn create-node
  "新增子项目或单机."
  [opts request]
  (invoke opts request #(pms/create-node! %1 %2 (id request) (:body-params request))))

(defn members
  "读取项目成员."
  [opts request]
  (invoke opts request #(pms/members %1 %2 (id request))))

(defn set-member
  "增加成员或调整成员角色."
  [opts request]
  (invoke opts request #(pms/set-member! %1 %2 (id request) (:body-params request))))

(defn events
  "读取项目审计轨迹."
  [opts request]
  (invoke opts request #(pms/events %1 %2 (id request))))

(defn dashboard
  "读取驾驶舱指标."
  [opts request]
  (invoke opts request pms/dashboard))

(defn options
  "读取基础数据选项."
  [opts request]
  (invoke opts request pms/options))
