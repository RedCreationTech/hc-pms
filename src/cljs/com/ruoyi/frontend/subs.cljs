(ns com.ruoyi.frontend.subs
  "re-frame 订阅定义。"
  (:require
   [re-frame.core :as rf]))

(rf/reg-sub :page
            (fn [db _]
              (:page db)))

(rf/reg-sub :auth/token
            (fn [db _]
              (get-in db [:auth :token])))

(rf/reg-sub :auth/user
            (fn [db _]
              (get-in db [:auth :user])))

(rf/reg-sub :auth/loading?
            (fn [db _]
              (get-in db [:auth :loading?])))

(rf/reg-sub :auth/logged-in?
            (fn [db _]
              (boolean (get-in db [:auth :token]))))

(rf/reg-sub :users/items
            (fn [db _]
              (get-in db [:users :items])))

(rf/reg-sub :users/total
            (fn [db _]
              (get-in db [:users :total])))

(rf/reg-sub :users/loading?
            (fn [db _]
              (get-in db [:users :loading?] false)))

(rf/reg-sub :users/query-params
            (fn [db _]
              (get-in db [:users :query-params] {})))

(rf/reg-sub :users/page
            (fn [db _]
              (get-in db [:users :page] 1)))

(rf/reg-sub :users/page-size
            (fn [db _]
              (get-in db [:users :page-size] 10)))

(rf/reg-sub :users/selected-ids
            (fn [db _]
              (get-in db [:users :selected-ids] [])))

(rf/reg-sub :users/selected-empty?
            (fn [db _]
              (empty? (get-in db [:users :selected-ids] []))))

(rf/reg-sub :users/modal-visible?
            (fn [db _]
              (get-in db [:users :modal-visible?] false)))

(rf/reg-sub :users/editing?
            (fn [db _]
              (boolean (get-in db [:users :editing]))))

(rf/reg-sub :users/editing
            (fn [db _]
              (get-in db [:users :editing])))

(rf/reg-sub :users/form-data
            (fn [db _]
              (get-in db [:users :form-data] {})))

(rf/reg-sub :users/role-options
            (fn [db _]
              (get-in db [:users :role-options] [])))

(rf/reg-sub :users/post-options
            (fn [db _]
              (get-in db [:users :post-options] [])))

(rf/reg-sub :users/form-errors
            (fn [db _]
              (get-in db [:users :form-errors] {})))

(rf/reg-sub :users/detail-visible?
            (fn [db _]
              (get-in db [:users :detail-visible?] false)))

(rf/reg-sub :users/detail-data
            (fn [db _]
              (get-in db [:users :detail-data])))

(rf/reg-sub :users/reset-pwd-visible?
            (fn [db _]
              (get-in db [:users :reset-pwd-visible?] false)))

(rf/reg-sub :users/reset-pwd-username
            (fn [db _]
              (get-in db [:users :reset-pwd-username] "")))

(rf/reg-sub :users/reset-pwd-value
            (fn [db _]
              (get-in db [:users :reset-pwd-value] "")))

(rf/reg-sub :users/selected-dept-id
            (fn [db _]
              (or (get-in db [:users :selected-dept-id])
                  (get-in db [:users :query-params :dept_id]))))

(rf/reg-sub :users/show-search?
            (fn [db _]
              (get-in db [:users :show-search?] true)))

(rf/reg-sub :users/columns
            (fn [db _]
              (get-in db [:users :columns])))

(rf/reg-sub :users/import-visible?
            (fn [db _]
              (get-in db [:users :import-visible?] false)))

(rf/reg-sub :users/import-loading?
            (fn [db _]
              (get-in db [:users :import-loading?] false)))

(rf/reg-sub :users/import-file
            (fn [db _]
              (get-in db [:users :import-file] nil)))

(rf/reg-sub :users/import-update-support?
            (fn [db _]
              (get-in db [:users :import-update-support?] false)))

(rf/reg-sub :users/auth-role-visible?
            (fn [db _]
              (get-in db [:users :auth-role-visible?] false)))

(rf/reg-sub :users/auth-role-user
            (fn [db _]
              (get-in db [:users :auth-role-user])))

