(ns com.ruoyi.domain.system.menu
  "菜单领域服务,处理菜单树构建与 CRUD."
  (:require
    [clojure.walk :as walk]
    [com.ruoyi.infra.db :as db]))


(defn list-menus
  "查询菜单列表."
  [{:keys [query-fn]} params]
  (query-fn :list-menus (merge {:menu_name nil :status nil :menu_type nil} params)))


(defn find-menu-by-id
  "根据ID查询菜单."
  [{:keys [query-fn]} menu-id]
  (query-fn :find-menu-by-id {:menu_id menu-id}))


(defn create-menu!
  "创建菜单."
  [{:keys [query-fn db]} params]
  (db/insert-and-get-id! query-fn db :create-menu!
                         (merge {:menu_name nil :parent_id nil :order_num nil :path nil
                                 :component nil :query nil :route_name nil :is_frame nil
                                 :is_cache nil :menu_type nil :visible nil :status nil
                                 :perms nil :icon nil :create_by nil}
                                params)))


(defn update-menu!
  "更新菜单."
  [{:keys [query-fn]} params]
  (query-fn :update-menu! (merge {:menu_name nil :parent_id nil :order_num nil :path nil
                                  :component nil :query nil :route_name nil :is_frame nil
                                  :is_cache nil :menu_type nil :visible nil :status nil
                                  :perms nil :icon nil :create_by nil :create_time nil :update_by nil}
                                 params)))


(defn update-menu-order!
  "批量更新菜单排序."
  [{:keys [query-fn]} items]
  (doseq [{:keys [menu_id order_num]} items]
    (query-fn :update-menu-order! {:menu_id menu_id :order_num order_num})))


(defn delete-menu!
  "删除菜单."
  [{:keys [query-fn]} menu-id]
  (query-fn :delete-menu! {:menu_id menu-id}))


(defn- build-tree
  "将扁平菜单列表构建为树形结构."
  [items parent-id]
  (let [children (filter #(= (:parent_id %) parent-id) items)]
    (mapv (fn [child]
            (assoc child :children (build-tree items (:menu_id child))))
          children)))


(defn menu-tree
  "获取菜单树."
  [{:keys [query-fn]}]
  (let [menus (query-fn :list-menus {:menu_name nil :status nil :menu_type nil})]
    (build-tree menus 0)))


(defn menu-tree-by-roles
  "根据角色ID列表构建菜单树."
  [{:keys [query-fn]} role-ids]
  (if (seq role-ids)
    (let [menus (query-fn :list-menus-by-role-ids {:role-ids role-ids})]
      (build-tree menus 0))
    []))


(defn menu-tree-for-actor
  "当前身份可见的菜单树: 超级管理员为全部启用菜单; 其他用户为有效角色授权的菜单及其完整祖先链.
  停用菜单 (及其下级) 不出现; 按钮 (F) 保留在树中供前端判断, 侧边栏只渲染目录与菜单."
  [{:keys [query-fn]} actor]
  (let [menus (query-fn :list-menus {:menu_name nil :status "0" :menu_type nil})
        by-id (into {} (map (juxt :menu_id identity)) menus)
        granted (if (:admin? actor)
                  (set (keys by-id))
                  (set (map :menu_id (query-fn :authz-user-menu-ids {:user_id (:user_id actor)}))))
        with-ancestors (reduce (fn [acc id]
                                 (loop [acc acc id id]
                                   (if-let [m (get by-id id)]
                                     (let [acc (conj acc id)]
                                       (if (pos? (or (:parent_id m) 0)) (recur acc (:parent_id m)) acc))
                                     acc)))
                               #{} granted)]
    (build-tree (filter #(contains? with-ancestors (:menu_id %)) menus) 0)))
