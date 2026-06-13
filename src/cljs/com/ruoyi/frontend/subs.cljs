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
              (get-in db [:users :query-params :dept_id])))

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

(rf/reg-sub :gen/tables (fn [db _] (get-in db [:gen :tables])))
(rf/reg-sub :gen/tables-loading? (fn [db _] (get-in db [:gen :tables-loading?] false)))
(rf/reg-sub :gen/selected-tables (fn [db _] (get-in db [:gen :selected-tables] [])))
(rf/reg-sub :gen/preview-data (fn [db _] (get-in db [:gen :preview-data])))
(rf/reg-sub :gen/preview-loading? (fn [db _] (get-in db [:gen :preview-loading?] false)))
(rf/reg-sub :gen/preview-visible? (fn [db _] (get-in db [:gen :preview-visible?] false)))
(rf/reg-sub :file/items (fn [db _] (get-in db [:file :items] [])))
(rf/reg-sub :file/loading? (fn [db _] (get-in db [:file :loading?] false)))

(rf/reg-sub :fb/items (fn [db _] (get-in db [:fb :items] [])))
(rf/reg-sub :fb/selected-id (fn [db _] (get-in db [:fb :selected-id])))
(rf/reg-sub :fb/code-visible? (fn [db _] (get-in db [:fb :code-visible?] false)))

(rf/reg-sub :gen/preview-table-name (fn [db _] (get-in db [:gen :preview-table-name])))
(rf/reg-sub :gen/config-visible? (fn [db _] (get-in db [:gen :config-visible?] false)))
(rf/reg-sub :gen/config (fn [db _] (get-in db [:gen :config] {:package-path "com.ruoyi" :module-name "system" :author "ruoyi" :table-prefix "sys_"})))

(rf/reg-sub :posts/form-data
            (fn [db _]
              (get-in db [:posts :form-data] {})))

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
