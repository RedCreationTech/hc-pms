(ns com.ruoyi.rouyi.frontend.events
  "re-frame 事件处理器。"
  (:require
   [re-frame.core :as rf]
   [com.ruoyi.rouyi.frontend.db :as db]
   [com.ruoyi.rouyi.frontend.api :as api]))

(rf/reg-event-db :initialize-db
                 (fn [_ _]
                   db/default-db))

(rf/reg-event-fx :navigate
                 (fn [{:keys [db]} [_ page]]
                   (let [fetch (case page
                                 :user [:users/fetch {}]
                                 :dict [:dicts/fetch-types {}]
                                 :config [:configs/fetch {}]
                                 :oper-log [:oper-logs/fetch {}]
                                 :login-log [:login-logs/fetch {}]
                                 :online [:online-users/fetch {}]
                                 :job [:jobs/fetch {}]
                                 :role [:roles/fetch {}]
                                 :menu [:menus/fetch]
                                 :dept [:depts/fetch {}]
                                 :post [:posts/fetch {}]
                                 nil)]
                     (if fetch
                       {:db (assoc db :page page)
                        :dispatch fetch}
                       {:db (assoc db :page page)}))))

(rf/reg-event-db :auth/set-token
                 (fn [db [_ token]]
                   (assoc-in db [:auth :token] token)))

(rf/reg-event-fx :auth/set-user
                 (fn [{:keys [db]} [_ user]]
                   {:db       (assoc-in db [:auth :user] user)
                    :dispatch [:navigate :dashboard]}))

(rf/reg-event-db :auth/set-loading
                 (fn [db [_ loading?]]
                   (assoc-in db [:auth :loading?] loading?)))

(rf/reg-event-fx :auth/login
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:auth :loading?] true)
                    :api/login params}))

(rf/reg-fx :api/login
           (fn [params]
             (api/login params
                        (fn [result]
                          (when (= 200 (:code result))
                            (rf/dispatch [:auth/login-success (:data result)]))
                          (when (not= 200 (:code result))
                            (rf/dispatch [:auth/login-failure (:msg result)])))
                        (fn [_]
                          (rf/dispatch [:auth/login-failure "网络错误"])))))

(rf/reg-event-fx :auth/login-success
                 (fn [{:keys [db]} [_ data]]
                   {:db (-> db
                            (assoc-in [:auth :token] (:token data))
                            (assoc-in [:auth :loading?] false))
                    :dispatch [:navigate :dashboard]}))

(rf/reg-event-db :auth/login-failure
                 (fn [db [_ msg]]
                   (-> db
                       (assoc-in [:auth :loading?] false)
                       (assoc :notification {:type :error :message msg}))))

(rf/reg-event-fx :auth/fetch-info
                 (fn [{:keys [db]} _]
                   {:db db
                    :api/get-info nil}))

(rf/reg-fx :api/get-info
           (fn [_]
             (api/get-info
              (fn [result]
                (when (= 200 (:code result))
                  (rf/dispatch [:auth/set-user (:data result)])))
              (fn [_]))))

(rf/reg-event-db :theme/toggle-mode
                 (fn [db _]
                   (update-in db [:theme :mode] #(if (= % :light) :dark :light))))

(rf/reg-event-db :users/set-list
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:users :items] (:rows data))
                       (assoc-in [:users :total] (:total data))
                       (assoc-in [:users :loading?] false))))

(rf/reg-event-fx :users/fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:users :loading?] true)
                    :api/list-users params}))

(rf/reg-event-db :dicts/set-types
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:dicts :types] (:rows data))
                       (assoc-in [:dicts :loading?] false))))

(rf/reg-event-fx :dicts/fetch-types
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:dicts :loading?] true)
                    :api/list-dict-types params}))

(rf/reg-fx :api/list-dict-types
           (fn [params]
             (api/list-dict-types params
                                  (fn [result]
                                    (when (= 200 (:code result))
                                      (rf/dispatch [:dicts/set-types (:data result)])))
                                  (fn [_]))))

(rf/reg-event-db :dicts/set-data
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:dicts :data] (:rows data))
                       (assoc-in [:dicts :loading?] false))))

(rf/reg-event-fx :dicts/fetch-data
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:dicts :loading?] true)
                    :api/list-dict-data params}))

(rf/reg-fx :api/list-dict-data
           (fn [params]
             (api/list-dict-data params
                                 (fn [result]
                                   (when (= 200 (:code result))
                                     (rf/dispatch [:dicts/set-data (:data result)])))
                                 (fn [_]))))

(rf/reg-event-db :configs/set-list
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:configs :items] (:rows data))
                       (assoc-in [:configs :total] (:total data))
                       (assoc-in [:configs :loading?] false))))

