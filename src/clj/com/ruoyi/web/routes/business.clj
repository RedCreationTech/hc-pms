(ns com.ruoyi.web.routes.business
  "业务模块路由聚合（BPM / OA / HRM / CRM）。"
  (:require
    [com.ruoyi.web.controllers.business.bpm :as bpm]
    [com.ruoyi.web.controllers.business.bpm-mgmt :as mgmt]
    [com.ruoyi.web.controllers.business.crm :as crm]
    [com.ruoyi.web.controllers.business.hrm :as hrm]
    [com.ruoyi.web.controllers.business.leave :as leave]
    [com.ruoyi.web.controllers.business.oa :as oa]
    [com.ruoyi.web.controllers.business.reimburse :as reimburse]
    [com.ruoyi.web.middleware.auth :as auth-mw]))


(defn business-routes
  [{:keys [bpm-service hrm-service oa-service crm-service leave-service reimburse-service mgmt-service]}]
  ["/business"
   {:middleware [(auth-mw/auth-middleware {:required? true})]
    :swagger {:tags ["办公" "BPM"]}}

   ;; ── 流程分类 ──
   ["/bpm/category"
    ["" {:get  {:summary "流程分类列表" :handler (partial bpm/list-categories {:bpm-service bpm-service})}
         :post {:summary "新增流程分类" :handler (partial bpm/create-category {:bpm-service bpm-service})}}]
    ["/sort" {:put {:summary "批量保存分类排序" :handler (partial bpm/sort-categories {:bpm-service bpm-service})}}]
    ["/:id" {:get    {:summary "分类详情" :handler (partial bpm/get-category {:bpm-service bpm-service})}
             :put    {:summary "更新分类" :handler (partial bpm/update-category {:bpm-service bpm-service})}
             :delete {:summary "删除分类" :handler (partial bpm/delete-category {:bpm-service bpm-service})}}]]

   ;; ── 流程模型 ──
   ["/bpm/model"
    ["" {:get  {:summary "流程模型列表" :handler (partial bpm/list-models {:bpm-service bpm-service})}
         :post {:summary "新增流程模型" :handler (partial bpm/create-model {:bpm-service bpm-service})}}]
    ["/deploy/:id" {:post {:summary "部署流程模型" :handler (partial bpm/deploy-model {:bpm-service bpm-service})}}]
    ["/state" {:put {:summary "挂起/激活流程定义" :handler (partial bpm/model-set-state {:bpm-service bpm-service})}}]
    ["/clean" {:delete {:summary "清理流程历史实例与部署" :handler (partial bpm/model-clean {:bpm-service bpm-service})}}]
    ["/copy" {:post {:summary "复制流程模型" :handler (partial bpm/model-copy {:bpm-service bpm-service})}}]
    ["/sort" {:put {:summary "批量保存模型排序" :handler (partial bpm/sort-models {:bpm-service bpm-service})}}]
    ["/:id/tree" {:get  {:summary "流程节点树" :handler (partial bpm/model-tree {:bpm-service bpm-service})}
                  :post {:summary "保存流程节点树" :handler (partial bpm/model-save-tree {:bpm-service bpm-service})}}]
    ["/:id" {:get    {:summary "模型详情" :handler (partial bpm/get-model {:bpm-service bpm-service})}
             :put    {:summary "更新模型" :handler (partial bpm/update-model {:bpm-service bpm-service})}
             :delete {:summary "删除模型" :handler (partial bpm/delete-model {:bpm-service bpm-service})}}]]

   ;; ── Phase 3 治理能力：流程定义版本 ──
   ["/bpm/definition/page" {:get {:summary "流程定义分页(版本)" :handler (partial bpm/definition-page {:bpm-service bpm-service})}}]
   ["/bpm/definition/xml" {:get {:summary "流程定义BPMN XML" :handler (partial bpm/definition-xml {:bpm-service bpm-service})}}]
   ["/bpm/definition/restore" {:put {:summary "历史定义恢复回模型" :handler (partial bpm/definition-restore {:bpm-service bpm-service})}}]

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
    ["/history/:pid" {:get {:summary "实例历史轨迹" :handler (partial bpm/instance-history {:bpm-service bpm-service})}}]
    ["/diagram/:pid" {:get {:summary "实例流程图(高亮)" :handler (partial bpm/instance-diagram {:bpm-service bpm-service})}}]
    ["/print-data" {:get {:summary "实例打印数据" :handler (partial bpm/print-data {:bpm-service bpm-service})}}]]

   ;; ── 任务 ──
   ["/bpm/todo"    {:get {:summary "我的待办" :handler (partial bpm/list-todo {:bpm-service bpm-service})}}]
   ["/bpm/done"    {:get {:summary "我的已办" :handler (partial bpm/list-done {:bpm-service bpm-service})}}]
   ["/bpm/task/:id/detail" {:get {:summary "任务详情(表单回显)" :handler (partial bpm/task-detail {:bpm-service bpm-service})}}]
   ["/bpm/task/:id/approve" {:post {:summary "审批通过" :handler (partial bpm/approve-task {:bpm-service bpm-service})}}]
   ["/bpm/task/:id/reject"  {:post {:summary "审批驳回" :handler (partial bpm/reject-task {:bpm-service bpm-service})}}]
   ["/bpm/task/:id/claim"   {:post {:summary "认领任务" :handler (partial bpm/claim-task {:bpm-service bpm-service})}}]
   ["/bpm/task/:id/transfer" {:post {:summary "转办任务" :handler (partial bpm/transfer-task {:bpm-service bpm-service})}}]
   ["/bpm/task/:id/delegate" {:post {:summary "委派任务" :handler (partial bpm/delegate-task {:bpm-service bpm-service})}}]
   ["/bpm/task/resolve" {:post {:summary "委派办结(任务回到 owner)" :handler (partial bpm/resolve-task {:bpm-service bpm-service})}}]

   ;; ── Phase 1 审批闭环：加签/减签/抄送/撤回/可退回节点 ──
   ["/bpm/task/create-sign" {:post {:summary "加签" :handler (partial bpm/create-sign {:bpm-service bpm-service})}}]
   ["/bpm/task/delete-sign" {:delete {:summary "减签" :handler (partial bpm/delete-sign {:bpm-service bpm-service})}}]
   ["/bpm/task/sign-list" {:get {:summary "加签子任务列表" :handler (partial bpm/sign-list {:bpm-service bpm-service})}}]
   ["/bpm/task/return-list" {:get {:summary "可退回节点列表" :handler (partial bpm/return-list {:bpm-service bpm-service})}}]
   ["/bpm/task/copy" {:post {:summary "任务抄送" :handler (partial bpm/copy-task {:bpm-service bpm-service})}}]
   ["/bpm/task/copy/page" {:get {:summary "我的抄送分页" :handler (partial bpm/copy-page {:bpm-service bpm-service})}}]
   ["/bpm/task/withdraw" {:put {:summary "撤回已办任务" :handler (partial bpm/withdraw-task {:bpm-service bpm-service})}}]
   ["/bpm/task/withdraw-to-start" {:put {:summary "撤回到起始节点" :handler (partial bpm/withdraw-to-start {:bpm-service bpm-service})}}]
   ["/bpm/instance/cancel" {:delete {:summary "取消流程实例(发起人或管理员)" :handler (partial bpm/cancel-instance {:bpm-service bpm-service})}}]

   ;; ── 流程任务管理 / 流程实例运维 ──
   ["/bpm/task/all" {:get {:summary "全部任务(管理员)" :handler (partial bpm/list-all-tasks {:bpm-service bpm-service})}}]
   ["/bpm/instance/:pid/suspend"  {:post {:summary "挂起流程实例" :handler (partial bpm/suspend-instance {:bpm-service bpm-service})}}]
   ["/bpm/instance/:pid/activate" {:post {:summary "激活流程实例" :handler (partial bpm/activate-instance {:bpm-service bpm-service})}}]
   ["/bpm/instance/:pid/terminate" {:post {:summary "终止流程实例" :handler (partial bpm/terminate-instance {:bpm-service bpm-service})}}]

   ;; ── 办公报表 ──
   ["/report/stats" {:get {:summary "办公一体化统计看板" :handler (partial bpm/office-stats {:bpm-service bpm-service})}}]

   ;; ── BPM 管理：用户分组/监听器/表达式/设置（通用 CRUD）──
   ["/bpm/:module"
    ["" {:get  {:summary "BPM管理列表" :handler (partial mgmt/list-items {:mgmt-service mgmt-service})}
         :post {:summary "BPM管理新增" :handler (partial mgmt/create-item {:mgmt-service mgmt-service})}}]
    ["/:id" {:get    {:summary "BPM管理详情" :handler (partial mgmt/get-item {:mgmt-service mgmt-service})}
             :put    {:summary "BPM管理更新" :handler (partial mgmt/update-item {:mgmt-service mgmt-service})}
             :delete {:summary "BPM管理删除" :handler (partial mgmt/delete-item {:mgmt-service mgmt-service})}}]]

   ;; ── HRM 员工 ──
   ["/hrm/employee"
    ["" {:get  {:summary "员工列表" :handler (partial hrm/list-employees {:hrm-service hrm-service})}
         :post {:summary "新增员工" :handler (partial hrm/create-employee {:hrm-service hrm-service})}}]
    ["/:id" {:get    {:summary "员工详情" :handler (partial hrm/get-employee {:hrm-service hrm-service})}
             :put    {:summary "更新员工" :handler (partial hrm/update-employee {:hrm-service hrm-service})}
             :delete {:summary "删除员工" :handler (partial hrm/delete-employee {:hrm-service hrm-service})}}]]

   ;; ── OA 日程 ──
   ["/oa/calendar"
    ["" {:get  {:summary "日程列表" :handler (partial oa/list-calendars {:oa-service oa-service})}
         :post {:summary "新增日程" :handler (partial oa/create-calendar {:oa-service oa-service})}}]
    ["/:id" {:get    {:summary "日程详情" :handler (partial oa/get-calendar {:oa-service oa-service})}
             :put    {:summary "更新日程" :handler (partial oa/update-calendar {:oa-service oa-service})}
             :delete {:summary "删除日程" :handler (partial oa/delete-calendar {:oa-service oa-service})}}]]

   ;; ── OA 会议 ──
   ["/oa/meeting"
    ["" {:get  {:summary "会议列表" :handler (partial oa/list-meetings {:oa-service oa-service})}
         :post {:summary "新增会议" :handler (partial oa/create-meeting {:oa-service oa-service})}}]
    ["/:id" {:get    {:summary "会议详情" :handler (partial oa/get-meeting {:oa-service oa-service})}
             :put    {:summary "更新会议" :handler (partial oa/update-meeting {:oa-service oa-service})}
             :delete {:summary "删除会议" :handler (partial oa/delete-meeting {:oa-service oa-service})}}]]

   ;; ── CRM 客户 ──
   ["/crm/customer"
    ["" {:get  {:summary "客户列表" :handler (partial crm/list-customers {:crm-service crm-service})}
         :post {:summary "新增客户" :handler (partial crm/create-customer {:crm-service crm-service})}}]
    ["/:id" {:get    {:summary "客户详情" :handler (partial crm/get-customer {:crm-service crm-service})}
             :put    {:summary "更新客户" :handler (partial crm/update-customer {:crm-service crm-service})}
             :delete {:summary "删除客户" :handler (partial crm/delete-customer {:crm-service crm-service})}}]]

   ;; ── OA 请假（业务 + BPM 集成）──
   ["/oa/leave"
    ["" {:get  {:summary "请假单列表(自动同步审批状态)" :handler (partial leave/list-leaves {:leave-service leave-service})}
         :post {:summary "发起请假申请(入审批流)" :handler (partial leave/start-leave {:leave-service leave-service})}}]
    ["/:id" {:get    {:summary "请假单详情" :handler (partial leave/get-leave {:leave-service leave-service})}
             :delete {:summary "删除请假单" :handler (partial leave/delete-leave {:leave-service leave-service})}}]]

   ;; ── OA 报销（业务 + BPM 集成）──
   ["/oa/reimburse"
    ["" {:get  {:summary "报销单列表(自动同步审批状态)" :handler (partial reimburse/list-reimburses {:reimburse-service reimburse-service})}
         :post {:summary "发起报销申请(入审批流)" :handler (partial reimburse/start-reimburse {:reimburse-service reimburse-service})}}]
    ["/:id" {:get    {:summary "报销单详情" :handler (partial reimburse/get-reimburse {:reimburse-service reimburse-service})}
             :delete {:summary "删除报销单" :handler (partial reimburse/delete-reimburse {:reimburse-service reimburse-service})}}]]])
