(ns com.ruoyi.web.routes.business
  "业务模块路由聚合（BPM / OA / HRM / CRM）。"
  (:require
   [com.ruoyi.web.controllers.business.bpm :as bpm]
   [com.ruoyi.web.middleware.auth :as auth-mw]))

(defn business-routes [{:keys [bpm-service]}]
  ["/business"
   {:middleware [(auth-mw/auth-middleware {:required? true})]
    :swagger {:tags ["办公" "BPM"]}}

   ;; ── 流程分类 ──
   ["/bpm/category"
    ["" {:get  {:summary "流程分类列表" :handler (partial bpm/list-categories {:bpm-service bpm-service})}
         :post {:summary "新增流程分类" :handler (partial bpm/create-category {:bpm-service bpm-service})}}]
    ["/:id" {:get    {:summary "分类详情" :handler (partial bpm/get-category {:bpm-service bpm-service})}
             :put    {:summary "更新分类" :handler (partial bpm/update-category {:bpm-service bpm-service})}
             :delete {:summary "删除分类" :handler (partial bpm/delete-category {:bpm-service bpm-service})}}]]

   ;; ── 流程模型 ──
   ["/bpm/model"
    ["" {:get  {:summary "流程模型列表" :handler (partial bpm/list-models {:bpm-service bpm-service})}
         :post {:summary "新增流程模型" :handler (partial bpm/create-model {:bpm-service bpm-service})}}]
    ["/deploy/:id" {:post {:summary "部署流程模型" :handler (partial bpm/deploy-model {:bpm-service bpm-service})}}]
    ["/:id" {:get    {:summary "模型详情" :handler (partial bpm/get-model {:bpm-service bpm-service})}
             :put    {:summary "更新模型" :handler (partial bpm/update-model {:bpm-service bpm-service})}
             :delete {:summary "删除模型" :handler (partial bpm/delete-model {:bpm-service bpm-service})}}]]

   ;; ── 动态表单 ──
   ["/bpm/form"
    ["" {:get  {:summary "动态表单列表" :handler (partial bpm/list-forms {:bpm-service bpm-service})}
         :post {:summary "新增动态表单" :handler (partial bpm/create-form {:bpm-service bpm-service})}}]
    ["/:id" {:get    {:summary "表单详情" :handler (partial bpm/get-form {:bpm-service bpm-service})}
             :put    {:summary "更新表单" :handler (partial bpm/update-form {:bpm-service bpm-service})}
             :delete {:summary "删除表单" :handler (partial bpm/delete-form {:bpm-service bpm-service})}}]]

   ;; ── 流程实例 ──
   ["/bpm/instance"
    ["" {:get  {:summary "流程实例列表" :handler (partial bpm/list-instances {:bpm-service bpm-service})}
         :post {:summary "发起流程实例" :handler (partial bpm/start-instance {:bpm-service bpm-service})}}]
    ["/history/:pid" {:get {:summary "实例历史轨迹" :handler (partial bpm/instance-history {:bpm-service bpm-service})}}]]

   ;; ── 任务 ──
   ["/bpm/todo"    {:get {:summary "我的待办" :handler (partial bpm/list-todo {:bpm-service bpm-service})}}]
   ["/bpm/done"    {:get {:summary "我的已办" :handler (partial bpm/list-done {:bpm-service bpm-service})}}]
   ["/bpm/task/:id/approve" {:post {:summary "审批通过" :handler (partial bpm/approve-task {:bpm-service bpm-service})}}]
   ["/bpm/task/:id/reject"  {:post {:summary "审批驳回" :handler (partial bpm/reject-task {:bpm-service bpm-service})}}]
   ["/bpm/task/:id/claim"   {:post {:summary "认领任务" :handler (partial bpm/claim-task {:bpm-service bpm-service})}}]
   ["/bpm/task/:id/transfer" {:post {:summary "转办任务" :handler (partial bpm/transfer-task {:bpm-service bpm-service})}}]])