(rf/reg-sub :users/auth-role-ids
            (fn [db _]
              (get-in db [:users :auth-role-ids] [])))

(rf/reg-sub :online-users/items
            (fn [db _]
              (get-in db [:online-users :items])))

(rf/reg-sub :online-users/total
            (fn [db _]
              (get-in db [:online-users :total])))

(rf/reg-sub :online-users/loading?
            (fn [db _]
              (get-in db [:online-users :loading?])))

(rf/reg-sub :jobs/items
            (fn [db _]
              (get-in db [:jobs :items])))

(rf/reg-sub :jobs/total
            (fn [db _]
              (get-in db [:jobs :total])))

(rf/reg-sub :jobs/loading?
            (fn [db _]
              (get-in db [:jobs :loading?])))

(rf/reg-sub :job-logs/items
            (fn [db _]
              (get-in db [:job-logs :items])))

(rf/reg-sub :job-logs/total
            (fn [db _]
              (get-in db [:job-logs :total])))

(rf/reg-sub :job-logs/loading?
            (fn [db _]
              (get-in db [:job-logs :loading?])))

(rf/reg-sub :profile/data
            (fn [db _]
              (get-in db [:profile :data])))

(rf/reg-sub :profile/loading?
            (fn [db _]
              (get-in db [:profile :loading?])))

(rf/reg-sub :dicts/types
            (fn [db _]
              (get-in db [:dicts :types])))

(rf/reg-sub :dicts/data
            (fn [db _]
              (get-in db [:dicts :data])))

(rf/reg-sub :dicts/loading?
            (fn [db _]
              (get-in db [:dicts :loading?])))

(rf/reg-sub :dicts/selected-type
            (fn [db _]
              (get-in db [:dicts :selected-type] nil)))

(rf/reg-sub :configs/items
            (fn [db _]
              (get-in db [:configs :items])))

(rf/reg-sub :configs/total
            (fn [db _]
              (get-in db [:configs :total])))

(rf/reg-sub :configs/loading?
            (fn [db _]
              (get-in db [:configs :loading?])))

(rf/reg-sub :oper-logs/items
            (fn [db _]
              (get-in db [:oper-logs :items])))

(rf/reg-sub :oper-logs/total
            (fn [db _]
              (get-in db [:oper-logs :total])))

(rf/reg-sub :oper-logs/loading?
            (fn [db _]
              (get-in db [:oper-logs :loading?])))

(rf/reg-sub :oper-logs/detail-visible?
            (fn [db _]
              (get-in db [:oper-logs :detail-visible?] false)))

(rf/reg-sub :oper-logs/detail-data
            (fn [db _]
              (get-in db [:oper-logs :detail-data])))

(rf/reg-sub :login-logs/items
            (fn [db _]
              (get-in db [:login-logs :items])))

(rf/reg-sub :login-logs/total
            (fn [db _]
              (get-in db [:login-logs :total])))

(rf/reg-sub :login-logs/loading?
            (fn [db _]
              (get-in db [:login-logs :loading?])))

(rf/reg-sub :notification
            (fn [db _]
              (:notification db)))

(rf/reg-sub :theme/mode
            (fn [db _]
              (get-in db [:theme :mode] :light)))

(rf/reg-sub :theme/primary-color
            (fn [db _]
              (get-in db [:theme :primary-color] "#1677ff")))

(rf/reg-sub :theme/compact?
            (fn [db _]
              (get-in db [:theme :compact?] false)))

(rf/reg-sub :theme/algorithm
            (fn [db _]
              (get-in db [:theme :algorithm] "default")))

(rf/reg-sub :theme/component-size
            (fn [db _]
              (get-in db [:theme :component-size] "middle")))

(rf/reg-sub :theme/font-size
            (fn [db _]
              (get-in db [:theme :font-size] "middle")))

(rf/reg-sub :layout/settings
            (fn [db _]
              (:layout-settings db)))

(rf/reg-sub :layout/setting
            (fn [db [_ k]]
              (get-in db [:layout-settings k])))

;; ─── Tabs ──────────────────────────────────────────────────────────

(rf/reg-sub :tabs/items
            (fn [db _]
              (get-in db [:tabs :items] [])))

