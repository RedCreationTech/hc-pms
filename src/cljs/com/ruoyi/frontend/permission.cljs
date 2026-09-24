(ns com.ruoyi.frontend.permission
  "前端权限: 按钮权限判断 (对应若依 v-hasPermi) 与页面访问判断 (路由守卫).
   后端是权限边界, 这里只决定界面是否显示入口."
  (:require
    [com.ruoyi.frontend.router :as router]
    [re-frame.core :as rf]))


(defn admin?
  [auth]
  (let [permissions (set (:permissions auth))]
    (boolean (or (contains? (set (:roles auth)) "admin")
                 (contains? permissions "*:*:*")))))


(defn has-permi?
  "认证信息中是否拥有任一权限."
  [auth perms]
  (let [owned (set (:permissions auth))
        required (if (sequential? perms) perms [perms])]
    (boolean (or (admin? auth) (some owned required)))))


(defn permitted?
  "当前登录用户是否拥有任一权限 (在组件渲染中调用, 随权限刷新自动更新)."
  [perms]
  (has-permi? @(rf/subscribe [:auth/user]) perms))


(defn- menu-pages
  "菜单树中全部 C 类菜单 (含隐藏菜单) 对应的页面路由键."
  ([menus] (menu-pages menus ""))
  ([menus parent-path]
   (reduce (fn [acc m]
             (let [path (:path m)
                   full-path (cond
                               (not (seq path)) parent-path
                               (seq parent-path) (str parent-path "/" path)
                               :else path)
                   route-key (when (and (seq full-path) (= "C" (:menu_type m)))
                               (:handler (router/match-route (str "/" full-path))))
                   acc (cond-> acc route-key (conj route-key))]
               (into acc (menu-pages (:children m) full-path))))
           #{}
           menus)))


(def always-allowed
  "所有登录用户都可访问的页面."
  #{:dashboard :profile})


(def derived-pages
  "没有独立菜单的子页面: 页面键 -> 可进入它的页面或权限."
  {:bpm-model-edit {:pages #{:bpm-model} :perms ["bpm:model:edit" "bpm:model:add"]}
   :bpm-definition {:pages #{:bpm-model} :perms ["bpm:model:list"]}
   :bpm-start {:pages #{:bpm-instance} :perms ["bpm:instance:start"]}})


(defn allowed-pages
  "当前用户可访问的页面集合; 超级管理员返回 :all."
  [auth]
  (if (admin? auth)
    :all
    (let [base (into always-allowed (menu-pages (:menus auth)))]
      (into base
            (keep (fn [[page {parents :pages perms :perms}]]
                    (when (or (some base parents) (has-permi? auth perms))
                      page)))
            derived-pages))))


(defn page-allowed?
  [auth page]
  (let [allowed (allowed-pages auth)]
    (or (= :all allowed) (contains? allowed page))))
