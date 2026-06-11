(ns com.ruoyi.rouyi.frontend.pages.dashboard
  "仪表盘首页。")

(defn dashboard-page []
  [:div
   [:h2 "欢迎使用若依管理系统"]
   [:p "这是一个基于 Clojure + ClojureScript + Ant Design 的全栈管理后台。"]
   [:ul
    [:li "系统管理：用户、角色、菜单、部门、岗位、字典、参数配置"]
    [:li "权限控制：RBAC 模型，支持菜单和按钮权限"]
    [:li "日志审计：操作日志、登录日志、在线用户监控"]
    [:li "定时任务：Quartz 调度管理"]
    [:li "代码生成：根据表结构自动生成前后端代码"]
    [:li "安全机制：JWT 认证、密码加密、XSS/CSRF 防护"]]])
