(ns com.ruoyi.rouyi.frontend.router
  "前端路由管理。"
  (:require
    [bidi.bidi :as bidi]
    [accountant.core :as accountant]
    [re-frame.core :as rf]))

;; 路由定义
(def routes
  ["/" {"" :dashboard
        "system/user" :user
        "system/role" :role
        "system/menu" :menu
        "system/dept" :dept
        "system/post" :post
        "system/dict" :dict
        "system/config" :config
        "system/notice" :notice
        "monitor/operlog" :oper-log
        "monitor/logininfor" :login-log
        "monitor/online" :online
        "monitor/job" :job
        "monitor/server" :server
        "monitor/cache" :cache
        "tool/gen" :gen
        "system/user/profile" :profile}])

;; 路由匹配
(defn match-route [path]
  (bidi/match-route routes path))

;; 获取页面路径
(defn page-path [page]
  (bidi/path-for routes page))

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
   :gen "代码生成"
   :profile "个人中心"})

;; 状态标记
(defonce initialized? (volatile! false))

;; 初始化路由
(defn init-routes! []
  (try
    (accountant/configure-navigation!
      :nav-handler (fn [path]
                     (let [match (match-route path)
                           page (or (:handler match) :dashboard)]
                       (rf/dispatch [:navigate page])))
      :path-exists? (fn [path]
                      (boolean (match-route path))))
    (vreset! initialized? true)
    (accountant/dispatch-current!)
    (catch js/Error e
      (js/console.warn "router: init failed" (.-message e)))))

;; 导航到页面
(defn navigate! [page]
  (when @initialized?
    (try
      (accountant/navigate! (page-path page))
      (catch js/Error e
        (js/console.warn "router: navigate failed" (.-message e))))))
