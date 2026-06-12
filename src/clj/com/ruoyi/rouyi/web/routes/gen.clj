(ns com.ruoyi.rouyi.web.routes.gen
  "代码生成器路由。"
  (:require
   [com.ruoyi.rouyi.web.controllers.gen :as gen]
   [com.ruoyi.rouyi.web.middleware.auth :as auth-mw]))

(defn gen-routes [{:keys [gen-service]}]
  ["/tool"
   {:middleware [(auth-mw/auth-middleware {:required? true})]
    :swagger {:tags ["代码生成"]}}
   ["/gen"
    ["/tables" {:get {:summary    "查询数据库表"
                      :description "查询系统所有数据库表（供选择生成）"
                      :handler    (partial gen/list-tables {:gen-service gen-service})}}]
    ["/columns" {:get {:summary    "查询表列信息"
                       :description "查询指定表的字段元数据"
                       :handler    (partial gen/table-columns {:gen-service gen-service})}}]
    ["/preview" {:get {:summary    "预览代码"
                       :description "预览生成的代码模板内容"
                       :handler    (partial gen/preview-code {:gen-service gen-service})}}]
    ["/generate" {:post {:summary    "批量生成代码"
                         :description "选择表并生成完整 CRUD 代码文件"
                         :handler    (partial gen/batch-generate {:gen-service gen-service})}}]]])
