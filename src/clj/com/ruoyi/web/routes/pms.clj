(ns com.ruoyi.web.routes.pms
  "项目管理路由,复用 JWT 认证并在领域层执行实时功能和项目权限检查."
  (:require [com.ruoyi.web.controllers.pms :as pms]
            [com.ruoyi.web.middleware.auth :as auth]))

(defn- endpoint
  "为控制器注入项目服务."
  [opts summary handler]
  {:summary summary :handler (partial handler opts)})

(defn pms-routes
  "注册项目台账,结构,成员,审计与驾驶舱接口."
  [opts]
  ["/pms"
   {:middleware [(auth/auth-middleware {:required? true})]
    :swagger {:tags ["项目管理"]}}
   ["/projects"
    ["" {:get (endpoint opts "项目台账" pms/projects)
          :post (endpoint opts "创建项目" pms/create-project)}]
    ["/:id" {:get (endpoint opts "项目详情" pms/project)
               :put (endpoint opts "修改项目" pms/update-project)}]
    ["/:id/transition" {:post (endpoint opts "项目状态转换" pms/transition)}]
    ["/:id/nodes" {:get (endpoint opts "项目结构" pms/nodes)
                     :post (endpoint opts "新增结构节点" pms/create-node)}]
    ["/:id/members" {:get (endpoint opts "项目成员" pms/members)
                       :post (endpoint opts "维护项目成员" pms/set-member)}]
    ["/:id/events" {:get (endpoint opts "项目审计" pms/events)}]]
   ["/dashboard" {:get (endpoint opts "项目驾驶舱" pms/dashboard)}]
   ["/options" {:get (endpoint opts "项目基础选项" pms/options)}]])
