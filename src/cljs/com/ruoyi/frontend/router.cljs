(ns com.ruoyi.frontend.router
  "前端路由管理 -- 手动实现,不依赖 accountant."
  (:require
    [bidi.bidi :as bidi]
    [re-frame.core :as rf]))


;; 路由定义
(def routes
  ["/" {"" :dashboard
        "dashboard" :dashboard
        "pms/project" :pms-project
        "pms/dashboard" :pms-dashboard
        "pms/config" :pms-config
        "pms/portfolio" :pms-portfolio
        "pms/todo" :pms-todo
        "pms/search" :pms-search
        "pms/targets" :pms-targets
        "system/user" :user
        "system/role" :role
        "system/menu" :menu
        "system/dept" :dept
        "system/post" :post
        "system/dict" :dict
        "system/config" :config
        "system/notice" :notice
        "system/operlog/operlog" :oper-log
        "system/operlog/logininfor" :login-log
        "monitor/operlog" :oper-log
        "monitor/logininfor" :login-log
        "monitor/online" :online
        "monitor/job" :job
        "monitor/server" :server
        "monitor/cache" :cache
        "monitor/datasource" :datasource
        "monitor/integrant" :integrant
        "monitor/swagger" :swagger
        "system/user/profile" :profile
        "office/bpm/model" :bpm-model
        "office/bpm/model/edit" :bpm-model-edit
        "office/bpm/definition" :bpm-definition
        "office/bpm/instance" :bpm-instance
        "office/bpm/form" :bpm-form
        "office/bpm/category" :bpm-category
        "office/bpm/user-group" :bpm-user-group
        "office/bpm/listener" :bpm-listener
        "office/bpm/expression" :bpm-expression
        "office/bpm/settings" :bpm-settings
        "office/bpm/instance-manager" :bpm-instance-manager
        "office/bpm/task-manager" :bpm-task-manager
        "office/bpm/instance-ops" :bpm-instance-ops
        "office/bpm/todo" :bpm-todo
        "office/bpm/start" :bpm-start
        "office/bpm/done" :bpm-done
        "office/bpm/copy" :bpm-copy
        "office/oa/leave" :leave
        "office/oa/reimburse" :reimburse
        "office/report" :report
        "office/oa/calendar" :oa-calendar
        "office/oa/meeting" :oa-meeting
        "office/hrm/employee" :hrm-employee
        "office/crm/customer" :crm-customer}])


;; 路由匹配
(defn match-route
  [path]
  (bidi/match-route routes path))


;; 获取页面路径
(defn page-path
  [page]
  (or (bidi/path-for routes page) "/"))


;; 页面名称映射
(def page-names
  {:dashboard "首页"
   :pms-project "项目中心"
   :pms-dashboard "项目驾驶舱"
   :pms-config "模板与规则"
   :pms-portfolio "项目组合看板"
   :pms-todo "我的待办"
   :pms-search "全局检索"
   :pms-targets "经营目标看板"
   :user "用户管理"
   :role "角色管理"
   :menu "菜单管理"
   :dept "部门管理"
   :post "岗位管理"
   :dict "字典管理"
   :config "参数管理"
   :notice "通知公告"
   :oper-log "操作日志"
   :login-log "登录日志"
   :online "在线用户"
   :job "定时任务"
   :server "服务监控"
   :cache "缓存监控"
   :datasource "数据监控"
   :integrant "Integrant 依赖"
   :swagger "系统接口"
   :profile "个人中心"
   :office "办公"
   :bpm-model "流程模型"
   :bpm-model-edit "流程模型设计"
   :bpm-definition "流程定义版本"
   :bpm-instance "我的流程"
   :bpm-form "流程表单"
   :bpm-category "流程分类"
   :bpm-user-group "用户分组"
   :bpm-listener "流程监听器"
   :bpm-expression "流程表达式"
   :bpm-settings "流程设置"
   :bpm-instance-manager "流程实例管理"
   :bpm-task-manager "流程任务管理"
   :bpm-instance-ops "流程实例运维"
   :bpm-todo "我的待办"
   :bpm-start "发起流程"
   :bpm-done "我的已办"
   :bpm-copy "抄送我的"
   :leave "请假申请"
   :reimburse "报销申请"
   :report "办公报表"
   :oa-calendar "日程管理"
   :oa-meeting "会议管理"
   :hrm-employee "员工管理"
   :crm-customer "客户管理"})


;; 状态标记
(defonce initialized? (volatile! false))


(defn- current-query
  "当前 URL 的 query 参数(keyword 键的映射)."
  []
  (let [params (js/URLSearchParams. (.-search js/location))
        ks (js/Array.from (.keys params))]
    (into {} (map (fn [k] [(keyword k) (.get params k)])) ks)))


;; 监听浏览器前进/后退
(defn- on-popstate
  [^js _event]
  (let [path (.-pathname js/location)
        match (match-route path)
        page (or (:handler match) :dashboard)]
    (rf/dispatch [:navigate page (current-query)])))


;; 初始化路由
(defn init-routes!
  []
  (when-not @initialized?
    (.addEventListener js/window "popstate" on-popstate)
    (vreset! initialized? true)
    ;; 手动 dispatch 当前 URL(带上 query,刷新/直达时编辑器页需要 ?id=)
    (let [path (.-pathname js/location)
          match (match-route path)
          page (or (:handler match) :dashboard)]
      (rf/dispatch-sync [:navigate page (current-query)]))))


;; 导航到页面(只更新 URL,不 dispatch 事件);query 为可选参数映射,如 {:id 1}
(defn navigate!
  ([page] (navigate! page nil))
  ([page query]
   (when @initialized?
     (let [path (page-path page)
           qs (when (seq query)
                (let [params (js/URLSearchParams.)]
                  (doseq [[k v] query]
                    (.set params (name k) (str v)))
                  (str "?" (.toString params))))]
       (.pushState js/history nil "" (str path qs))))))
