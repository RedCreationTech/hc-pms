(ns com.ruoyi.frontend.router
  "前端路由管理 — 手动实现，不依赖 accountant。"
  (:require
   [bidi.bidi :as bidi]
   [re-frame.core :as rf]))

;; 路由定义
(def routes
  ["/" {"" :dashboard
        "dashboard" :dashboard
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
        "system/user/profile" :profile}])

;; 路由匹配
(defn match-route [path]
  (bidi/match-route routes path))

;; 获取页面路径
(defn page-path [page]
  (or (bidi/path-for routes page) "/"))

;; 页面名称映射
(def page-names
  {:dashboard "首页"
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
   :profile "个人中心"})

;; 状态标记
(defonce initialized? (volatile! false))

;; 监听浏览器前进/后退
(defn- on-popstate [^js _event]
  (let [path (.-pathname js/location)
        match (match-route path)
        page (or (:handler match) :dashboard)]
    (rf/dispatch [:navigate page])))

;; 初始化路由
(defn init-routes! []
  (when-not @initialized?
    (.addEventListener js/window "popstate" on-popstate)
    (vreset! initialized? true)
    ;; 手动 dispatch 当前 URL
    (let [path (.-pathname js/location)
          match (match-route path)
          page (or (:handler match) :dashboard)]
      (rf/dispatch-sync [:navigate page]))))

;; 导航到页面（只更新 URL，不 dispatch 事件）
(defn navigate! [page]
  (when @initialized?
    (let [path (page-path page)]
      (.pushState js/history nil "" path))))
