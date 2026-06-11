(ns com.ruoyi.rouyi.web.routes.gen
  "代码生成器路由。"
  (:require
    [com.ruoyi.rouyi.web.controllers.gen :as gen]
    [com.ruoyi.rouyi.web.middleware.auth :as auth-mw]))

(defn gen-routes [{:keys [gen-service]}]
  ["/tool"
   {:middleware [((auth-mw/auth-middleware {:required? true}))]}
   ["/gen"
    ;; 查询所有表
    ["/tables" {:get {:handler (partial gen/list-tables {:gen-service gen-service})}}]
    ;; 查询表列信息
    ["/columns" {:get {:handler (partial gen/table-columns {:gen-service gen-service})}}]
    ;; 预览代码模板
    ["/preview" {:get {:handler (partial gen/preview-code {:gen-service gen-service})}}]
    ;; 批量生成代码
    ["/generate" {:post {:handler (partial gen/batch-generate {:gen-service gen-service})}}]]])