(rf/reg-sub :tabs/active
            (fn [db _]
              (get-in db [:tabs :active] :dashboard)))

;; ─── 角色管理 ──────────────────────────────────────────────────────

(rf/reg-sub :roles/items
            (fn [db _]
              (get-in db [:roles :items])))

(rf/reg-sub :roles/total
            (fn [db _]
              (get-in db [:roles :total])))

(rf/reg-sub :roles/loading?
            (fn [db _]
              (get-in db [:roles :loading?] false)))

(rf/reg-sub :roles/query-params
            (fn [db _]
              (get-in db [:roles :query-params] {})))

(rf/reg-sub :roles/modal-visible?
            (fn [db _]
              (get-in db [:roles :modal-visible?] false)))

(rf/reg-sub :roles/editing?
            (fn [db _]
              (boolean (get-in db [:roles :editing]))))

(rf/reg-sub :roles/editing
            (fn [db _]
              (get-in db [:roles :editing])))

(rf/reg-sub :roles/form-data
            (fn [db _]
              (get-in db [:roles :form-data] {})))

(rf/reg-sub :roles/permission-visible?
            (fn [db _]
              (get-in db [:roles :permission-visible?] false)))

(rf/reg-sub :roles/permission-role
            (fn [db _]
              (get-in db [:roles :permission-role])))

(rf/reg-sub :roles/menu-tree
            (fn [db _]
              (get-in db [:roles :menu-tree])))

(rf/reg-sub :roles/checked-keys
            (fn [db _]
              (get-in db [:roles :checked-keys] [])))

(rf/reg-sub :roles/data-scope-visible?
            (fn [db _]
              (get-in db [:roles :data-scope-visible?] false)))

(rf/reg-sub :roles/data-scope-role
            (fn [db _]
              (get-in db [:roles :data-scope-role] {})))

(rf/reg-sub :roles/data-scope
            (fn [db _]
              (get-in db [:roles :data-scope] "1")))

(rf/reg-sub :roles/data-scope-dept-tree
            (fn [db _]
              (get-in db [:roles :data-scope-dept-tree] [])))

(rf/reg-sub :roles/data-scope-checked-keys
            (fn [db _]
              (get-in db [:roles :data-scope-checked-keys] [])))

(rf/reg-sub :roles/user-alloc-visible?
            (fn [db _]
              (get-in db [:roles :user-alloc-visible?] false)))

(rf/reg-sub :roles/user-alloc-role
            (fn [db _]
              (get-in db [:roles :user-alloc-role] {})))

(rf/reg-sub :roles/user-alloc-active-tab
            (fn [db _]
              (get-in db [:roles :user-alloc-active-tab] "allocated")))

(rf/reg-sub :roles/allocated-items
            (fn [db _]
              (get-in db [:roles :allocated-items] [])))

(rf/reg-sub :roles/allocated-total
            (fn [db _]
              (get-in db [:roles :allocated-total] 0)))

(rf/reg-sub :roles/allocated-loading?
            (fn [db _]
              (get-in db [:roles :allocated-loading?] false)))

(rf/reg-sub :roles/allocated-query
            (fn [db _]
              (get-in db [:roles :allocated-query] {})))

(rf/reg-sub :roles/allocated-selected
            (fn [db _]
              (get-in db [:roles :allocated-selected] [])))

(rf/reg-sub :roles/unallocated-items
            (fn [db _]
              (get-in db [:roles :unallocated-items] [])))

(rf/reg-sub :roles/unallocated-total
            (fn [db _]
              (get-in db [:roles :unallocated-total] 0)))

(rf/reg-sub :roles/unallocated-loading?
            (fn [db _]
              (get-in db [:roles :unallocated-loading?] false)))

(rf/reg-sub :roles/unallocated-query
            (fn [db _]
              (get-in db [:roles :unallocated-query] {})))

(rf/reg-sub :roles/unallocated-selected
            (fn [db _]
              (get-in db [:roles :unallocated-selected] [])))

;; ─── 菜单管理 ──────────────────────────────────────────────────────

(rf/reg-sub :menus/items
            (fn [db _]
              (get-in db [:menus :items])))

