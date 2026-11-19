(ns com.ruoyi.web.routes.pms-cancellation
  "B17 审批驱动的项目/单机取消路由, 挂在 /api/pms 下."
  (:require
    [com.ruoyi.domain.pms.cancellation :as cancellation]
    [com.ruoyi.web.controllers.pms-finance :as http]
    [com.ruoyi.web.controllers.pms-http :as core]))


(defn- command
  "按固定路由参数顺序执行类型化业务命令, 不接受任意类型 CRUD."
  [svc f keys]
  (fn [request] (http/command svc f keys request)))


(def ^:private perms
  "读取需项目查看权限; 建单/提交/审核另由领域层校验项目状态转换权限与职责分离."
  ["pms:project:list" "pms:project:query" "pms:project:transition"])


(defn cancellation-routes
  "返回取消申请与审批路由."
  [svc]
  [["/projects/:id/cancellations"
    ["" {:get {:summary "项目取消申请与停止下发汇总" :perms perms
               :handler (partial http/query svc cancellation/workspace)}
         :post {:summary "登记取消申请 (须关联已批准变更依据)" :perms perms
                :handler (command svc cancellation/create! [])}}]
    ["/:record_id" {:get {:summary "取消申请明细" :perms perms
                          :handler (fn [request]
                                     (core/invoke svc request
                                                  #(cancellation/view %1 %2 (core/project-id request)
                                                                      (get-in request [:path-params :record_id]))))}}]
    ["/:record_id/withdraw" {:post (command svc cancellation/withdraw! [:record_id])}]
    ["/:record_id/submit" {:post (command svc cancellation/submit! [:record_id])}]
    ["/:record_id/review" {:post (command svc cancellation/review! [:record_id])}]]
   ["/cancellations/approval-types" {:get {:summary "取消审批策略是否已配置" :perms perms
                                           :handler (partial http/query svc cancellation/approval-types)}}]])