(rf/reg-event-fx :configs/fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:configs :loading?] true)
                    :api/list-configs params}))

(rf/reg-fx :api/list-configs
           (fn [params]
             (api/list-configs params
                               (fn [result]
                                 (when (= 200 (:code result))
                                   (rf/dispatch [:configs/set-list (:data result)])))
                               (fn [_]))))

(rf/reg-event-fx :configs/create
                 (fn [{:keys [db]} [_ params]]
                   {:db db
                    :api/create-config params}))

(rf/reg-fx :api/create-config
           (fn [params]
             (api/create-config params
                                (fn [result]
                                  (when (= 200 (:code result))
                                    (rf/dispatch [:configs/created])
                                    (.success js/antd.message "创建成功"))
                                  (when (not= 200 (:code result))
                                    (.error js/antd.message (:msg result))))
                                (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :configs/created
                 (fn [{:keys [db]} _]
                   {:db (assoc-in db [:notification] nil)
                    :dispatch [:configs/fetch {}]}))

(rf/reg-event-fx :configs/update
                 (fn [{:keys [db]} [_ id params]]
                   {:db db
                    :api/update-config [id params]}))

(rf/reg-fx :api/update-config
           (fn [[id params]]
             (api/update-config id params
                                (fn [result]
                                  (when (= 200 (:code result))
                                    (rf/dispatch [:configs/updated])
                                    (.success js/antd.message "更新成功"))
                                  (when (not= 200 (:code result))
                                    (.error js/antd.message (:msg result))))
                                (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :configs/updated
                 (fn [{:keys [db]} _]
                   {:db db
                    :dispatch [:configs/fetch {}]}))

(rf/reg-event-fx :configs/delete
                 (fn [{:keys [db]} [_ id]]
                   {:db db
                    :api/delete-config id}))

(rf/reg-fx :api/delete-config
           (fn [id]
             (api/delete-config id
                                (fn [result]
                                  (when (= 200 (:code result))
                                    (rf/dispatch [:configs/deleted])
                                    (.success js/antd.message "删除成功"))
                                  (when (not= 200 (:code result))
                                    (.error js/antd.message (:msg result))))
                                (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :configs/deleted
                 (fn [{:keys [db]} _]
                   {:db db
                    :dispatch [:configs/fetch {}]}))

(rf/reg-event-db :oper-logs/set-list
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:oper-logs :items] (:rows data))
                       (assoc-in [:oper-logs :total] (:total data))
                       (assoc-in [:oper-logs :loading?] false))))

(rf/reg-event-fx :oper-logs/fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:oper-logs :loading?] true)
                    :api/list-oper-logs params}))

(rf/reg-fx :api/list-oper-logs
           (fn [params]
             (api/list-oper-logs params
                                 (fn [result]
                                   (when (= 200 (:code result))
                                     (rf/dispatch [:oper-logs/set-list (:data result)])))
                                 (fn [_]))))

(rf/reg-event-fx :oper-logs/clear
                 (fn [{:keys [db]} _]
                   {:db db
                    :api/clear-oper-logs nil}))

(rf/reg-fx :api/clear-oper-logs
           (fn [_]
             (api/clear-oper-logs
              (fn [result]
                (when (= 200 (:code result))
                  (rf/dispatch [:oper-logs/cleared])
                  (.success js/antd.message "清空成功")))
              (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :oper-logs/cleared
                 (fn [{:keys [db]} _]
                   {:db db
                    :dispatch [:oper-logs/fetch {}]}))

(rf/reg-event-db :login-logs/set-list
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:login-logs :items] (:rows data))
                       (assoc-in [:login-logs :total] (:total data))
                       (assoc-in [:login-logs :loading?] false))))

(rf/reg-event-fx :login-logs/fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:login-logs :loading?] true)
                    :api/list-login-logs params}))

(rf/reg-fx :api/list-login-logs
           (fn [params]
             (api/list-login-logs params
                                  (fn [result]
                                    (when (= 200 (:code result))
                                      (rf/dispatch [:login-logs/set-list (:data result)])))
                                  (fn [_]))))

(rf/reg-event-fx :login-logs/clear
                 (fn [{:keys [db]} _]
                   {:db db
                    :api/clear-login-logs nil}))

(rf/reg-fx :api/clear-login-logs
           (fn [_]
             (api/clear-login-logs
              (fn [result]
                (when (= 200 (:code result))
                  (rf/dispatch [:login-logs/cleared])
                  (.success js/antd.message "清空成功")))
              (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :login-logs/cleared
                 (fn [{:keys [db]} _]
                   {:db db
                    :dispatch [:login-logs/fetch {}]}))

(rf/reg-fx :api/list-users
           (fn [params]
             (api/list-users params
                             (fn [result]
                               (when (= 200 (:code result))
                                 (rf/dispatch [:users/set-list (:data result)])))
                             (fn [_]))))

;; ────── 在线用户 ──────

(rf/reg-event-db :online-users/set-list
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:online-users :items] (:rows data))
                       (assoc-in [:online-users :total] (:total data))
                       (assoc-in [:online-users :loading?] false))))