(rf/reg-sub :menus/loading?
            (fn [db _]
              (get-in db [:menus :loading?] false)))

(rf/reg-sub :menus/modal-visible?
            (fn [db _]
              (get-in db [:menus :modal-visible?] false)))

(rf/reg-sub :menus/editing?
            (fn [db _]
              (boolean (get-in db [:menus :editing]))))

(rf/reg-sub :menus/editing
            (fn [db _]
              (get-in db [:menus :editing])))

(rf/reg-sub :menus/form-data
            (fn [db _]
              (get-in db [:menus :form-data] {})))

(rf/reg-sub :menus/tree-data
            (fn [db _]
              (get-in db [:menus :tree-data] [])))

;; ─── 部门管理 ──────────────────────────────────────────────────────

(rf/reg-sub :depts/items
            (fn [db _]
              (get-in db [:depts :items])))

(rf/reg-sub :depts/loading?
            (fn [db _]
              (get-in db [:depts :loading?] false)))

(rf/reg-sub :depts/tree
            (fn [db _]
              (get-in db [:depts :tree] [])))

(rf/reg-sub :depts/expanded-keys
            (fn [db _]
              (get-in db [:depts :expanded-keys] [])))

(rf/reg-sub :depts/modal-visible?
            (fn [db _]
              (get-in db [:depts :modal-visible?] false)))

(rf/reg-sub :depts/editing?
            (fn [db _]
              (boolean (get-in db [:depts :editing]))))

(rf/reg-sub :depts/editing
            (fn [db _]
              (get-in db [:depts :editing])))

(rf/reg-sub :depts/form-data
            (fn [db _]
              (get-in db [:depts :form-data] {})))

;; ─── 岗位管理 ──────────────────────────────────────────────────────

(rf/reg-sub :posts/items
            (fn [db _]
              (get-in db [:posts :items])))

(rf/reg-sub :posts/total
            (fn [db _]
              (get-in db [:posts :total])))

(rf/reg-sub :posts/loading?
            (fn [db _]
              (get-in db [:posts :loading?] false)))

(rf/reg-sub :posts/query-params
            (fn [db _]
              (get-in db [:posts :query-params] {})))

(rf/reg-sub :posts/modal-visible?
            (fn [db _]
              (get-in db [:posts :modal-visible?] false)))

(rf/reg-sub :posts/editing?
            (fn [db _]
              (boolean (get-in db [:posts :editing]))))

(rf/reg-sub :posts/editing
            (fn [db _]
              (get-in db [:posts :editing])))

(rf/reg-sub :posts/form-data
            (fn [db _]
              (get-in db [:posts :form-data] {})))

;; ─── 首页仪表盘 ──────────────────────────────────────────────────────

(rf/reg-sub :dashboard/stats
            (fn [db _]
              (get-in db [:dashboard :stats])))

(rf/reg-sub :dashboard/loading?
            (fn [db _]
              (get-in db [:dashboard :loading?] false)))

;; ─── 服务器监控 ──────────────────────────────────────────────────────

(rf/reg-sub :server/data
            (fn [db _]
              (get-in db [:server :data])))

(rf/reg-sub :server/loading?
            (fn [db _]
              (get-in db [:server :loading?] false)))

(rf/reg-sub :server/datasource
            (fn [db _]
              (get-in db [:server :datasource])))

(rf/reg-sub :server/datasource-loading?
            (fn [db _]
              (get-in db [:server :datasource-loading?] false)))

(rf/reg-sub :integrant/data
            (fn [db _]
              (get-in db [:integrant :data])))

(rf/reg-sub :integrant/trace
            (fn [db [_ key]]
              (get-in db [:integrant :trace key])))

(rf/reg-sub :integrant/traces
            (fn [db _]
              (get-in db [:integrant :trace] {})))

;; ─── 缓存监控 ──────────────────────────────────────────────────────

(rf/reg-sub :cache/data
            (fn [db _]
              (get-in db [:cache :data])))

(rf/reg-sub :cache/names
            (fn [db _]
              (get-in db [:cache :names] [])))

(rf/reg-sub :cache/selected-name
            (fn [db _]
              (get-in db [:cache :selected-name])))

