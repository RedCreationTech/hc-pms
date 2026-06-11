(ns com.ruoyi.rouyi.frontend.pages.menu
  "菜单管理页面。"
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- menu-columns []
  #js [#js {:title "菜单名称" :dataIndex "menu_name" :key "menu_name" :width 200}
       #js {:title "图标" :dataIndex "icon" :key "icon" :width 80}
       #js {:title "排序" :dataIndex "order_num" :key "order_num" :width 80}
       #js {:title "权限标识" :dataIndex "perms" :key "perms"}
       #js {:title "组件路径" :dataIndex "component" :key "component"}
       #js {:title "状态" :dataIndex "status" :key "status" :width 100
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:color (if (= v "0") "green" "red")}
                         (if (= v "0") "正常" "停用")]))}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 180}
       #js {:title "操作" :key "action" :width 200
            :render (fn [_ _]
                      (r/as-element
                        [antd/space
                         [antd/button {:type "link" :size "small"} "新增"]
                         [antd/button {:type "link" :size "small"} "编辑"]
                         [antd/button {:type "link" :danger true :size "small"} "删除"]]))}])

(defn menu-page []
  (let [items @(rf/subscribe [:menus/items])
        loading? @(rf/subscribe [:menus/loading?])]
    [:div
     [:h3 "菜单管理"]
     [antd/space {:style {:marginBottom 16}}
      [antd/button {:type "primary"} "新增菜单"]
      [antd/button {:type "default"} "展开/折叠"]]
     [antd/table {:rowKey "menu_id"
                  :columns (menu-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination false
                  :defaultExpandAllRows true}]]))