(rf/reg-event-fx :online-users/fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:online-users :loading?] true)
                    :api/list-online-users params}))

(rf/reg-fx :api/list-online-users
           (fn [params]
             (api/list-online-users params
                                    (fn [result]
                                      (when (= 200 (:code result))
                                        (rf/dispatch [:online-users/set-list (:data result)])))
                                    (fn [_]))))

(rf/reg-event-fx :online-users/force-logout
                 (fn [_ [_ token-id]]
                   {:api/force-logout token-id}))

(rf/reg-fx :api/force-logout
           (fn [token-id]
             (api/force-logout token-id
                               (fn [result]
                                 (when (= 200 (:code result))
                                   (rf/dispatch [:online-users/fetch {}])))
                               (fn [_]))))

;; ────── 定时任务 ──────

(rf/reg-event-db :jobs/set-list
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:jobs :items] (:rows data))
                       (assoc-in [:jobs :total] (:total data))
                       (assoc-in [:jobs :loading?] false))))

(rf/reg-event-fx :jobs/fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:jobs :loading?] true)
                    :api/list-jobs params}))

(rf/reg-fx :api/list-jobs
           (fn [params]
             (api/list-jobs params
                            (fn [result]
                              (when (= 200 (:code result))
                                (rf/dispatch [:jobs/set-list (:data result)])))
                            (fn [_]))))

(rf/reg-event-fx :jobs/create
                 (fn [_ [_ params]]
                   {:api/create-job params}))

(rf/reg-fx :api/create-job
           (fn [params]
             (api/create-job params
                             (fn [result]
                               (when (= 200 (:code result))
                                 (rf/dispatch [:jobs/fetch {}])))
                             (fn [_]))))

(rf/reg-event-fx :jobs/update
                 (fn [_ [_ id params]]
                   {:api/update-job [id params]}))

(rf/reg-fx :api/update-job
           (fn [[id params]]
             (api/update-job id params
                             (fn [result]
                               (when (= 200 (:code result))
                                 (rf/dispatch [:jobs/fetch {}])))
                             (fn [_]))))

(rf/reg-event-fx :jobs/delete
                 (fn [_ [_ id]]
                   {:api/delete-job id}))

(rf/reg-fx :api/delete-job
           (fn [id]
             (api/delete-job id
                             (fn [result]
                               (when (= 200 (:code result))
                                 (rf/dispatch [:jobs/fetch {}])))
                             (fn [_]))))

;; ────── 任务日志 ──────

(rf/reg-event-db :job-logs/set-list
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:job-logs :items] (:rows data))
                       (assoc-in [:job-logs :total] (:total data))
                       (assoc-in [:job-logs :loading?] false))))

(rf/reg-event-fx :job-logs/fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:job-logs :loading?] true)
                    :api/list-job-logs params}))

(rf/reg-fx :api/list-job-logs
           (fn [params]
             (api/list-job-logs params
                                (fn [result]
                                  (when (= 200 (:code result))
                                    (rf/dispatch [:job-logs/set-list (:data result)])))
                                (fn [_]))))

;; ────── 个人中心 ──────

(rf/reg-event-db :profile/set-data
                 (fn [db [_ data]]
                   (assoc-in db [:profile :data] data)))

(rf/reg-event-db :profile/set-loading
                 (fn [db [_ loading?]]
                   (assoc-in db [:profile :loading?] loading?)))

(rf/reg-event-fx :profile/fetch
                 (fn [{:keys [db]} _]
                   {:db (assoc-in db [:profile :loading?] true)
                    :api/get-profile nil}))

(rf/reg-fx :api/get-profile
           (fn [_]
             (api/get-profile
              (fn [result]
                (when (= 200 (:code result))
                  (rf/dispatch [:profile/set-data (:data result)])))
              (fn [_]))))

(rf/reg-event-fx :profile/update
                 (fn [_ [_ params]]
                   {:api/update-profile params}))

(rf/reg-fx :api/update-profile
           (fn [params]
             (api/update-profile params
                                 (fn [result]
                                   (when (= 200 (:code result))
                                     (js/alert "更新成功")
                                     (rf/dispatch [:profile/fetch])))
                                 (fn [_]))))

(rf/reg-event-fx :profile/change-password
                 (fn [_ [_ params]]
                   {:api/change-password params}))