(rf/reg-sub :cache/keys
            (fn [db _]
              (get-in db [:cache :keys] [])))

(rf/reg-sub :cache/value
            (fn [db _]
              (get-in db [:cache :value])))

(rf/reg-sub :cache/value-visible?
            (fn [db _]
              (get-in db [:cache :value-visible?] false)))

(rf/reg-sub :cache/loading?
            (fn [db _]
              (get-in db [:cache :loading?] false)))

;; ─── 通知公告 ──────────────────────────────────────────────────────

(rf/reg-sub :notices/items
            (fn [db _]
              (get-in db [:notices :items])))

(rf/reg-sub :notices/total
            (fn [db _]
              (get-in db [:notices :total])))

(rf/reg-sub :notices/loading?
            (fn [db _]
              (get-in db [:notices :loading?] false)))

(rf/reg-sub :notices/modal-visible?
            (fn [db _]
              (get-in db [:notices :modal-visible?] false)))

(rf/reg-sub :notices/editing?
            (fn [db _]
              (boolean (get-in db [:notices :editing]))))

(rf/reg-sub :notices/editing
            (fn [db _]
              (get-in db [:notices :editing])))

(rf/reg-sub :notices/form-data
            (fn [db _]
              (get-in db [:notices :form-data] {})))

(rf/reg-sub :users/expanded-dept-ids
            (fn [db _]
              (get-in db [:users :expanded-dept-ids] #{})))



;; ─── 办公：请假 ──────────────────────────────────────────────────────
(rf/reg-sub :leave/items (fn [db _] (get-in db [:leave :items] [])))
(rf/reg-sub :leave/total (fn [db _] (get-in db [:leave :total] 0)))
(rf/reg-sub :leave/loading? (fn [db _] (get-in db [:leave :loading?] false)))
(rf/reg-sub :leave/modal-visible? (fn [db _] (get-in db [:leave :modal-visible?] false)))
(rf/reg-sub :leave/submitting? (fn [db _] (get-in db [:leave :submitting?] false)))

;; ─── 办公：BPM 待办 ──────────────────────────────────────────────────
(rf/reg-sub :bpm-todo/items (fn [db _] (get-in db [:bpm-todo :items] [])))
(rf/reg-sub :bpm-todo/total (fn [db _] (get-in db [:bpm-todo :total] 0)))
(rf/reg-sub :bpm-todo/loading? (fn [db _] (get-in db [:bpm-todo :loading?] false)))
(rf/reg-sub :bpm-todo/modal-visible? (fn [db _] (get-in db [:bpm-todo :modal-visible?] false)))
(rf/reg-sub :bpm-todo/current (fn [db _] (get-in db [:bpm-todo :current] nil)))
(rf/reg-sub :bpm-todo/action (fn [db _] (get-in db [:bpm-todo :action] nil)))
(rf/reg-sub :bpm-todo/submitting? (fn [db _] (get-in db [:bpm-todo :submitting?] false)))

;; ─── 办公：BPM 已办 ──────────────────────────────────────────────────
(rf/reg-sub :bpm-done/items (fn [db _] (get-in db [:bpm-done :items] [])))
(rf/reg-sub :bpm-done/total (fn [db _] (get-in db [:bpm-done :total] 0)))
(rf/reg-sub :bpm-done/loading? (fn [db _] (get-in db [:bpm-done :loading?] false)))

;; ─── 办公：我的流程 ──────────────────────────────────────────────────
(rf/reg-sub :bpm-instance/items (fn [db _] (get-in db [:bpm-instance :items] [])))
(rf/reg-sub :bpm-instance/total (fn [db _] (get-in db [:bpm-instance :total] 0)))
(rf/reg-sub :bpm-instance/loading? (fn [db _] (get-in db [:bpm-instance :loading?] false)))

;; ─── 办公：流程模型 ──────────────────────────────────────────────────
(rf/reg-sub :bpm-model/items (fn [db _] (get-in db [:bpm-model :items] [])))
(rf/reg-sub :bpm-model/total (fn [db _] (get-in db [:bpm-model :total] 0)))
(rf/reg-sub :bpm-model/loading? (fn [db _] (get-in db [:bpm-model :loading?] false)))