(rf/reg-fx :api/change-password
           (fn [params]
             (api/change-password params
                                  (fn [result]
                                    (when (= 200 (:code result))
                                      (js/alert "密码修改成功"))
                                    (when (not= 200 (:code result))
                                      (js/alert (:msg result))))
                                  (fn [_]))))

;; ────── 角色管理 ──────

(rf/reg-event-db :roles/update-query
                 (fn [db [_ k v]]
                   (assoc-in db [:roles :query-params k] v)))

(rf/reg-event-db :roles/reset-query
                 (fn [db _]
                   (assoc-in db [:roles :query-params] {})))

(rf/reg-event-db :roles/set-list
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:roles :items] (:rows data))
                       (assoc-in [:roles :total] (:total data))
                       (assoc-in [:roles :loading?] false))))

(rf/reg-event-fx :roles/fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:roles :loading?] true)
                    :api/list-roles params}))

(rf/reg-fx :api/list-roles
           (fn [params]
             (api/list-roles params
                             (fn [result]
                               (when (= 200 (:code result))
                                 (rf/dispatch [:roles/set-list (:data result)])))
                             (fn [_]))))

(rf/reg-event-db :roles/open-modal
                 (fn [db _]
                   (-> db
                       (assoc-in [:roles :modal-visible?] true)
                       (assoc-in [:roles :editing?] false)
                       (assoc-in [:roles :form-data] {:role_sort 0 :status "0" :data_scope "1"}))))

(rf/reg-event-db :roles/close-modal
                 (fn [db _]
                   (assoc-in db [:roles :modal-visible?] false)))

(rf/reg-event-db :roles/update-form
                 (fn [db [_ k v]]
                   (assoc-in db [:roles :form-data k] v)))

(rf/reg-event-db :roles/edit
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:roles :modal-visible?] true)
                       (assoc-in [:roles :editing?] true)
                       (assoc-in [:roles :form-data] data))))

(rf/reg-event-fx :roles/submit
                 (fn [{:keys [db]} _]
                   (let [data (get-in db [:roles :form-data])
                         editing? (get-in db [:roles :editing?])]
                     (if editing?
                       {:db (assoc-in db [:roles :modal-visible?] false)
                        :api/update-role [(:role_id data) data]}
                       {:db (assoc-in db [:roles :modal-visible?] false)
                        :api/create-role data}))))

(rf/reg-fx :api/create-role
           (fn [params]
             (api/create-role params
                              (fn [result]
                                (when (= 200 (:code result))
                                  (.success js/antd.message "创建成功")
                                  (rf/dispatch [:roles/fetch {}])))
                              (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-fx :api/update-role
           (fn [[id params]]
             (api/update-role id params
                              (fn [result]
                                (when (= 200 (:code result))
                                  (.success js/antd.message "更新成功")
                                  (rf/dispatch [:roles/fetch {}])))
                              (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :roles/delete
                 (fn [_ [_ id]]
                   {:api/delete-role id}))

(rf/reg-fx :api/delete-role
           (fn [id]
             (api/delete-role id
                              (fn [result]
                                (when (= 200 (:code result))
                                  (.success js/antd.message "删除成功")
                                  (rf/dispatch [:roles/fetch {}])))
                              (fn [_] (.error js/antd.message "网络错误")))))

;; ────── 角色菜单权限 ──────

(rf/reg-event-db :roles/open-permission
                 (fn [db [_ role]]
                   (-> db
                       (assoc-in [:roles :permission-visible?] true)
                       (assoc-in [:roles :permission-role] role))))

(rf/reg-event-db :roles/close-permission
                 (fn [db _]
                   (assoc-in db [:roles :permission-visible?] false)))

(rf/reg-event-fx :roles/fetch-menu-tree
                 (fn [{:keys [db]} _]
                   {:db db
                    :api/menu-tree nil}))

(rf/reg-fx :api/menu-tree
           (fn [_]
             (api/menu-tree
              (fn [result]
                (when (= 200 (:code result))
                  (rf/dispatch [:roles/set-menu-tree (:data result)])))
              (fn [_]))))

(rf/reg-event-db :roles/set-menu-tree
                 (fn [db [_ data]]
                   (assoc-in db [:roles :menu-tree] data)))

(rf/reg-event-fx :users/open-import
                 (fn [_ _]
                   (let [input (.createElement js/document "input")]
                     (set! (.-type input) "file")
                     (set! (.-accept input) ".csv")
                     (set! (.-onchange input) (fn [e] (when-let [file (-> e .-target .-files (aget 0))] (rf/dispatch [:users/import file]))))
                     (.click input))
                   {}))

(rf/reg-event-fx :users/import
                 (fn [{:keys [db]} [_ file]]
                   {:db db :api/import-users file}))

(rf/reg-fx :api/import-users
           (fn [file]
             (api/import-users-csv file
                                   (fn [r] (when (= 200 (:code r)) (.success js/antd.message (str "成功导入 " (:imported (:data r)) " 个用户")) (rf/dispatch [:users/fetch {}])))
                                   (fn [_] (.error js/antd.message "导入失败")))))

(rf/reg-event-fx :users/export
                 (fn [{:keys [db]} _]
                   {:db db :api/export-users nil}))

(rf/reg-fx :api/export-users
           (fn [_]
             (api/export-users-csv
              (fn [csv-data]
                (let [blob (js/Blob. #js [csv-data] #js {:type "text/csv;charset=utf-8"})
                      url (js/URL.createObjectURL blob)
                      link (.createElement js/document "a")]
                  (set! (.-href link) url)
                  (.setAttribute link "download" "users_export.csv")
                  (.appendChild js/document.body link)
                  (.click link)
                  (.removeChild js/document.body link)
                  (js/URL.revokeObjectURL url)))
              (fn [_] (.error js/antd.message "导出失败")))))

(rf/reg-fx :api/upload-avatar
           (fn [form-data]
             (api/upload-avatar form-data
                                (fn [result]
                                  (when (= 200 (:code result))
                                    (rf/dispatch [:profile/fetch])))
                                (fn [_]))))

(rf/reg-event-db :roles/set-checked-keys
                 (fn [db [_ keys]]
                   (assoc-in db [:roles :checked-keys] keys)))

(rf/reg-event-fx :roles/save-permission
                 (fn [{:keys [db]} _]
                   (let [role-id (get-in db [:roles :permission-role :role_id])
                         menu-ids (get-in db [:roles :checked-keys] [])]
                     {:db (assoc-in db [:roles :permission-visible?] false)
                      :api/update-role [role-id {:role_id role-id :menu-ids (vec menu-ids)}]})))

;; ────── 部门管理 ──────

(rf/reg-event-db :depts/set-list
                 (fn [db [_ data]]
                   (assoc-in db [:depts :items] (:rows data))))

(rf/reg-event-fx :depts/fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:depts :loading?] true)
                    :api/list-depts params}))

(rf/reg-fx :api/list-depts
           (fn [params]
             (api/list-depts params
                             (fn [result]
                               (when (= 200 (:code result))
                                 (rf/dispatch [:depts/set-list (:data result)])))
                             (fn [_]))))

(rf/reg-event-db :depts/open-modal
                 (fn [db _]
                   (-> db (assoc-in [:depts :modal-visible?] true) (assoc-in [:depts :editing?] false)
                       (assoc-in [:depts :form-data] {:order_num 0 :status "0"}))))

(rf/reg-event-db :depts/close-modal
                 (fn [db _] (assoc-in db [:depts :modal-visible?] false)))

(rf/reg-event-db :depts/update-form
                 (fn [db [_ k v]] (assoc-in db [:depts :form-data k] v)))

(rf/reg-event-db :depts/edit
                 (fn [db [_ data]]
                   (-> db (assoc-in [:depts :modal-visible?] true) (assoc-in [:depts :editing?] true)
                       (assoc-in [:depts :form-data] data))))

(rf/reg-event-fx :depts/submit
                 (fn [{:keys [db]} _]
                   (let [data (get-in db [:depts :form-data]) editing? (get-in db [:depts :editing?])]
                     (if editing?
                       {:db (assoc-in db [:depts :modal-visible?] false) :api/update-dept [(:dept_id data) data]}
                       {:db (assoc-in db [:depts :modal-visible?] false) :api/create-dept data}))))

(rf/reg-fx :api/create-dept
           (fn [params]
             (api/create-dept params (fn [r] (when (= 200 (:code r)) (.success js/antd.message "创建成功") (rf/dispatch [:depts/fetch {}]))) (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-fx :api/update-dept
           (fn [[id params]]
             (api/update-dept id params (fn [r] (when (= 200 (:code r)) (.success js/antd.message "更新成功") (rf/dispatch [:depts/fetch {}]))) (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :depts/delete
                 (fn [_ [_ id]] {:api/delete-dept id}))

(rf/reg-fx :api/delete-dept
           (fn [id]
             (api/delete-dept id (fn [r] (when (= 200 (:code r)) (.success js/antd.message "删除成功") (rf/dispatch [:depts/fetch {}]))) (fn [_] (.error js/antd.message "网络错误")))))

;; ────── 岗位管理 ──────

(rf/reg-event-db :posts/set-list
                 (fn [db [_ data]]
                   (-> db (assoc-in [:posts :items] (:rows data)) (assoc-in [:posts :total] (:total data)) (assoc-in [:posts :loading?] false))))

(rf/reg-event-db :posts/update-query
                 (fn [db [_ k v]] (assoc-in db [:posts :query-params k] v)))

(rf/reg-event-db :posts/reset-query
                 (fn [db _] (assoc-in db [:posts :query-params] {})))

(rf/reg-event-fx :posts/fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:posts :loading?] true) :api/list-posts params}))

(rf/reg-fx :api/list-posts
           (fn [params]
             (api/list-posts params
                             (fn [r] (when (= 200 (:code r)) (rf/dispatch [:posts/set-list (:data r)])))
                             (fn [_]))))

(rf/reg-event-db :posts/open-modal
                 (fn [db _]
                   (-> db (assoc-in [:posts :modal-visible?] true) (assoc-in [:posts :editing?] false)
                       (assoc-in [:posts :form-data] {:post_sort 0 :status "0"}))))

(rf/reg-event-db :posts/close-modal
                 (fn [db _] (assoc-in db [:posts :modal-visible?] false)))

(rf/reg-event-db :posts/update-form
                 (fn [db [_ k v]] (assoc-in db [:posts :form-data k] v)))

(rf/reg-event-db :posts/edit
                 (fn [db [_ data]]
                   (-> db (assoc-in [:posts :modal-visible?] true) (assoc-in [:posts :editing?] true)
                       (assoc-in [:posts :form-data] data))))

(rf/reg-event-fx :posts/submit
                 (fn [{:keys [db]} _]
                   (let [data (get-in db [:posts :form-data]) editing? (get-in db [:posts :editing?])]
                     (if editing?
                       {:db (assoc-in db [:posts :modal-visible?] false) :api/update-post [(:post_id data) data]}
                       {:db (assoc-in db [:posts :modal-visible?] false) :api/create-post data}))))

(rf/reg-fx :api/create-post
           (fn [params] (api/create-post params (fn [r] (when (= 200 (:code r)) (.success js/antd.message "创建成功") (rf/dispatch [:posts/fetch {}]))) (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-fx :api/update-post
           (fn [[id params]] (api/update-post id params (fn [r] (when (= 200 (:code r)) (.success js/antd.message "更新成功") (rf/dispatch [:posts/fetch {}]))) (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :posts/delete
                 (fn [_ [_ id]] {:api/delete-post id}))

(rf/reg-fx :api/delete-post
           (fn [id] (api/delete-post id (fn [r] (when (= 200 (:code r)) (.success js/antd.message "删除成功") (rf/dispatch [:posts/fetch {}]))) (fn [_] (.error js/antd.message "网络错误")))))

;; ────── 服务器监控 ──────

(rf/reg-event-db :server/set-data
                 (fn [db [_ data]]
                   (-> db (assoc-in [:server :data] data) (assoc-in [:server :loading?] false))))

(rf/reg-event-fx :server/fetch
                 (fn [{:keys [db]} _]
                   {:db (assoc-in db [:server :loading?] true) :api/get-server-info nil}))

(rf/reg-fx :api/get-server-info
           (fn [_]
             (api/get-server-info
              (fn [r] (when (= 200 (:code r)) (rf/dispatch [:server/set-data (:data r)])))
              (fn [_]))))

;; ────── 缓存监控 ──────

(rf/reg-event-db :cache/set-info
                 (fn [db [_ data]]
                   (-> db (assoc-in [:cache :data] data) (assoc-in [:cache :loading?] false))))

(rf/reg-event-fx :cache/fetch-info
                 (fn [{:keys [db]} _]
                   {:db (assoc-in db [:cache :loading?] true) :api/get-cache-info nil}))

(rf/reg-fx :api/get-cache-info
           (fn [_]
             (api/get-cache-info (fn [r] (when (= 200 (:code r)) (rf/dispatch [:cache/set-info (:data r)]))) (fn [_]))))

(rf/reg-event-db :cache/set-keys
                 (fn [db [_ data]]
                   (assoc-in db [:cache :keys] data)))

(rf/reg-event-fx :cache/fetch-keys
                 (fn [{:keys [db]} _]
                   {:db db :api/get-cache-keys nil}))

(rf/reg-fx :api/get-cache-keys
           (fn [_]
             (api/get-cache-keys (fn [r] (when (= 200 (:code r)) (rf/dispatch [:cache/set-keys (:data r)]))) (fn [_]))))

(rf/reg-event-fx :cache/clear
                 (fn [{:keys [db]} _]
                   {:db db :api/clear-cache nil}))

(rf/reg-fx :api/clear-cache
           (fn [_]
             (api/clear-cache (fn [r] (when (= 200 (:code r)) (.success js/antd.message "缓存已清空") (rf/dispatch [:cache/fetch-info]) (rf/dispatch [:cache/fetch-keys]))) (fn [_] (.error js/antd.message "清空缓存失败")))))

;; ────── 数据源监控 ──────

(rf/reg-event-db :server/set-datasource
                 (fn [db [_ data]]
                   (assoc-in db [:server :datasource] data)))

(rf/reg-event-fx :server/fetch-datasource
                 (fn [{:keys [db]} _]
                   {:db db :api/get-datasource nil}))

(rf/reg-fx :api/get-datasource
           (fn [_]
             (api/get-datasource
              (fn [r] (when (= 200 (:code r)) (rf/dispatch [:server/set-datasource (:data r)])))
              (fn [_]))))

;; ────── 代码生成器 ──────

(rf/reg-event-db :gen/set-tables
                 (fn [db [_ data]]
                   (-> db (assoc-in [:gen :tables] data) (assoc-in [:gen :tables-loading?] false))))

(rf/reg-event-fx :gen/fetch-tables
                 (fn [{:keys [db]} _]
                   {:db (assoc-in db [:gen :tables-loading?] true) :api/gen-tables nil}))

(rf/reg-fx :api/gen-tables
           (fn [_] (api/gen-tables (fn [r] (when (= 200 (:code r)) (rf/dispatch [:gen/set-tables (:data r)]))) (fn [_]))))

(rf/reg-event-db :gen/set-selected-tables
                 (fn [db [_ tables]]
                   (assoc-in db [:gen :selected-tables] tables)))

(rf/reg-event-db :gen/set-preview
                 (fn [db [_ data table-name]]
                   (-> db (assoc-in [:gen :preview-data] data) (assoc-in [:gen :preview-table-name] table-name)
                       (assoc-in [:gen :preview-loading?] false) (assoc-in [:gen :preview-visible?] true))))

(rf/reg-event-fx :gen/preview
                 (fn [{:keys [db]} [_ table-name]]
                   {:db (-> db (assoc-in [:gen :preview-loading?] true) (assoc-in [:gen :preview-visible?] true)
                            (assoc-in [:gen :preview-table-name] table-name))
                    :api/gen-preview table-name}))

(rf/reg-fx :api/gen-preview
           (fn [table-name] (api/gen-preview table-name (fn [r] (when (= 200 (:code r)) (rf/dispatch [:gen/set-preview (:data r) table-name]))) (fn [_]))))

(rf/reg-event-db :gen/close-preview
                 (fn [db _] (assoc-in db [:gen :preview-visible?] false)))

(rf/reg-event-fx :gen/generate
                 (fn [{:keys [db]} [_ tables]]
                   {:db db :api/gen-generate tables}))

(rf/reg-fx :api/gen-generate
           (fn [tables] (api/gen-generate tables (fn [r] (when (= 200 (:code r)) (.success js/antd.message "代码生成成功"))) (fn [_] (.error js/antd.message "生成失败")))))

;; ────── 操作日志详情 ──────

(rf/reg-event-db :oper-logs/set-detail
                 (fn [db [_ data]]
                   (assoc-in db [:oper-logs :detail-data] data)))

(rf/reg-event-db :oper-logs/show-detail
                 (fn [db [_ data]]
                   (-> db (assoc-in [:oper-logs :detail-visible?] true) (assoc-in [:oper-logs :detail-data] data))))

(rf/reg-event-db :oper-logs/hide-detail
                 (fn [db _]
                   (assoc-in db [:oper-logs :detail-visible?] false)))

;; ────── 表单构建器 ──────

(let [counter (atom 0)]
  (rf/reg-event-db :fb/add-item
                   (fn [db [_ comp]]
                     (let [id (swap! counter inc)]
                       (update-in db [:fb :items] conj {:id id :type (:type comp) :props (:defaults comp)})))))

(rf/reg-event-db :fb/remove-item
                 (fn [db [_ id]]
                   (update-in db [:fb :items] #(filterv (fn [i] (not= (:id i) id)) %))))

(rf/reg-event-db :fb/select-item
                 (fn [db [_ id]]
                   (assoc-in db [:fb :selected-id] id)))

(rf/reg-event-db :fb/update-prop
                 (fn [db [_ k v]]
                   (let [id (get-in db [:fb :selected-id])]
                     (update-in db [:fb :items]
                                (fn [items] (mapv (fn [i] (if (= (:id i) id) (assoc-in i [:props k] v) i)) items))))))

(rf/reg-event-db :fb/toggle-code
                 (fn [db _]
                   (update-in db [:fb :code-visible?] not)))

(rf/reg-event-db :fb/clear
                 (fn [db _]
                   (assoc db :fb {:items [] :selected-id nil :code-visible? false})))

;; ────── 多Tab管理 ──────

(rf/reg-event-fx :tabs/add
                 (fn [{:keys [db]} [_ key label]]
                   (let [tabs (get-in db [:tabs :items] [])
                         exists? (some #(= (:key %) key) tabs)]
                     (if exists?
                       {:db (assoc-in db [:tabs :active] key)}
                       {:db (-> db
                                (update-in [:tabs :items] conj {:key key :label label :closable (not= key :dashboard)})
                                (assoc-in [:tabs :active] key))}))))

(rf/reg-event-db :tabs/activate
                 (fn [db [_ key]]
                   (assoc-in db [:tabs :active] key)))

(rf/reg-event-fx :tabs/remove
                 (fn [{:keys [db]} [_ key]]
                   (let [tabs (get-in db [:tabs :items] [])
                         active (get-in db [:tabs :active])
                         remaining (filterv #(not= (:key %) key) tabs)]
                     (if (= active key)
                       (let [new-active (if-let [last-rem (last remaining)] (:key last-rem) :dashboard)]
                         {:db (-> db
                                  (assoc-in [:tabs :items] remaining)
                                  (assoc-in [:tabs :active] new-active))})
                       {:db (assoc-in db [:tabs :items] remaining)}))))

(rf/reg-event-db :tabs/remove-others
                 (fn [db [_ key]]
                   (let [tabs (get-in db [:tabs :items] [])
                         home-tab (first (filter #(= (:key %) :dashboard) tabs))
                         keep-tab (first (filter #(= (:key %) key) tabs))]
                     (-> db
                         (assoc-in [:tabs :items] (filterv some? [home-tab keep-tab]))
                         (assoc-in [:tabs :active] key)))))

(rf/reg-event-db :tabs/remove-all
                 (fn [db _]
                   (let [home-tab (first (filter #(= (:key %) :dashboard) (get-in db [:tabs :items] [])))]
                     (-> db
                         (assoc-in [:tabs :items] (if home-tab [home-tab] []))
                         (assoc-in [:tabs :active] :dashboard)))))

;; ────── 菜单管理 ──────

(rf/reg-event-db :menus/set-list
                 (fn [db [_ data]]
                   (assoc-in db [:menus :items] data)))

(rf/reg-event-fx :menus/fetch
                 (fn [{:keys [db]} _]
                   {:db (assoc-in db [:menus :loading?] true)
                    :api/list-menus nil}))

(rf/reg-fx :api/list-menus
           (fn [_]
             (api/list-menus
              (fn [result]
                (when (= 200 (:code result))
                  (rf/dispatch [:menus/set-list (:data result)])))
              (fn [_]))))

(rf/reg-event-db :menus/open-modal
                 (fn [db _]
                   (-> db
                       (assoc-in [:menus :modal-visible?] true)
                       (assoc-in [:menus :editing?] false)
                       (assoc-in [:menus :form-data] {:menu_type "M" :order_num 0 :status "0" :visible "0"}))))

(rf/reg-event-db :menus/close-modal
                 (fn [db _]
                   (assoc-in db [:menus :modal-visible?] false)))

(rf/reg-event-db :menus/update-form
                 (fn [db [_ k v]]
                   (assoc-in db [:menus :form-data k] v)))

(rf/reg-event-db :menus/edit
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:menus :modal-visible?] true)
                       (assoc-in [:menus :editing?] true)
                       (assoc-in [:menus :form-data] data))))

(rf/reg-event-fx :menus/submit
                 (fn [{:keys [db]} _]
                   (let [data (get-in db [:menus :form-data])
                         editing? (get-in db [:menus :editing?])]
                     (if editing?
                       {:db (assoc-in db [:menus :modal-visible?] false)
                        :api/update-menu [(:menu_id data) data]}
                       {:db (assoc-in db [:menus :modal-visible?] false)
                        :api/create-menu data}))))

(rf/reg-fx :api/create-menu
           (fn [params]
             (api/create-menu params
                              (fn [result]
                                (when (= 200 (:code result))
                                  (.success js/antd.message "创建成功")
                                  (rf/dispatch [:menus/fetch])))
                              (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-fx :api/update-menu
           (fn [[id params]]
             (api/update-menu id params
                              (fn [result]
                                (when (= 200 (:code result))
                                  (.success js/antd.message "更新成功")
                                  (rf/dispatch [:menus/fetch])))
                              (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :menus/delete
                 (fn [_ [_ id]]
                   {:api/delete-menu id}))

(rf/reg-fx :api/delete-menu
           (fn [id]
             (api/delete-menu id
                              (fn [result]
                                (when (= 200 (:code result))
                                  (.success js/antd.message "删除成功")
                                  (rf/dispatch [:menus/fetch])))
                              (fn [_] (.error js/antd.message "网络错误")))))
