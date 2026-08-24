(ns com.ruoyi.frontend.events
  "re-frame 事件处理器。"
  (:require
   [re-frame.core :as rf]
   [com.ruoyi.frontend.db :as db]
   [com.ruoyi.frontend.api :as api]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.router :as router]))

(rf/reg-event-db :initialize-db
                 (fn [_ _]
                   db/default-db))

(def page-tab-meta
  {:dashboard {:label "首页" :icon "dashboard" :closable false}
   :user {:label "用户管理" :icon "user"}
   :role {:label "角色管理" :icon "peoples"}
   :menu {:label "菜单管理" :icon "tree-table"}
   :dept {:label "部门管理" :icon "tree"}
   :post {:label "岗位管理" :icon "post"}
   :dict {:label "字典管理" :icon "dict"}
   :config {:label "参数设置" :icon "edit"}
   :notice {:label "通知公告" :icon "message"}
   :oper-log {:label "操作日志" :icon "form"}
   :login-log {:label "登录日志" :icon "logininfor"}
   :online {:label "在线用户" :icon "online"}
   :job {:label "定时任务" :icon "job"}
   :server {:label "服务监控" :icon "server"}
   :cache {:label "缓存监控" :icon "cache"}
   :datasource {:label "连接池监视" :icon "database"}
   :swagger {:label "系统接口" :icon "swagger"}
   :profile {:label "个人中心" :icon "profile"}})

(defn- activate-page-tab [db page]
  (let [meta (merge {:label (get router/page-names page "页面")}
                    (get page-tab-meta page {}))
        tabs (get-in db [:tabs :items] [])
        exists? (some #(= (:key %) page) tabs)
        tab (merge {:key page :closable (not= page :dashboard)} meta)]
    (-> db
        (assoc :page page)
        (assoc-in [:tabs :active] page)
        (cond-> (not exists?)
          (update-in [:tabs :items] conj tab)))))

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
                                 :notice [:notices/fetch {}]
                                 :leave [:leave/fetch {}]
                                 :bpm-todo [:bpm/todo-fetch]
                                 :bpm-done [:bpm/done-fetch]
                                 :bpm-instance [:bpm/instance-fetch {}]
                                 :bpm-model [:bpm/model-fetch {}]
                                 :hrm-employee [:hrm/fetch {}]
                                 :oa-calendar [:oa-calendar/fetch {}]
                                 :oa-meeting [:oa-meeting/fetch {}]
                                 :crm-customer [:crm/fetch {}]
                                 nil)
                         effects {:db (activate-page-tab db page)
                                  :router/navigate! page}]
                     (if fetch
                       (assoc effects :dispatch fetch)
                       effects))))

(rf/reg-event-db :auth/set-token
                 (fn [db [_ token]]
                   (assoc-in db [:auth :token] token)))

(rf/reg-event-fx :auth/set-user
                 (fn [{:keys [db]} [_ user]]
                   (try
                     (.setItem js/localStorage "ruoyi_user" (.stringify js/JSON (clj->js user)))
                     (catch js/Error _))
                   (let [page (:page db)
                         effects {:db (assoc-in db [:auth :user] user)}
                         ;; 只在登录后或当前页面异常时导航到 dashboard
                         non-page? (or (nil? page) (= :login page))]
                     (if non-page?
                       (assoc effects :dispatch [:navigate :dashboard])
                       effects))))

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
                   (let [token (:token data)]
                     ;; 保存到 localStorage
                     (try (.setItem js/localStorage "ruoyi_token" token) (catch js/Error _))
                     {:db (-> db
                              (assoc-in [:auth :token] token)
                              (assoc-in [:auth :loading?] false))
                      :dispatch-n [[:navigate :dashboard] [:auth/fetch-info]]})))

(rf/reg-event-fx :auth/login-failure
                 (fn [{:keys [db]} [_ msg]]
                   (antd/error! msg)
                   {:db (-> db
                            (assoc-in [:auth :loading?] false)
                            (assoc :notification {:type :error :message msg}))}))


(rf/reg-event-fx :auth/logout
                 (fn [{:keys [db]} _]
                   (try
                     (.removeItem js/localStorage "ruoyi_token")
                     (.removeItem js/localStorage "ruoyi_user")
                     (catch js/Error _))
                   {:db (-> db
                            (assoc-in [:auth :token] nil)
                            (assoc-in [:auth :user] nil)
                            (assoc-in [:menus :items] [])
                            (assoc-in [:menus :tree-data] [])
                            (assoc :tabs {:items [{:key :dashboard :label "首页" :closable false}]
                                          :active :dashboard}))
                    :api/logout nil
                    :dispatch [:navigate :login]}))

(rf/reg-fx :api/logout
           (fn [_]
             (api/logout
              (fn [_]
                (try
                  (.removeItem js/localStorage "ruoyi_token")
                  (.removeItem js/localStorage "ruoyi_user")
                  (catch js/Error _)))
              (fn [_]
                (try
                  (.removeItem js/localStorage "ruoyi_token")
                  (.removeItem js/localStorage "ruoyi_user")
                  (catch js/Error _))))))

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

(defn- stored-layout-settings
  "从 localStorage 读取布局设置。"
  []
  (try
    (when-let [raw (js/localStorage.getItem "rouyi-layout-settings")]
      (merge db/default-layout-settings
             (js->clj (.parse js/JSON raw) :keywordize-keys true)))
    (catch js/Error _ nil)))

(defn- persist-layout-settings!
  "把布局设置持久化到 localStorage。"
  [settings]
  (try
    (js/localStorage.setItem "rouyi-layout-settings"
                             (.stringify js/JSON (clj->js settings)))
    (catch js/Error _)))

(defn- apply-theme-style
  "根据布局面板的主题风格同步 antd 主题模式。"
  [db settings]
  (let [mode (if (= "dark" (:theme-style settings)) :dark :light)]
    (js/localStorage.setItem "rouyi-theme-mode" (name mode))
    (assoc-in db [:theme :mode] mode)))

(rf/reg-event-db :theme/toggle-mode
                 (fn [db _]
                   (update-in db [:theme :mode] #(if (= % :light) :dark :light))))

(rf/reg-event-db :theme/set-mode
                 (fn [db [_ mode]]
                   (js/localStorage.setItem "rouyi-theme-mode" (name mode))
                   (assoc-in db [:theme :mode] mode)))

(rf/reg-event-db :theme/set-algorithm
                 (fn [db [_ algorithm]]
                   (js/localStorage.setItem "rouyi-theme-algorithm" algorithm)
                   (assoc-in db [:theme :algorithm] algorithm)))

(rf/reg-event-db :theme/set-primary-color
                 (fn [db [_ color]]
                   (js/localStorage.setItem "rouyi-primary-color" color)
                   (assoc-in db [:theme :primary-color] color)))

(rf/reg-event-db :theme/set-component-size
                 (fn [db [_ size]]
                   (js/localStorage.setItem "rouyi-component-size" size)
                   (assoc-in db [:theme :component-size] size)))

(rf/reg-event-db :theme/set-density
                 (fn [db [_ size]]
                   (js/localStorage.setItem "rouyi-component-size" size)
                   (js/localStorage.setItem "rouyi-theme-algorithm"
                                            (if (= size "small") "compact" "default"))
                   (-> db
                       (assoc-in [:theme :component-size] size)
                       (assoc-in [:theme :algorithm] (if (= size "small") "compact" "default")))))

(rf/reg-event-db :theme/set-font-size
                 (fn [db [_ size]]
                   (js/localStorage.setItem "rouyi-font-size" size)
                   (assoc-in db [:theme :font-size] size)))

(rf/reg-event-db :theme/load-from-storage
                 (fn [db _]
                   (let [mode (js/localStorage.getItem "rouyi-theme-mode")
                         algorithm (js/localStorage.getItem "rouyi-theme-algorithm")
                         color (js/localStorage.getItem "rouyi-primary-color")
                         size (js/localStorage.getItem "rouyi-component-size")
                         font-size (js/localStorage.getItem "rouyi-font-size")
                         layout-settings (stored-layout-settings)]
                     (cond-> db
                       mode (assoc-in [:theme :mode] (keyword mode))
                       algorithm (assoc-in [:theme :algorithm] algorithm)
                       color (assoc-in [:theme :primary-color] color)
                       size (assoc-in [:theme :component-size] size)
                       font-size (assoc-in [:theme :font-size] font-size)
                       layout-settings (assoc :layout-settings layout-settings)))))

(rf/reg-event-db :layout/set-setting
                 (fn [db [_ k value]]
                   (let [settings (assoc (merge db/default-layout-settings (:layout-settings db)) k value)
                         db* (assoc db :layout-settings settings)]
                     (persist-layout-settings! settings)
                     (if (= k :theme-style)
                       (apply-theme-style db* settings)
                       db*))))

(rf/reg-event-db :layout/reset-settings
                 (fn [db _]
                   (let [settings db/default-layout-settings]
                     (persist-layout-settings! settings)
                     (js/localStorage.setItem "rouyi-primary-color" "#409eff")
                     (-> db
                         (assoc :layout-settings settings)
                         (assoc-in [:theme :primary-color] "#409eff")
                         (apply-theme-style settings)))))

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
                   (let [items (if (sequential? data) data (:rows data []))]
                     (-> db
                         (assoc-in [:dicts :types] items)
                         (assoc-in [:dicts :loading?] false)))))

(rf/reg-event-fx :dicts/search
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:dicts :loading?] true)
                    :api/list-dicts-search params}))

(rf/reg-fx :api/list-dicts-search
           (fn [params]
             (api/list-dict-types {}
                                  (fn [result]
                                    (when (= 200 (:code result))
                                      (let [data (:data result)
                                            items (if (sequential? data) data (:rows data []))
                                            filtered (cond->> items
                                                       (:dict_name params)
                                                       (filter #(clojure.string/includes?
                                                                 (or (:dict_name %) "")
                                                                 (:dict_name params)))
                                                       (:dict_type params)
                                                       (filter #(clojure.string/includes?
                                                                 (or (:dict_type %) "")
                                                                 (:dict_type params)))
                                                       (some? (:status params))
                                                       (filter #(= (:status params) (:status %))))]
                                        (rf/dispatch [:dicts/set-types {:rows filtered :total (count filtered)}]))))
                                  (fn [_]))))

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
                   (let [items (if (sequential? data) data (:rows data []))]
                     (-> db
                         (assoc-in [:dicts :data] items)
                         (assoc-in [:dicts :loading?] false)))))

(rf/reg-event-fx :dicts/fetch-data
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:dicts :loading?] true)
                    :api/list-dict-data params}))

(rf/reg-event-db :dicts/select-type
                 (fn [db [_ dict-type]]
                   (assoc-in db [:dicts :selected-type] dict-type)))

(rf/reg-event-db :dicts/clear-selected-type
                 (fn [db _]
                   (assoc-in db [:dicts :selected-type] nil)))

(rf/reg-fx :api/list-dict-data
           (fn [params]
             (api/list-dict-data params
                                 (fn [result]
                                   (when (= 200 (:code result))
                                     (rf/dispatch [:dicts/set-data (:data result)])))
                                 (fn [_]))))

(rf/reg-event-db :configs/set-list
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))
                         total (if (sequential? data) (count data) (:total data 0))]
                     (-> db
                         (assoc-in [:configs :items] items)
                         (assoc-in [:configs :total] total)
                         (assoc-in [:configs :loading?] false)))))

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
                                    (antd/success! "创建成功"))
                                  (when (not= 200 (:code result))
                                    (antd/error! (:msg result))))
                                (fn [_] (antd/error! "网络错误")))))

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
                                    (antd/success! "更新成功"))
                                  (when (not= 200 (:code result))
                                    (antd/error! (:msg result))))
                                (fn [_] (antd/error! "网络错误")))))

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
                                    (antd/success! "删除成功"))
                                  (when (not= 200 (:code result))
                                    (antd/error! (:msg result))))
                                (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :configs/deleted
                 (fn [{:keys [db]} _]
                   {:db db
                    :dispatch [:configs/fetch {}]}))

(rf/reg-event-db :oper-logs/set-list
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))
                         total (if (sequential? data) (count data) (:total data 0))]
                     (-> db
                         (assoc-in [:oper-logs :items] items)
                         (assoc-in [:oper-logs :total] total)
                         (assoc-in [:oper-logs :loading?] false)))))

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

(rf/reg-event-fx :oper-logs/delete
                 (fn [{:keys [db]} [_ ids]]
                   {:db db
                    :api/delete-oper-logs ids}))

(rf/reg-fx :api/delete-oper-logs
           (fn [ids]
             (api/delete-oper-logs ids
                                   (fn [result]
                                     (when (= 200 (:code result))
                                       (antd/success! "删除成功")
                                       (rf/dispatch [:oper-logs/fetch {}]))
                                     (when (not= 200 (:code result))
                                       (antd/error! (:msg result))))
                                   (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/clear-oper-logs
           (fn [_]
             (api/clear-oper-logs
              (fn [result]
                (when (= 200 (:code result))
                  (rf/dispatch [:oper-logs/cleared])
                  (antd/success! "清空成功")))
              (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :oper-logs/cleared
                 (fn [{:keys [db]} _]
                   {:db db
                    :dispatch [:oper-logs/fetch {}]}))

(rf/reg-event-fx :oper-logs/export
                 (fn [{:keys [db]} _]
                   (let [items (get-in db [:oper-logs :items] [])]
                     (when (seq items)
                       (let [headers ["日志编号" "系统模块" "操作类型" "操作人员" "操作IP" "状态" "操作时间"]
                             rows (map (fn [item]
                                         [(:oper_id item) (:title item) (:business_type item)
                                          (:oper_name item) (:oper_ip item)
                                          (if (= "0" (:status item)) "成功" "失败")
                                          (:oper_time item)])
                                       items)
                             csv (str (clojure.string/join "," headers) "\n"
                                      (clojure.string/join "\n" (map #(clojure.string/join "," %) rows)))
                             blob (js/Blob. #js [csv] #js {:type "text/csv;charset=utf-8"})
                             url (js/URL.createObjectURL blob)
                             link (.createElement js/document "a")]
                         (set! (.-href link) url)
                         (.setAttribute link "download" "oper_log.csv")
                         (.appendChild js/document.body link)
                         (.click link)
                         (.removeChild js/document.body link)
                         (js/URL.revokeObjectURL url)
                         (antd/success! "导出成功"))))
                   {:db db}))

(rf/reg-event-db :login-logs/set-list
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))
                         total (if (sequential? data) (count data) (:total data 0))]
                     (-> db
                         (assoc-in [:login-logs :items] items)
                         (assoc-in [:login-logs :total] total)
                         (assoc-in [:login-logs :loading?] false)))))

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

(rf/reg-event-fx :login-logs/delete
                 (fn [{:keys [db]} [_ ids]]
                   {:db db
                    :api/delete-login-logs ids}))

(rf/reg-fx :api/delete-login-logs
           (fn [ids]
             (api/delete-login-logs ids
                                    (fn [result]
                                      (when (= 200 (:code result))
                                        (antd/success! "删除成功")
                                        (rf/dispatch [:login-logs/fetch {}]))
                                      (when (not= 200 (:code result))
                                        (antd/error! (:msg result))))
                                    (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :login-logs/unlock
                 (fn [{:keys [db]} [_ username]]
                   (antd/success! (str "用户 " username " 解锁成功"))
                   {:db db}))

(rf/reg-fx :api/clear-login-logs
           (fn [_]
             (api/clear-login-logs
              (fn [result]
                (when (= 200 (:code result))
                  (rf/dispatch [:login-logs/cleared])
                  (antd/success! "清空成功")))
              (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :login-logs/cleared
                 (fn [{:keys [db]} _]
                   {:db db
                    :dispatch [:login-logs/fetch {}]}))

(rf/reg-event-fx :login-logs/export
                 (fn [{:keys [db]} _]
                   (let [items (get-in db [:login-logs :items] [])]
                     (when (seq items)
                       (let [headers ["访问编号" "用户名称" "登录地址" "登录地点" "浏览器" "操作系统" "登录状态" "操作信息" "登录时间"]
                             rows (map (fn [item]
                                         [(:info_id item) (:user_name item) (:ipaddr item)
                                          (:login_location item) (:browser item) (:os item)
                                          (if (= "0" (:status item)) "成功" "失败")
                                          (:msg item) (:login_time item)])
                                       items)
                             csv (str (clojure.string/join "," headers) "\n"
                                      (clojure.string/join "\n" (map #(clojure.string/join "," %) rows)))
                             blob (js/Blob. #js [csv] #js {:type "text/csv;charset=utf-8"})
                             url (js/URL.createObjectURL blob)
                             link (.createElement js/document "a")]
                         (set! (.-href link) url)
                         (.setAttribute link "download" "login_log.csv")
                         (.appendChild js/document.body link)
                         (.click link)
                         (.removeChild js/document.body link)
                         (js/URL.revokeObjectURL url)
                         (antd/success! "导出成功"))))
                   {:db db}))

;; ────── 在线用户 ──────

(rf/reg-event-db :online-users/set-list
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))
                         total (if (sequential? data) (count data) (:total data 0))]
                     (-> db
                         (assoc-in [:online-users :items] items)
                         (assoc-in [:online-users :total] total)
                         (assoc-in [:online-users :loading?] false)))))

(rf/reg-event-fx :online-users/search
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:online-users :loading?] true)
                    :api/list-online-users-search params}))

(rf/reg-fx :api/list-online-users-search
           (fn [params]
             (api/list-online-users {}
                                    (fn [result]
                                      (when (= 200 (:code result))
                                        (let [data (:data result)
                                              items (if (sequential? data) data (:rows data []))
                                              filtered (cond->> items
                                                         (:user_name params)
                                                         (filter #(clojure.string/includes?
                                                                   (or (get % "user-name" (:user_name %)) "")
                                                                   (:user_name params))))]
                                          (rf/dispatch [:online-users/set-list {:rows filtered :total (count filtered)}]))))
                                    (fn [_]))))

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
                   (let [items (if (sequential? data) data (:rows data []))
                         total (if (sequential? data) (count data) (:total data 0))]
                     (-> db
                         (assoc-in [:jobs :items] items)
                         (assoc-in [:jobs :total] total)
                         (assoc-in [:jobs :loading?] false)))))

(rf/reg-event-fx :jobs/search
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:jobs :loading?] true)
                    :api/list-jobs-search params}))

(rf/reg-fx :api/list-jobs-search
           (fn [params]
             (api/list-jobs {}
                            (fn [result]
                              (when (= 200 (:code result))
                                (let [data (:data result)
                                      items (if (sequential? data) data (:rows data []))
                                      filtered (cond->> items
                                                 (:job_name params)
                                                 (filter #(clojure.string/includes?
                                                           (or (:job_name %) "")
                                                           (:job_name params)))
                                                 (:job_group params)
                                                 (filter #(clojure.string/includes?
                                                           (or (:job_group %) "")
                                                           (:job_group params))))]
                                  (rf/dispatch [:jobs/set-list {:rows filtered :total (count filtered)}]))))
                            (fn [_]))))

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

(rf/reg-event-fx :jobs/run-once
                 (fn [_ [_ job-id]]
                   {:api/run-job-once job-id}))

(rf/reg-fx :api/run-job-once
           (fn [job-id]
             (api/run-job-once job-id
                               (fn [result]
                                 (when (= 200 (:code result))
                                   (antd/success! "执行成功")))
                               (fn [_] (antd/error! "执行失败")))))

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
                   (let [items (if (sequential? data) data (:rows data []))
                         total (if (sequential? data) (count data) (:total data 0))]
                     (-> db
                         (assoc-in [:roles :items] items)
                         (assoc-in [:roles :total] total)
                         (assoc-in [:roles :loading?] false)))))

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
                       (assoc-in [:roles :editing] nil)
                       (assoc-in [:roles :form-data] {:role_sort 0 :status "0" :data_scope "1"}))))

(rf/reg-event-db :roles/close-modal
                 (fn [db _]
                   (assoc-in db [:roles :modal-visible?] false)))

(rf/reg-event-db :roles/edit
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:roles :modal-visible?] true)
                       (assoc-in [:roles :editing?] true)
                       (assoc-in [:roles :editing] data)
                       (assoc-in [:roles :form-data] data))))

(rf/reg-event-fx :roles/submit
                 (fn [{:keys [db]} [_ values]]
                   (let [editing (get-in db [:roles :editing])]
                     (if editing
                       {:db (assoc-in db [:roles :modal-visible?] false)
                        :api/update-role [(:role_id editing) values]}
                       {:db (assoc-in db [:roles :modal-visible?] false)
                        :api/create-role values}))))

(rf/reg-fx :api/create-role
           (fn [params]
             (api/create-role params
                              (fn [result]
                                (when (= 200 (:code result))
                                  (antd/success! "创建成功")
                                  (rf/dispatch [:roles/fetch {}])))
                              (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/update-role
           (fn [[id params]]
             (api/update-role id params
                              (fn [result]
                                (when (= 200 (:code result))
                                  (antd/success! "更新成功")
                                  (rf/dispatch [:roles/fetch {}])))
                              (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/update-role-and-refresh
           (fn [[id params]]
             (api/update-role id params
                              (fn [result]
                                (when (= 200 (:code result))
                                  (antd/success! "权限更新成功，正在刷新...")
                                  (rf/dispatch [:roles/fetch {}])
                                  ;; 刷新页面以更新菜单
                                  (js/setTimeout #(.reload js/location) 500)))
                              (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :roles/delete
                 (fn [_ [_ id]]
                   {:api/delete-role id}))

(rf/reg-event-fx :roles/change-status
                 (fn [_ [_ id status]]
                   {:api/change-role-status [id status]}))

(rf/reg-fx :api/delete-role
           (fn [id]
             (api/delete-role id
                              (fn [result]
                                (when (= 200 (:code result))
                                  (antd/success! "删除成功")
                                  (rf/dispatch [:roles/fetch {}])))
                              (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/change-role-status
           (fn [[id status]]
             (api/change-role-status id status
                                     (fn [result]
                                       (when (= 200 (:code result))
                                         (antd/success! "状态修改成功")
                                         (rf/dispatch [:roles/fetch {}])))
                                     (fn [_] (antd/error! "网络错误")))))

;; ────── 角色菜单权限 ──────

(rf/reg-event-fx :roles/open-permission
                 (fn [{:keys [db]} [_ role]]
                   {:db (-> db
                            (assoc-in [:roles :permission-visible?] true)
                            (assoc-in [:roles :permission-role] role))
                    :api/fetch-role-for-permission (:role_id role)}))

(rf/reg-fx :api/fetch-role-for-permission
           (fn [role-id]
             (api/get-role role-id
                           (fn [result]
                             (when (= 200 (:code result))
                               (let [role (:data result)]
                                 (rf/dispatch [:roles/set-permission-role role])
                                 (rf/dispatch [:roles/set-checked-keys (mapv str (:menu-ids role []))]))))
                           (fn [_] (antd/error! "获取角色详情失败")))))

(rf/reg-event-db :roles/set-permission-role
                 (fn [db [_ role]]
                   (assoc-in db [:roles :permission-role] role)))

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

(rf/reg-event-db :users/open-import
                 (fn [db _]
                   (-> db
                       (assoc-in [:users :import-visible?] true)
                       (assoc-in [:users :import-file] nil)
                       (assoc-in [:users :import-update-support?] false))))

(rf/reg-event-db :users/close-import
                 (fn [db _]
                   (assoc-in db [:users :import-visible?] false)))

(rf/reg-event-db :users/set-import-file
                 (fn [db [_ file]]
                   (assoc-in db [:users :import-file] file)))

(rf/reg-event-db :users/set-import-loading
                 (fn [db [_ loading?]]
                   (assoc-in db [:users :import-loading?] loading?)))

(rf/reg-event-db :users/set-import-update-support
                 (fn [db [_ update-support?]]
                   (assoc-in db [:users :import-update-support?] update-support?)))

(rf/reg-event-fx :users/import
                 (fn [{:keys [db]} _]
                   (let [file (get-in db [:users :import-file])
                         update-support? (get-in db [:users :import-update-support?] false)]
                     (if file
                       {:db (assoc-in db [:users :import-loading?] true)
                        :api/import-users [file update-support?]}
                       {:db db}))))

(rf/reg-fx :api/import-users
           (fn [[file update-support?]]
             (api/import-users-csv file update-support?
                                   (fn [r]
                                     (rf/dispatch [:users/set-import-loading false])
                                     (when (= 200 (:code r))
                                       (antd/success! (str "导入完成：成功 " (:success (:data r)) " 条，失败 " (:failed (:data r)) " 条"))
                                       (rf/dispatch [:users/close-import])
                                       (rf/dispatch [:users/fetch {}])))
                                   (fn [_]
                                     (rf/dispatch [:users/set-import-loading false])
                                     (antd/error! "导入失败")))))

(rf/reg-event-fx :users/export
                 (fn [{:keys [db]} _]
                   (let [params (get-in db [:users :query-params] {})
                         ids (get-in db [:users :selected-ids] [])
                         params (cond-> params
                                  (seq ids) (assoc :ids (.join (clj->js ids) ",")))]
                     {:db db :api/export-users params})))

(rf/reg-fx :api/export-users
           (fn [params]
             (api/export-users-csv
              params
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
              (fn [_] (antd/error! "导出失败")))))

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
                         menu-ids (get-in db [:roles :checked-keys] [])
                         menu-ids-int (mapv (fn [x] (if (string? x) (parse-long x) x)) menu-ids)]
                     {:db (assoc-in db [:roles :permission-visible?] false)
                      :api/update-role-and-refresh [role-id {:role_id role-id :menu-ids menu-ids-int}]})))

;; ────── 角色数据权限 ──────

(rf/reg-event-fx :roles/open-data-scope
                 (fn [{:keys [db]} [_ role]]
                   {:db (-> db
                            (assoc-in [:roles :data-scope-visible?] true)
                            (assoc-in [:roles :data-scope-role] role)
                            (assoc-in [:roles :data-scope] (or (:data_scope role) "1"))
                            (assoc-in [:roles :data-scope-checked-keys] []))
                    :api/fetch-role-dept-tree (:role_id role)}))

(rf/reg-event-db :roles/close-data-scope
                 (fn [db _]
                   (assoc-in db [:roles :data-scope-visible?] false)))

(rf/reg-event-db :roles/set-data-scope
                 (fn [db [_ data-scope]]
                   (assoc-in db [:roles :data-scope] data-scope)))

(rf/reg-event-db :roles/set-data-scope-checked-keys
                 (fn [db [_ keys]]
                   (assoc-in db [:roles :data-scope-checked-keys] keys)))

(rf/reg-event-db :roles/set-dept-tree-and-keys
                 (fn [db [_ result]]
                   (-> db
                       (assoc-in [:roles :data-scope-dept-tree] (:depts result []))
                       (assoc-in [:roles :data-scope-checked-keys] (mapv str (:checked-keys result []))))))

(rf/reg-fx :api/fetch-role-dept-tree
           (fn [role-id]
             (api/get-role-dept-tree role-id
                                     (fn [result]
                                       (when (= 200 (:code result))
                                         (rf/dispatch [:roles/set-dept-tree-and-keys (:data result)])))
                                     (fn [_] (antd/error! "获取部门树失败")))))

(rf/reg-event-fx :roles/save-data-scope
                 (fn [{:keys [db]} _]
                   (let [role-id (get-in db [:roles :data-scope-role :role_id])
                         data-scope (get-in db [:roles :data-scope] "1")
                         dept-ids (get-in db [:roles :data-scope-checked-keys] [])]
                     {:db (assoc-in db [:roles :data-scope-visible?] false)
                      :api/save-data-scope {:role_id role-id
                                            :data_scope data-scope
                                            :dept_ids (clojure.string/join "," dept-ids)}})))

(rf/reg-fx :api/save-data-scope
           (fn [params]
             (api/set-role-data-scope params
                                      (fn [result]
                                        (when (= 200 (:code result))
                                          (antd/success! "数据权限设置成功")
                                          (rf/dispatch [:roles/fetch {}])))
                                      (fn [_] (antd/error! "设置失败")))))

;; ────── 角色用户分配 ──────

(rf/reg-event-fx :roles/open-user-alloc
                 (fn [{:keys [db]} [_ role]]
                   {:db (-> db
                            (assoc-in [:roles :user-alloc-visible?] true)
                            (assoc-in [:roles :user-alloc-role] role)
                            (assoc-in [:roles :user-alloc-active-tab] "allocated")
                            (assoc-in [:roles :allocated-query] {})
                            (assoc-in [:roles :unallocated-query] {})
                            (assoc-in [:roles :allocated-selected] [])
                            (assoc-in [:roles :unallocated-selected] [])
                            (assoc-in [:roles :allocated-items] [])
                            (assoc-in [:roles :unallocated-items] [])
                            (assoc-in [:roles :allocated-total] 0)
                            (assoc-in [:roles :unallocated-total] 0))
                    :api/list-role-allocated-users {:role_id (:role_id role)}}))

(rf/reg-event-db :roles/close-user-alloc
                 (fn [db _]
                   (assoc-in db [:roles :user-alloc-visible?] false)))

(rf/reg-event-db :roles/set-user-alloc-active-tab
                 (fn [db [_ tab]]
                   (assoc-in db [:roles :user-alloc-active-tab] tab)))

(rf/reg-event-db :roles/set-allocated-query
                 (fn [db [_ k v]]
                   (assoc-in db [:roles :allocated-query k] v)))

(rf/reg-event-db :roles/reset-allocated-query
                 (fn [db _]
                   (assoc-in db [:roles :allocated-query] {})))

(rf/reg-event-fx :roles/fetch-allocated
                 (fn [{:keys [db]} _]
                   (let [role (get-in db [:roles :user-alloc-role])
                         query (get-in db [:roles :allocated-query] {})]
                     {:db (assoc-in db [:roles :allocated-loading?] true)
                      :api/list-role-allocated-users (merge {:role_id (:role_id role)} query)})))

(rf/reg-event-db :roles/set-allocated-list
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))
                         total (if (sequential? data) (count data) (:total data 0))]
                     (-> db
                         (assoc-in [:roles :allocated-items] items)
                         (assoc-in [:roles :allocated-total] total)
                         (assoc-in [:roles :allocated-loading?] false)))))

(rf/reg-event-db :roles/set-allocated-selected
                 (fn [db [_ keys]]
                   (assoc-in db [:roles :allocated-selected] keys)))

(rf/reg-event-db :roles/set-unallocated-query
                 (fn [db [_ k v]]
                   (assoc-in db [:roles :unallocated-query k] v)))

(rf/reg-event-db :roles/reset-unallocated-query
                 (fn [db _]
                   (assoc-in db [:roles :unallocated-query] {})))

(rf/reg-event-fx :roles/fetch-unallocated
                 (fn [{:keys [db]} _]
                   (let [role (get-in db [:roles :user-alloc-role])
                         query (get-in db [:roles :unallocated-query] {})]
                     {:db (assoc-in db [:roles :unallocated-loading?] true)
                      :api/list-role-unallocated-users (merge {:role_id (:role_id role)} query)})))

(rf/reg-event-db :roles/set-unallocated-list
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))
                         total (if (sequential? data) (count data) (:total data 0))]
                     (-> db
                         (assoc-in [:roles :unallocated-items] items)
                         (assoc-in [:roles :unallocated-total] total)
                         (assoc-in [:roles :unallocated-loading?] false)))))

(rf/reg-event-db :roles/set-unallocated-selected
                 (fn [db [_ keys]]
                   (assoc-in db [:roles :unallocated-selected] keys)))

(rf/reg-fx :api/list-role-allocated-users
           (fn [params]
             (api/list-role-allocated-users
              params
              (fn [result]
                (when (= 200 (:code result))
                  (rf/dispatch [:roles/set-allocated-list (:data result)])))
              (fn [_] (rf/dispatch [:roles/set-allocated-list []])))))

(rf/reg-fx :api/list-role-unallocated-users
           (fn [params]
             (api/list-role-unallocated-users
              params
              (fn [result]
                (when (= 200 (:code result))
                  (rf/dispatch [:roles/set-unallocated-list (:data result)])))
              (fn [_] (rf/dispatch [:roles/set-unallocated-list []])))))

(rf/reg-event-fx :roles/cancel-user
                 (fn [{:keys [db]} [_ user-id]]
                   (let [role (get-in db [:roles :user-alloc-role])]
                     {:api/cancel-role-auth-user {:role_id (:role_id role) :user_id user-id}})))

(rf/reg-fx :api/cancel-role-auth-user
           (fn [params]
             (api/cancel-role-auth-user
              params
              (fn [result]
                (when (= 200 (:code result))
                  (antd/success! "取消授权成功")
                  (rf/dispatch [:roles/fetch-allocated])))
              (fn [_] (antd/error! "取消授权失败")))))

(rf/reg-event-fx :roles/cancel-all-users
                 (fn [{:keys [db]} _]
                   (let [role (get-in db [:roles :user-alloc-role])
                         ids (get-in db [:roles :allocated-selected] [])]
                     (if (seq ids)
                       {:api/cancel-role-auth-user-all {:role_id (:role_id role)
                                                        :user_ids (clojure.string/join "," ids)}}
                       (do (antd/warning! "请选择要取消授权的用户")
                           {:db db})))))

(rf/reg-fx :api/cancel-role-auth-user-all
           (fn [params]
             (api/cancel-role-auth-user-all
              params
              (fn [result]
                (when (= 200 (:code result))
                  (antd/success! "批量取消授权成功")
                  (rf/dispatch [:roles/fetch-allocated])
                  (rf/dispatch [:roles/set-allocated-selected []])))
              (fn [_] (antd/error! "批量取消授权失败")))))

(rf/reg-event-fx :roles/select-all-users
                 (fn [{:keys [db]} _]
                   (let [role (get-in db [:roles :user-alloc-role])
                         ids (get-in db [:roles :unallocated-selected] [])]
                     (if (seq ids)
                       {:api/select-role-auth-user-all {:role_id (:role_id role)
                                                        :user_ids (clojure.string/join "," ids)}}
                       (do (antd/warning! "请选择要授权的用户")
                           {:db db})))))

(rf/reg-fx :api/select-role-auth-user-all
           (fn [params]
             (api/select-role-auth-user-all
              params
              (fn [result]
                (when (= 200 (:code result))
                  (antd/success! "批量授权成功")
                  (rf/dispatch [:roles/fetch-unallocated])
                  (rf/dispatch [:roles/set-unallocated-selected []])))
              (fn [_] (antd/error! "批量授权失败")))))

;; ────── 部门管理 ──────

(declare build-dept-tree)

(rf/reg-event-db :depts/set-list
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))
                         tree (build-dept-tree items 0)
                         _ (js/console.log "[depts/set-list] tree count:" (count tree) "first:" (clj->js (first tree)))]
                     (-> db
                         (assoc-in [:depts :items] items)
                         (assoc-in [:depts :tree] tree)
                         (assoc-in [:depts :loading?] false)))))

(rf/reg-event-fx :depts/search
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:depts :loading?] true)
                    :api/list-depts-search params}))

(rf/reg-fx :api/list-depts-search
           (fn [params]
             (api/list-depts {}
                             (fn [result]
                               (when (= 200 (:code result))
                                 (let [items (:data result [])
                                       filtered (cond->> items
                                                  (:dept_name params)
                                                  (filter #(clojure.string/includes?
                                                            (or (:dept_name %) "")
                                                            (:dept_name params)))
                                                  (some? (:status params))
                                                  (filter #(= (:status params) (:status %))))]
                                   (rf/dispatch [:depts/set-list filtered]))))
                             (fn [_]))))

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
                 (fn [db [_ initial-data]]
                   (-> db (assoc-in [:depts :modal-visible?] true) (assoc-in [:depts :editing] nil)
                       (assoc-in [:depts :form-data] (merge {:order_num 0 :status "0"} initial-data)))))

(rf/reg-event-db :depts/close-modal
                 (fn [db _] (assoc-in db [:depts :modal-visible?] false)))

(rf/reg-event-db :depts/edit
                 (fn [db [_ data]]
                   (-> db (assoc-in [:depts :modal-visible?] true) (assoc-in [:depts :editing] data)
                       (assoc-in [:depts :form-data] data))))

(rf/reg-event-fx :depts/submit
                 (fn [{:keys [db]} [_ values]]
                   (let [editing (get-in db [:depts :editing])
                         values (update values :order_num #(if (string? %) (js/parseInt % 10) %))]
                     (if editing
                       {:db (assoc-in db [:depts :modal-visible?] false) :api/update-dept [(:dept_id editing) values]}
                       {:db (assoc-in db [:depts :modal-visible?] false) :api/create-dept values}))))

(rf/reg-fx :api/create-dept
           (fn [params]
             (api/create-dept params (fn [r] (when (= 200 (:code r)) (antd/success! "创建成功") (rf/dispatch [:depts/fetch {}]))) (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/update-dept
           (fn [[id params]]
             (api/update-dept id params (fn [r] (when (= 200 (:code r)) (antd/success! "更新成功") (rf/dispatch [:depts/fetch {}]))) (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :depts/delete
                 (fn [_ [_ id]] {:api/delete-dept id}))

(rf/reg-event-fx :depts/change-status
                 (fn [_ [_ id status]]
                   {:api/change-dept-status [id status]}))

(rf/reg-fx :api/delete-dept
           (fn [id]
             (api/delete-dept id (fn [r] (when (= 200 (:code r)) (antd/success! "删除成功") (rf/dispatch [:depts/fetch {}]))) (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/change-dept-status
           (fn [[id status]]
             (api/change-dept-status id status
                                     (fn [result]
                                       (when (= 200 (:code result))
                                         (antd/success! "状态修改成功")
                                         (rf/dispatch [:depts/fetch {}])))
                                     (fn [_] (antd/error! "网络错误")))))

;; ────── 岗位管理 ──────

(rf/reg-event-db :posts/set-list
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))
                         total (if (sequential? data) (count data) (:total data 0))]
                     (-> db (assoc-in [:posts :items] items) (assoc-in [:posts :total] total) (assoc-in [:posts :loading?] false)))))

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
                   (-> db
                       (assoc-in [:posts :modal-visible?] true)
                       (assoc-in [:posts :editing?] false)
                       (assoc-in [:posts :editing] nil)
                       (assoc-in [:posts :form-data] {:post_sort 0 :status "0"}))))

(rf/reg-event-db :posts/close-modal
                 (fn [db _] (assoc-in db [:posts :modal-visible?] false)))

(rf/reg-event-db :posts/edit
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:posts :modal-visible?] true)
                       (assoc-in [:posts :editing?] true)
                       (assoc-in [:posts :editing] data)
                       (assoc-in [:posts :form-data] data))))

(rf/reg-event-fx :posts/submit
                 (fn [{:keys [db]} [_ values]]
                   (let [editing (get-in db [:posts :editing])]
                     (if editing
                       {:db (assoc-in db [:posts :modal-visible?] false) :api/update-post [(:post_id editing) values]}
                       {:db (assoc-in db [:posts :modal-visible?] false) :api/create-post values}))))

(rf/reg-fx :api/create-post
           (fn [params] (api/create-post params (fn [r] (when (= 200 (:code r)) (antd/success! "创建成功") (rf/dispatch [:posts/fetch {}]))) (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/update-post
           (fn [[id params]] (api/update-post id params (fn [r] (when (= 200 (:code r)) (antd/success! "更新成功") (rf/dispatch [:posts/fetch {}]))) (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :posts/delete
                 (fn [_ [_ id]] {:api/delete-post id}))

(rf/reg-event-fx :posts/change-status
                 (fn [_ [_ id status]]
                   {:api/change-post-status [id status]}))

(rf/reg-fx :api/delete-post
           (fn [id] (api/delete-post id (fn [r] (when (= 200 (:code r)) (antd/success! "删除成功") (rf/dispatch [:posts/fetch {}]))) (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/change-post-status
           (fn [[id status]]
             (api/change-post-status id status
                                     (fn [result]
                                       (when (= 200 (:code result))
                                         (antd/success! "状态修改成功")
                                         (rf/dispatch [:posts/fetch {}])))
                                     (fn [_] (antd/error! "网络错误")))))

;; ────── 首页仪表盘 ──────

(rf/reg-event-db :dashboard/set-stats
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:dashboard :stats] data)
                       (assoc-in [:dashboard :loading?] false))))

(rf/reg-event-fx :dashboard/fetch
                 (fn [{:keys [db]} _]
                   {:db (assoc-in db [:dashboard :loading?] true)
                    :api/get-dashboard-stats nil}))

(rf/reg-fx :api/get-dashboard-stats
           (fn [_]
             (api/get-dashboard-stats
              (fn [r] (when (= 200 (:code r))
                       (rf/dispatch [:dashboard/set-stats (:data r)])))
              (fn [_] (rf/dispatch [:dashboard/set-stats nil])))))

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
              (fn [_] (rf/dispatch [:server/set-data nil])))))

;; ────── 缓存监控 ──────

(rf/reg-event-db :cache/set-info
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:cache :data] data)
                       (assoc-in [:cache :loading?] false))))

(rf/reg-event-fx :cache/fetch-info
                 (fn [{:keys [db]} _]
                   {:db (assoc-in db [:cache :loading?] true)
                    :api/get-cache-info nil}))

(rf/reg-fx :api/get-cache-info
           (fn [_]
             (api/get-cache-info
              (fn [r] (when (= 200 (:code r)) (rf/dispatch [:cache/set-info (:data r)])))
              (fn [_]))))

(rf/reg-event-db :cache/set-names
                 (fn [db [_ data]]
                   (assoc-in db [:cache :names] (or (:cacheNames data) []))))

(rf/reg-event-fx :cache/fetch-names
                 (fn [{:keys [db]} _]
                   {:db db :api/get-cache-names nil}))

(rf/reg-fx :api/get-cache-names
           (fn [_]
             (api/get-cache-names
              (fn [r] (when (= 200 (:code r)) (rf/dispatch [:cache/set-names (:data r)])))
              (fn [_]))))

(rf/reg-event-db :cache/select-name
                 (fn [db [_ cache-name]]
                   (assoc-in db [:cache :selected-name] cache-name)))

(rf/reg-event-db :cache/set-keys
                 (fn [db [_ data]]
                   (assoc-in db [:cache :keys] (or (:keys data) []))))

(rf/reg-event-fx :cache/fetch-keys
                 (fn [{:keys [db]} _]
                   (let [cache-name (get-in db [:cache :selected-name])]
                     (if cache-name
                       {:db db :api/get-cache-keys-by-name cache-name}
                       {:db db}))))

(rf/reg-fx :api/get-cache-keys-by-name
           (fn [cache-name]
             (api/get-cache-keys-by-name
              cache-name
              (fn [r] (when (= 200 (:code r)) (rf/dispatch [:cache/set-keys (:data r)])))
              (fn [_]))))

(rf/reg-event-db :cache/set-value
                 (fn [db [_ value]]
                   (assoc-in db [:cache :value] value)))

(rf/reg-event-db :cache/show-value
                 (fn [db _]
                   (assoc-in db [:cache :value-visible?] true)))

(rf/reg-event-db :cache/close-value
                 (fn [db _]
                   (-> db
                       (assoc-in [:cache :value-visible?] false)
                       (assoc-in [:cache :value] nil))))

(rf/reg-event-fx :cache/fetch-value
                 (fn [_ [_ cache-name cache-key]]
                   {:api/get-cache-value [cache-name cache-key]}))

(rf/reg-fx :api/get-cache-value
           (fn [[cache-name cache-key]]
             (api/get-cache-value
              cache-name cache-key
              (fn [r] (when (= 200 (:code r))
                        (rf/dispatch [:cache/set-value (get-in r [:data :value] "")])
                        (rf/dispatch [:cache/show-value])))
              (fn [_] (antd/error! "获取缓存值失败")))))

(rf/reg-event-fx :cache/clear
                 (fn [{:keys [db]} _]
                   {:db db :api/clear-cache nil}))

(rf/reg-fx :api/clear-cache
           (fn [_]
             (api/clear-cache
              (fn [r]
                (when (= 200 (:code r))
                  (antd/success! "缓存已清空")
                  (rf/dispatch [:cache/fetch-info])
                  (rf/dispatch [:cache/fetch-names])
                  (rf/dispatch [:cache/fetch-keys])))
              (fn [_] (antd/error! "清空缓存失败")))))

(rf/reg-event-fx :cache/clear-name
                 (fn [_ [_ cache-name]]
                   {:api/clear-cache-name cache-name}))

(rf/reg-fx :api/clear-cache-name
           (fn [cache-name]
             (api/clear-cache-name
              cache-name
              (fn [r]
                (when (= 200 (:code r))
                  (antd/success! "缓存已清空")
                  (rf/dispatch [:cache/fetch-info])
                  (rf/dispatch [:cache/fetch-names])
                  (rf/dispatch [:cache/fetch-keys])))
              (fn [_] (antd/error! "清空缓存失败")))))

(rf/reg-event-fx :cache/clear-key
                 (fn [_ [_ cache-name cache-key]]
                   {:api/clear-cache-key [cache-name cache-key]}))

(rf/reg-fx :api/clear-cache-key
           (fn [[cache-name cache-key]]
             (api/clear-cache-key
              cache-name cache-key
              (fn [r]
                (when (= 200 (:code r))
                  (antd/success! "缓存键已清除")
                  (rf/dispatch [:cache/fetch-keys])
                  (rf/dispatch [:cache/fetch-info])))
              (fn [_] (antd/error! "清除缓存键失败")))))

;; ────── 数据源监控 ──────

(rf/reg-event-db :server/set-datasource
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:server :datasource] data)
                       (assoc-in [:server :datasource-loading?] false))))

(rf/reg-event-fx :server/fetch-datasource
                 (fn [{:keys [db]} _]
                   {:db (assoc-in db [:server :datasource-loading?] true)
                    :api/get-datasource nil}))

(rf/reg-fx :api/get-datasource
           (fn [_]
             (api/get-datasource
              (fn [r] (when (= 200 (:code r)) (rf/dispatch [:server/set-datasource (:data r)])))
              (fn [_] (rf/dispatch [:server/set-datasource nil])))))

;; ────── Integrant 依赖监控 ──────

(rf/reg-event-db :integrant/set-data
                 (fn [db [_ data]]
                   (assoc-in db [:integrant :data] data)))

(rf/reg-event-fx :integrant/fetch
                 (fn [{:keys [db]} _]
                   {:db db :api/get-integrant-info nil}))

(rf/reg-fx :api/get-integrant-info
           (fn [_]
             (api/get-integrant-info
              (fn [r] (when (= 200 (:code r)) (rf/dispatch [:integrant/set-data (:data r)])))
              (fn [_]))))

(rf/reg-event-db :integrant/set-trace
                 (fn [db [_ key data]]
                   (assoc-in db [:integrant :trace key] data)))

(rf/reg-event-fx :integrant/toggle-trace
                 (fn [{:keys [db]} [_ key enabled?]]
                   {:db db :api/set-integrant-trace [key enabled?]}))

(rf/reg-fx :api/set-integrant-trace
           (fn [[key enabled?]]
             (api/set-integrant-trace
              key enabled?
              (fn [r] (when (= 200 (:code r)) (rf/dispatch [:integrant/set-trace key (:data r)])))
              (fn [_]))))

(rf/reg-event-fx :integrant/fetch-trace-logs
                 (fn [{:keys [db]} [_ key]]
                   {:db db :api/get-integrant-trace-logs key}))

(rf/reg-fx :api/get-integrant-trace-logs
           (fn [key]
             (api/get-integrant-trace-logs
              key
              (fn [r] (when (= 200 (:code r)) (rf/dispatch [:integrant/set-trace key (:data r)])))
              (fn [_]))))

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

;; ────── 多Tab管理 ──────

(rf/reg-event-fx :tabs/add
                 (fn [{:keys [db]} [_ key label icon]]
                   (let [tabs (get-in db [:tabs :items] [])
                         exists? (some #(= (:key %) key) tabs)
                         refresh-tab (fn [tab]
                                       (cond-> tab
                                         (= (:key tab) key)
                                         (merge (cond-> {}
                                                  (seq label) (assoc :label label)
                                                  icon (assoc :icon icon)))))]
                     (if exists?
                       {:db (-> db
                                (update-in [:tabs :items] #(mapv refresh-tab %))
                                (assoc-in [:tabs :active] key))}
                       {:db (-> db
                                (update-in [:tabs :items] conj {:key key :label label :icon icon :closable (not= key :dashboard)})
                                (assoc-in [:tabs :active] key))}))))

(rf/reg-event-db :tabs/activate
                 (fn [db [_ key]]
                   (assoc-in db [:tabs :active] key)))

(rf/reg-event-fx :tabs/close
                 (fn [{:keys [db]} [_ key]]
                   {:db db
                    :dispatch [:tabs/remove key]}))

(rf/reg-event-fx :tabs/remove
                 (fn [{:keys [db]} [_ key]]
                   (let [tabs (get-in db [:tabs :items] [])
                         active (get-in db [:tabs :active])
                         remaining (filterv #(not= (:key %) key) tabs)]
                     (if (= active key)
                       (let [new-active (if-let [last-rem (last remaining)] (:key last-rem) :dashboard)]
                         {:db (-> db
                                  (assoc-in [:tabs :items] remaining)
                                  (assoc-in [:tabs :active] new-active))
                          :dispatch [:navigate new-active]})
                       {:db (assoc-in db [:tabs :items] remaining)}))))

(rf/reg-event-fx :tabs/remove-others
                 (fn [{:keys [db]} [_ key]]
                   (let [tabs (get-in db [:tabs :items] [])
                         home-tab (first (filter #(= (:key %) :dashboard) tabs))
                         keep-tab (first (filter #(= (:key %) key) tabs))]
                     {:db (-> db
                              (assoc-in [:tabs :items] (filterv some? [home-tab keep-tab]))
                              (assoc-in [:tabs :active] key))
                      :dispatch [:navigate key]})))

(rf/reg-event-fx :tabs/remove-all
                 (fn [{:keys [db]} _]
                   (let [home-tab (first (filter #(= (:key %) :dashboard) (get-in db [:tabs :items] [])))]
                     {:db (-> db
                              (assoc-in [:tabs :items] (if home-tab [home-tab] []))
                              (assoc-in [:tabs :active] :dashboard))
                      :dispatch [:navigate :dashboard]})))

(rf/reg-event-fx :tabs/remove-right
                 (fn [{:keys [db]} [_ key]]
                   (let [tabs (get-in db [:tabs :items] [])
                         idx (first (keep-indexed #(when (= (:key %2) key) %1) tabs))
                         remaining (if idx (subvec tabs 0 (inc idx)) tabs)]
                     {:db (-> db
                              (assoc-in [:tabs :items] remaining)
                              (assoc-in [:tabs :active] key))
                      :dispatch [:navigate key]})))

(rf/reg-fx :tabs/fullscreen!
           (fn [_]
             (let [el (or (.-documentElement js/document) (.-body js/document))]
               (if (.-fullscreenElement js/document)
                 (.exitFullscreen js/document)
                 (.requestFullscreen el)))))

(rf/reg-event-fx :tabs/fullscreen
                 (fn [_ _]
                   {:tabs/fullscreen! nil}))

;; ────── 菜单管理 ──────

(rf/reg-event-db :menus/set-list
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:menus :items] data)
                       (assoc-in [:menus :loading?] false))))

(rf/reg-event-db :menus/set-tree
                 (fn [db [_ data]]
                   (assoc-in db [:menus :tree-data] data)))

(rf/reg-event-fx :menus/fetch
                 (fn [{:keys [db]} _]
                   {:db (assoc-in db [:menus :loading?] true)
                    :api/list-menus nil}))

(rf/reg-event-fx :menus/search
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:menus :loading?] true)
                    :api/list-menus-search params}))

(rf/reg-fx :api/list-menus-search
           (fn [params]
             (api/list-menus
              {}
              (fn [result]
                (when (= 200 (:code result))
                  (let [data (:data result)
                        items (if (sequential? data) data (:rows data []))
                        ;; 客户端过滤
                        filtered (cond->> items
                                   (:menu_name params)
                                   (filter #(clojure.string/includes?
                                             (or (:menu_name %) "")
                                             (:menu_name params)))
                                   (some? (:status params))
                                   (filter #(= (:status params) (:status %))))]
                    (rf/dispatch [:menus/set-list filtered]))))
              (fn [_]))))

(rf/reg-event-fx :menus/fetch-tree
                 (fn [{:keys [db]} _]
                   {:db db
                    :api/menu-tree-for-menus nil}))

(rf/reg-fx :api/menu-tree-for-menus
           (fn [_]
             (api/menu-tree
              (fn [result]
                (when (= 200 (:code result))
                  (rf/dispatch [:menus/set-tree (:data result)])))
              (fn [_]))))

(rf/reg-fx :api/list-menus
           (fn [params]
             (api/list-menus params
                             (fn [result]
                               (when (= 200 (:code result))
                                 (rf/dispatch [:menus/set-list (:data result)])))
                             (fn [_]))))

(rf/reg-event-db :menus/open-modal
                 (fn [db [_ initial-data]]
                   (-> db
                       (assoc-in [:menus :modal-visible?] true)
                       (assoc-in [:menus :editing?] false)
                       (assoc-in [:menus :editing] false)
                       (assoc-in [:menus :form-data] (merge {:menu_type "M" :order_num 0 :status "0" :visible "0" :is_frame "0" :is_cache "0"} initial-data)))))

(rf/reg-event-db :menus/close-modal
                 (fn [db _]
                   (assoc-in db [:menus :modal-visible?] false)))

(rf/reg-event-db :menus/edit
                 (fn [db [_ data]]
                   (-> db
                       (assoc-in [:menus :modal-visible?] true)
                       (assoc-in [:menus :editing?] true)
                       (assoc-in [:menus :editing] true)
                       (assoc-in [:menus :form-data] data))))

(rf/reg-event-fx :menus/submit
                 (fn [{:keys [db]} [_ values]]
                   (let [data (-> values
                                  (update :order_num #(if (seq (str %)) (js/parseInt % 10) 0)))
                         editing (get-in db [:menus :editing])]
                     (if editing
                       {:db (assoc-in db [:menus :modal-visible?] false)
                        :api/update-menu [(:menu_id data) data]}
                       {:db (assoc-in db [:menus :modal-visible?] false)
                        :api/create-menu data}))))

(rf/reg-fx :api/create-menu
           (fn [params]
             (api/create-menu params
                              (fn [result]
                                (when (= 200 (:code result))
                                  (antd/success! "创建成功")
                                  (rf/dispatch [:menus/fetch])))
                              (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/update-menu
           (fn [[id params]]
             (api/update-menu id params
                              (fn [result]
                                (when (= 200 (:code result))
                                  (antd/success! "更新成功")
                                  (rf/dispatch [:menus/fetch])))
                              (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :menus/delete
                 (fn [_ [_ id]]
                   {:api/delete-menu id}))

(rf/reg-event-fx :menus/change-status
                 (fn [_ [_ id status]]
                   {:api/change-menu-status [id status]}))

(rf/reg-fx :api/delete-menu
           (fn [id]
             (api/delete-menu id
                              (fn [result]
                                (when (= 200 (:code result))
                                  (antd/success! "删除成功")
                                  (rf/dispatch [:menus/fetch])))
                              (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/change-menu-status
           (fn [[id status]]
             (api/change-menu-status id status
                                     (fn [result]
                                       (when (= 200 (:code result))
                                         (antd/success! "状态修改成功")
                                         (rf/dispatch [:menus/fetch])))
                                     (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :menus/save-sort
                 (fn [_ [_ items]]
                   {:api/save-menu-sort items}))

(rf/reg-fx :api/save-menu-sort
           (fn [items]
             (api/save-menu-sort items
                                 (fn [result]
                                   (when (= 200 (:code result))
                                     (antd/success! "排序保存成功")
                                     (rf/dispatch [:menus/fetch])))
                                 (fn [_] (antd/error! "网络错误")))))

;; ─── 字典类型 CRUD ────────────────────────────────────────────────────────────

(rf/reg-event-fx :dicts/create-type
                 (fn [_ [_ params]]
                   {:api/create-dict-type params}))

(rf/reg-fx :api/create-dict-type
           (fn [params]
             (api/create-dict-type params
                                   (fn [result]
                                     (when (= 200 (:code result))
                                       (antd/success! "创建成功")
                                       (rf/dispatch [:dicts/fetch-types {}]))
                                     (when (not= 200 (:code result))
                                       (antd/error! (:msg result))))
                                   (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :dicts/update-type
                 (fn [_ [_ id params]]
                   {:api/update-dict-type [id params]}))

(rf/reg-fx :api/update-dict-type
           (fn [[id params]]
             (api/update-dict-type id params
                                   (fn [result]
                                     (when (= 200 (:code result))
                                       (antd/success! "更新成功")
                                       (rf/dispatch [:dicts/fetch-types {}]))
                                     (when (not= 200 (:code result))
                                       (antd/error! (:msg result))))
                                   (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :dicts/delete-type
                 (fn [_ [_ id]]
                   {:api/delete-dict-type id}))

(rf/reg-fx :api/delete-dict-type
           (fn [id]
             (api/delete-dict-type id
                                   (fn [result]
                                     (when (= 200 (:code result))
                                       (antd/success! "删除成功")
                                       (rf/dispatch [:dicts/fetch-types {}]))
                                     (when (not= 200 (:code result))
                                       (antd/error! (:msg result))))
                                   (fn [_] (antd/error! "网络错误")))))

;; ─── 字典数据 CRUD ────────────────────────────────────────────────────────────

(rf/reg-event-fx :dicts/create-data
                 (fn [_ [_ params]]
                   {:api/create-dict-data params}))

(rf/reg-fx :api/create-dict-data
           (fn [params]
             (api/create-dict-data params
                                   (fn [result]
                                     (when (= 200 (:code result))
                                       (antd/success! "创建成功")
                                       (rf/dispatch [:dicts/fetch-data {:dict_type (:dict_type params)}]))
                                     (when (not= 200 (:code result))
                                       (antd/error! (:msg result))))
                                   (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :dicts/update-data
                 (fn [_ [_ id params]]
                   {:api/update-dict-data [id params]}))

(rf/reg-fx :api/update-dict-data
           (fn [[id params]]
             (api/update-dict-data id params
                                   (fn [result]
                                     (when (= 200 (:code result))
                                       (antd/success! "更新成功")
                                       (rf/dispatch [:dicts/fetch-data {:dict_type (:dict_type params)}]))
                                     (when (not= 200 (:code result))
                                       (antd/error! (:msg result))))
                                   (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :dicts/delete-data
                 (fn [_ [_ id]]
                   {:api/delete-dict-data id}))

(rf/reg-fx :api/delete-dict-data
           (fn [id]
             (api/delete-dict-data id
                                   (fn [result]
                                     (when (= 200 (:code result))
                                       (antd/success! "删除成功")
                                       (rf/dispatch [:dicts/fetch-data {}]))
                                     (when (not= 200 (:code result))
                                       (antd/error! (:msg result))))
                                   (fn [_] (antd/error! "网络错误")))))

;; ─── 通知公告 ─────────────────────────────────────────────────────────────────

(rf/reg-event-fx :notices/search
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:notices :loading?] true)
                    :api/list-notices-search params}))

(rf/reg-fx :api/list-notices-search
           (fn [params]
             (api/list-notices {}
                               (fn [result]
                                 (when (= 200 (:code result))
                                   (let [data (:data result)
                                         items (if (sequential? data) data (:rows data []))
                                         filtered (cond->> items
                                                    (:notice_name params)
                                                    (filter #(clojure.string/includes?
                                                              (or (:notice_name %) "")
                                                              (:notice_name params))))]
                                     (rf/dispatch [:notices/set-list {:rows filtered :total (count filtered)}]))))
                               (fn [_]))))

(rf/reg-event-fx :notices/fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:notices :loading?] true)
                    :api/list-notices params}))

(rf/reg-fx :api/list-notices
           (fn [params]
             (api/list-notices params
                               (fn [result]
                                 (when (= 200 (:code result))
                                   (rf/dispatch [:notices/set-list (:data result)])))
                               (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-db :notices/set-list
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))
                         total (if (sequential? data) (count data) (:total data 0))]
                     (assoc db :notices {:items items :total total :loading? false
                                         :modal-visible? false :editing nil :form-data {}}))))

(rf/reg-event-db :notices/open-modal
                 (fn [db _]
                   (assoc db :notices {:items (get-in db [:notices :items] [])
                                       :total (get-in db [:notices :total] 0)
                                       :loading? false
                                       :modal-visible? true :editing nil :form-data {}})))

(rf/reg-event-db :notices/close-modal
                 (fn [db _]
                   (assoc-in db [:notices :modal-visible?] false)))

(rf/reg-event-db :notices/edit
                 (fn [db [_ item]]
                   (-> db
                       (assoc-in [:notices :modal-visible?] true)
                       (assoc-in [:notices :editing] item)
                       (assoc-in [:notices :form-data] item))))

(rf/reg-event-fx :notices/submit
                 (fn [{:keys [db]} [_ values]]
                   (let [editing (get-in db [:notices :editing])]
                     (if editing
                       {:api/update-notice [(:notice_id editing) values]}
                       {:api/create-notice values}))))

(rf/reg-fx :api/create-notice
           (fn [params]
             (api/create-notice params
                                (fn [result]
                                  (when (= 200 (:code result))
                                    (antd/success! "创建成功")
                                    (rf/dispatch [:notices/fetch {}])))
                                (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/update-notice
           (fn [[id params]]
             (api/update-notice id params
                                (fn [result]
                                  (when (= 200 (:code result))
                                    (antd/success! "更新成功")
                                    (rf/dispatch [:notices/fetch {}])))
                                (fn [_] (antd/error! "网络错误")))))

(rf/reg-event-fx :notices/delete
                 (fn [_ [_ id]]
                   {:api/delete-notice id}))

(rf/reg-fx :api/delete-notice
           (fn [id]
             (api/delete-notice id
                                (fn [result]
                                  (when (= 200 (:code result))
                                    (antd/success! "删除成功")
                                    (rf/dispatch [:notices/fetch {}])))
                                (fn [_] (antd/error! "网络错误")))))

;; ─── 用户管理完整事件 ─────────────────────────────────────────────────────────

(rf/reg-event-db :users/open-add
                 (fn [db _]
                   (-> db
                       (assoc-in [:users :modal-visible?] true)
                       (assoc-in [:users :editing] nil)
                       (assoc-in [:users :form-data] {}))))

(rf/reg-event-fx :users/open-edit
                 (fn [_ [_ user-id]]
                   {:api/get-user user-id}))

(rf/reg-event-db :users/edit-user
                 (fn [db [_ user]]
                   (-> db
                       (assoc-in [:users :modal-visible?] true)
                       (assoc-in [:users :editing] user)
                       (assoc-in [:users :form-data] (or user {})))))

(rf/reg-event-fx :users/open-edit-selected
                 (fn [{:keys [db]} _]
                   (let [ids (get-in db [:users :selected-ids] [])]
                     (if (seq ids)
                       {:api/get-user (first ids)}
                       (do (antd/error! "请先选择要修改的用户")
                           {:db db})))))

(rf/reg-event-db :users/close-modal
                 (fn [db _]
                   (assoc-in db [:users :modal-visible?] false)))

(rf/reg-event-db :users/update-query
                 (fn [db [_ field value]]
                   (assoc-in db [:users :query-params field] value)))

(rf/reg-event-fx :users/search
                 (fn [{:keys [db]} _]
                   (let [params (get-in db [:users :query-params] {})
                         size (get-in db [:users :page-size] 10)]
                     {:db (assoc-in db [:users :page] 1)
                      :api/list-users (merge params {:page 1 :size size})})))

(rf/reg-event-fx :users/reset-query
                 (fn [{:keys [db]} _]
                   {:db (-> db
                            (assoc-in [:users :query-params] {})
                            (assoc-in [:users :selected-dept-id] nil)
                            (assoc-in [:users :selected-ids] [])
                            (assoc-in [:users :page] 1)
                            (assoc-in [:users :page-size] 10))
                    :api/list-users {:page 1 :size 10}}))

(rf/reg-event-fx :users/fetch-with-params
                 (fn [{:keys [db]} _]
                   (let [params (get-in db [:users :query-params] {})
                         page (get-in db [:users :page] 1)
                         size (get-in db [:users :page-size] 10)]
                     {:api/list-users (merge params {:page page :size size})})))

(rf/reg-event-db :users/toggle-search
                 (fn [db _]
                   (update-in db [:users :show-search?] not)))

(rf/reg-event-db :users/toggle-column
                 (fn [db [_ col-key]]
                   (update-in db [:users :columns col-key :visible?] not)))

(rf/reg-event-fx :users/create
                 (fn [{:keys [db]} [_ params]]
                   {:api/create-user params}))

(rf/reg-event-fx :users/update
                 (fn [{:keys [db]} [_ id params]]
                   {:api/update-user [id params]}))

(rf/reg-event-fx :users/delete
                 (fn [{:keys [db]} [_ id]]
                   (let [user (first (filter #(= id (:user_id %)) (get-in db [:users :items] [])))]
                     (if (or (= 1 id) (= "admin" (:user_name user)))
                       (do (antd/error! "admin 用户不能删除")
                           {:db db})
                       {:api/delete-user id}))))

(rf/reg-event-fx :users/batch-delete
                 (fn [{:keys [db]} _]
                   (let [ids (get-in db [:users :selected-ids] [])
                         items (get-in db [:users :items] [])
                         admin-ids (->> items
                                        (filter #(= "admin" (:user_name %)))
                                        (map :user_id)
                                        set)]
                     (cond
                       (empty? ids)
                       (do (antd/error! "请先选择要删除的用户") {})

                       (some admin-ids ids)
                       (do (antd/error! "admin 用户不能删除") {:db db})

                       :else
                       {:api/batch-delete-users ids}
                       ))))

(rf/reg-event-db :users/toggle-select
                 (fn [db [_ id]]
                   (let [ids (get-in db [:users :selected-ids] [])]
                     (assoc-in db [:users :selected-ids]
                               (if (some #{id} ids)
                                 (filterv #(not= id %) ids)
                                 (conj ids id))))))

(rf/reg-event-db :users/toggle-select-all
                 (fn [db [_ selected?]]
                   (if selected?
                     (assoc-in db [:users :selected-ids] (mapv :user_id (get-in db [:users :items] [])))
                     (assoc-in db [:users :selected-ids] []))))

(rf/reg-event-fx :users/change-status
                 (fn [{:keys [db]} [_ user-id status]]
                   {:api/change-user-status [user-id status]}))

(rf/reg-event-fx :users/reset-password
                 (fn [{:keys [db]} [_ user-id]]
                   {:db (-> db
                            (assoc-in [:users :reset-pwd-visible?] true)
                            (assoc-in [:users :reset-pwd-username] user-id)
                            (assoc-in [:users :reset-pwd-value] "123456"))}))

(rf/reg-event-fx :users/submit-reset-password
                 (fn [{:keys [db]} [_ values]]
                   (let [user-id (get-in db [:users :reset-pwd-username])
                         new-pwd (:password values "123456")]
                     {:db (assoc-in db [:users :reset-pwd-visible?] false)
                      :api/reset-user-password [user-id new-pwd]})))

(rf/reg-event-db :users/view-detail
                 (fn [db [_ user-id]]
                   (let [items (get-in db [:users :items] [])
                         user (first (filter #(= user-id (:user_id %)) items))]
                     (-> db
                         (assoc-in [:users :detail-visible?] true)
                         (assoc-in [:users :detail-data] user)))))

(rf/reg-event-db :users/close-detail
                 (fn [db _]
                   (assoc-in db [:users :detail-visible?] false)))

(rf/reg-event-fx :users/auth-role
                 (fn [{:keys [db]} [_ user-id]]
                   {:db (-> db
                            (assoc-in [:users :auth-role-visible?] true)
                            (assoc-in [:users :auth-role-user]
                                      (first (filter #(= user-id (:user_id %))
                                                     (get-in db [:users :items] []))))
                            (assoc-in [:users :auth-role-ids] []))
                    :api/get-user-roles user-id
                    :api/list-role-options nil}))

;; ─── API 注册 ─────────────────────────────────────────────────────────────────

(rf/reg-fx :api/list-users
           (fn [params]
             (api/list-users params
                             (fn [result]
                               (when (= 200 (:code result))
                                 (rf/dispatch [:users/set-list (:data result)])))
                             (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/create-user
           (fn [params]
             (api/create-user params
                              (fn [result]
                                (when (= 200 (:code result))
                                  (antd/success! "创建成功")
                                  (rf/dispatch [:users/close-modal])
                                  (rf/dispatch [:users/fetch {}]))
                                (when (not= 200 (:code result))
                                  (antd/error! (:msg result))))
                              (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/update-user
           (fn [[id params]]
             (api/update-user id params
                              (fn [result]
                                (when (= 200 (:code result))
                                  (antd/success! "更新成功")
                                  (rf/dispatch [:users/close-modal])
                                  (rf/dispatch [:users/fetch {}]))
                                (when (not= 200 (:code result))
                                  (antd/error! (:msg result))))
                              (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/delete-user
           (fn [id]
             (api/delete-user id
                              (fn [result]
                                (when (= 200 (:code result))
                                  (antd/success! "删除成功")
                                  (rf/dispatch [:users/fetch {}]))
                                (when (not= 200 (:code result))
                                  (antd/error! (:msg result))))
                              (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/get-user
           (fn [user-id]
             (api/get-user user-id
                           (fn [result]
                             (when (= 200 (:code result))
                               (rf/dispatch [:users/edit-user (:data result)])))
                           (fn [_] (antd/error! "获取用户详情失败")))))

(rf/reg-fx :api/batch-delete-users
           (fn [ids]
             (api/delete-user (.join (clj->js ids) ",")
                              (fn [result]
                                (when (= 200 (:code result))
                                  (antd/success! "删除成功")
                                  (rf/dispatch [:users/fetch {}]))
                                (when (not= 200 (:code result))
                                  (antd/error! (:msg result))))
                              (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/change-user-status
           (fn [[user-id status]]
             (api/change-user-status user-id status
                                     (fn [result]
                                       (when (= 200 (:code result))
                                         (antd/success! "状态修改成功")
                                         (rf/dispatch [:users/fetch {}])))
                                     (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/reset-user-password
           (fn [[user-id new-pwd]]
             (api/reset-user-password user-id new-pwd
                                      (fn [result]
                                        (when (= 200 (:code result))
                                          (antd/success! "密码重置成功")))
                                      (fn [_] (antd/error! "网络错误")))))

(rf/reg-fx :api/get-user-roles
           (fn [user-id]
             (api/get-user-roles user-id
                                 (fn [result]
                                   (when (= 200 (:code result))
                                     (rf/dispatch [:users/set-auth-role-ids (:data result)])))
                                 (fn [_] (antd/error! "获取用户角色失败")))))

(rf/reg-fx :api/update-user-roles
           (fn [[user-id role-ids]]
             (api/update-user-roles user-id role-ids
                                    (fn [result]
                                      (when (= 200 (:code result))
                                        (antd/success! "角色分配成功")
                                        (rf/dispatch [:users/close-auth-role])
                                        (rf/dispatch [:users/fetch-with-params]))
                                      (when (not= 200 (:code result))
                                        (antd/error! (:msg result))))
                                    (fn [_] (antd/error! "角色分配失败")))))

;; ─── 路由导航效果 ────────────────────────────────────────────────────────────

(rf/reg-fx :router/navigate!
           (fn [page]
             (router/navigate! page)))

;; ─── 用户表单事件 ─────────────────────────────────────────────────────────────

(rf/reg-event-fx :users/submit
                 (fn [{:keys [db]} [_ values]]
                   (let [editing (get-in db [:users :editing])]
                     (if editing
                       {:api/update-user [(:user_id editing) values]}
                       {:api/create-user values}))))

(rf/reg-event-db :users/close-reset-password
                 (fn [db _]
                   (assoc-in db [:users :reset-pwd-visible?] false)))

(rf/reg-event-db :users/close-auth-role
                 (fn [db _]
                   (assoc-in db [:users :auth-role-visible?] false)))

(rf/reg-event-db :users/set-auth-role-ids
                 (fn [db [_ roles]]
                   (assoc-in db [:users :auth-role-ids] (mapv :role_id roles))))

(rf/reg-event-db :users/set-auth-role-selection
                 (fn [db [_ role-ids]]
                   (assoc-in db [:users :auth-role-ids] (mapv #(js/parseInt % 10) role-ids))))

(rf/reg-event-fx :users/submit-auth-role
                 (fn [{:keys [db]} _]
                   (let [user-id (:user_id (get-in db [:users :auth-role-user]))
                         role-ids (get-in db [:users :auth-role-ids] [])]
                     {:api/update-user-roles [user-id role-ids]})))

;; ─── 用户管理辅助事件 ─────────────────────────────────────────────────────────

(rf/reg-event-db :users/select-dept
                 (fn [db [_ dept-id]]
                   (assoc-in db [:users :selected-dept-id] dept-id)))

(rf/reg-event-fx :users/change-page
                 (fn [{:keys [db]} [_ page page-size]]
                   {:db (-> db
                            (assoc-in [:users :page] page)
                            (assoc-in [:users :page-size] page-size))
                    :dispatch [:users/fetch-with-params]}))

(rf/reg-event-db :users/set-selected
                 (fn [db [_ ids]]
                   (assoc-in db [:users :selected-ids] (mapv #(js/parseInt % 10) ids))))

(rf/reg-event-db :users/set-role-options
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))]
                     (assoc-in db [:users :role-options] items))))

(rf/reg-event-db :users/set-post-options
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))]
                     (assoc-in db [:users :post-options] items))))

(rf/reg-event-fx :users/fetch-options
                 (fn [_ _]
                   {:api/list-role-options nil
                    :api/list-post-options nil}))

(rf/reg-fx :api/list-role-options
           (fn [_]
             (api/list-roles {:page 1 :size 1000}
                             (fn [result]
                               (when (= 200 (:code result))
                                 (rf/dispatch [:users/set-role-options (:data result)])))
                             (fn [_]))))

(rf/reg-fx :api/list-post-options
           (fn [_]
             (api/list-posts {:page 1 :size 1000}
                             (fn [result]
                               (when (= 200 (:code result))
                                 (rf/dispatch [:users/set-post-options (:data result)])))
                             (fn [_]))))

;; ─── 部门树构建工具 ───────────────────────────────────────────────────────────

(defn- build-dept-tree
  [items parent-id]
  (->> items
       (filter #(= parent-id (:parent_id %)))
       (mapv (fn [d]
               (let [children (build-dept-tree items (:dept_id d))]
                 (if (seq children)
                   (assoc d :children children)
                   d))))))

(rf/reg-event-db :users/toggle-dept-expand
                 (fn [db [_ dept-id]]
                   (let [expanded (get-in db [:users :expanded-dept-ids] #{})]
                     (assoc-in db [:users :expanded-dept-ids]
                               (if (contains? expanded dept-id)
                                 (disj expanded dept-id)
                                 (conj expanded dept-id))))))

(rf/reg-event-db :users/collapse-all-depts
                 (fn [db _]
                   (assoc-in db [:users :expanded-dept-ids] #{})))



;; ─── 办公：请假申请 ──────────────────────────────────────────────────
(rf/reg-event-fx :leave/fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:leave :loading?] true)
                    :api/oa-list-leaves (or params {})}))

(rf/reg-fx :api/oa-list-leaves
           (fn [params]
             (api/oa-list-leaves params
                                 (fn [r] (when (= 200 (:code r))
                                           (rf/dispatch [:leave/set-list (:data r)])))
                                 (fn [_] (antd/error! "加载请假单失败")))))

(rf/reg-event-db :leave/set-list
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))
                         total (if (sequential? data) (count data) (:total data 0))]
                     (assoc db :leave {:items items :total total :loading? false
                                       :modal-visible? false :submitting? false}))))

(rf/reg-event-db :leave/open-modal
                 (fn [db _]
                   (assoc db :leave {:items (get-in db [:leave :items] [])
                                     :total (get-in db [:leave :total] 0)
                                     :loading? false :modal-visible? true :submitting? false})))

(rf/reg-event-db :leave/close-modal
                 (fn [db _]
                   (assoc-in db [:leave :modal-visible?] false)))

(rf/reg-event-fx :leave/submit
                 (fn [{:keys [db]} [_ values]]
                   {:db (assoc-in db [:leave :submitting?] true)
                    :api/oa-start-leave values}))

(rf/reg-fx :api/oa-start-leave
           (fn [params]
             (api/oa-start-leave params
                                 (fn [r]
                                   (when (= 200 (:code r))
                                     (rf/dispatch [:leave/close-modal])
                                     (antd/success! "请假申请已提交，进入审批")
                                     (rf/dispatch [:leave/fetch {}])))
                                 (fn [_] (antd/error! "提交失败")))))

(rf/reg-event-fx :leave/delete
                 (fn [_ [_ id]]
                   {:api/oa-delete-leave id}))

(rf/reg-fx :api/oa-delete-leave
           (fn [id]
             (api/oa-delete-leave id
                                  (fn [r] (when (= 200 (:code r))
                                            (antd/success! "删除成功")
                                            (rf/dispatch [:leave/fetch {}])))
                                  (fn [_] (antd/error! "删除失败")))))


;; ─── 办公：BPM 待办/已办 ────────────────────────────────────────────
(rf/reg-event-fx :bpm/todo-fetch
                 (fn [{:keys [db]} _]
                   {:db (assoc-in db [:bpm-todo :loading?] true)
                    :api/bpm-list-todo nil}))

(rf/reg-fx :api/bpm-list-todo
           (fn [_]
             (api/bpm-list-todo
              (fn [r] (when (= 200 (:code r))
                        (rf/dispatch [:bpm/todo-set-list (:data r)])))
              (fn [_] (antd/error! "加载待办失败")))))

(rf/reg-event-db :bpm/todo-set-list
                 (fn [db [_ data]]
                   (let [rows (:rows data [])]
                     (assoc db :bpm-todo {:items rows :total (:total data 0)
                                          :loading? false :modal-visible? false
                                          :current nil :submitting? false}))))

(rf/reg-event-db :bpm/todo-open-approve
                 (fn [db [_ task]]
                   (assoc db :bpm-todo {:items (get-in db [:bpm-todo :items] [])
                                        :total (get-in db [:bpm-todo :total] 0)
                                        :loading? false :modal-visible? true
                                        :current task :action "approve" :submitting? false})))

(rf/reg-event-db :bpm/todo-open-reject
                 (fn [db [_ task]]
                   (assoc db :bpm-todo {:items (get-in db [:bpm-todo :items] [])
                                        :total (get-in db [:bpm-todo :total] 0)
                                        :loading? false :modal-visible? true
                                        :current task :action "reject" :submitting? false})))

(rf/reg-event-db :bpm/todo-close
                 (fn [db _]
                   (assoc-in db [:bpm-todo :modal-visible?] false)))

(rf/reg-event-fx :bpm/todo-submit
                 (fn [{:keys [db]} [_ comment]]
                   (let [task (get-in db [:bpm-todo :current])
                         action (get-in db [:bpm-todo :action])]
                     {:db (assoc-in db [:bpm-todo :submitting?] true)
                      :api/bpm-approve-task [(:task-id task) comment action]})))

(rf/reg-fx :api/bpm-approve-task
           (fn [[task-id comment action]]
             (let [f (if (= action "approve") api/bpm-approve-task api/bpm-reject-task)]
               (f task-id comment
                  (fn [r] (when (= 200 (:code r))
                            (rf/dispatch [:bpm/todo-close])
                            (antd/success! (if (= action "approve") "审批通过" "已驳回"))
                            (rf/dispatch [:bpm/todo-fetch])))
                  (fn [_] (antd/error! "操作失败"))))))

(rf/reg-event-fx :bpm/done-fetch
                 (fn [{:keys [db]} _]
                   {:db (assoc-in db [:bpm-done :loading?] true)
                    :api/bpm-list-done nil}))

(rf/reg-fx :api/bpm-list-done
           (fn [_]
             (api/bpm-list-done
              (fn [r] (when (= 200 (:code r))
                        (rf/dispatch [:bpm/done-set-list (:data r)])))
              (fn [_] (antd/error! "加载已办失败")))))

(rf/reg-event-db :bpm/done-set-list
                 (fn [db [_ data]]
                   (let [rows (:rows data [])]
                     (assoc db :bpm-done {:items rows :total (count rows) :loading? false}))))


;; ─── 办公：我的流程 ──────────────────────────────────────────────────
(rf/reg-event-fx :bpm/instance-fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:bpm-instance :loading?] true)
                    :api/bpm-list-instances (or params {})}))

(rf/reg-fx :api/bpm-list-instances
           (fn [params]
             (api/bpm-list-instances params
                                     (fn [r] (when (= 200 (:code r))
                                               (rf/dispatch [:bpm/instance-set-list (:data r)])))
                                     (fn [_] (antd/error! "加载流程失败")))))

(rf/reg-event-db :bpm/instance-set-list
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))
                         total (if (sequential? data) (count data) (:total data 0))]
                     (assoc db :bpm-instance {:items items :total total :loading? false}))))

;; ─── 办公：流程模型 ──────────────────────────────────────────────────
(rf/reg-event-fx :bpm/model-fetch
                 (fn [{:keys [db]} [_ params]]
                   {:db (assoc-in db [:bpm-model :loading?] true)
                    :api/bpm-list-models (or params {})}))

(rf/reg-fx :api/bpm-list-models
           (fn [params]
             (api/bpm-list-models params
                                  (fn [r] (when (= 200 (:code r))
                                            (rf/dispatch [:bpm/model-set-list (:data r)])))
                                  (fn [_] (antd/error! "加载流程模型失败")))))

(rf/reg-event-db :bpm/model-set-list
                 (fn [db [_ data]]
                   (let [items (if (sequential? data) data (:rows data []))
                         total (if (sequential? data) (count data) (:total data 0))]
                     (assoc db :bpm-model {:items items :total total :loading? false}))))

(rf/reg-event-fx :bpm/model-deploy
                 (fn [{:keys [db]} [_ model-id]]
                   {:db (assoc-in db [:bpm-model :deploying?] true)
                    :api/bpm-deploy-model model-id}))

(rf/reg-fx :api/bpm-deploy-model
           (fn [model-id]
             (api/bpm-deploy-model model-id
                                   (fn [r] (when (= 200 (:code r))
                                             (antd/success! "部署成功")
                                             (rf/dispatch [:bpm/model-fetch {}])))
                                   (fn [_] (antd/error! "部署失败")))))

;; ─── 办公：HRM 员工 ──────────────────────────────────────────────────
(rf/reg-event-fx :hrm/fetch (fn [{:keys [db]} [_ p]] {:db (assoc-in db [:hrm :loading?] true) :api/hrm-list p}))
(rf/reg-fx :api/hrm-list (fn [p] (api/hrm-list-employees p (fn [r] (when (= 200 (:code r)) (rf/dispatch [:hrm/set-list (:data r)]))) (fn [_] (antd/error! "加载员工失败")))))
(rf/reg-event-db :hrm/set-list (fn [db [_ d]] (let [items (if (sequential? d) d (:rows d []))] (assoc db :hrm {:items items :total (:total d 0) :loading? false :modal-visible? false :editing nil :form-data {}}))))
(rf/reg-event-db :hrm/open (fn [db _] (assoc db :hrm {:items (get-in db [:hrm :items] []) :total (get-in db [:hrm :total] 0) :loading? false :modal-visible? true :editing nil :form-data {}})))
(rf/reg-event-db :hrm/edit (fn [db [_ it]] (-> db (assoc-in [:hrm :modal-visible?] true) (assoc-in [:hrm :editing] it) (assoc-in [:hrm :form-data] it))))
(rf/reg-event-db :hrm/close (fn [db _] (assoc-in db [:hrm :modal-visible?] false)))
(rf/reg-event-fx :hrm/submit (fn [{:keys [db]} [_ v]] (let [e (get-in db [:hrm :editing])] (if e {:api/hrm-create v} {:api/hrm-create v}))))
(rf/reg-fx :api/hrm-create (fn [p] (api/hrm-create-employee p (fn [r] (when (= 200 (:code r)) (rf/dispatch [:hrm/close]) (antd/success! "保存成功") (rf/dispatch [:hrm/fetch {}]))) (fn [_] (antd/error! "保存失败")))))
(rf/reg-event-fx :hrm/delete (fn [_ [_ id]] {:api/hrm-del id}))
(rf/reg-fx :api/hrm-del (fn [id] (api/hrm-delete-employee id (fn [r] (when (= 200 (:code r)) (antd/success! "删除成功") (rf/dispatch [:hrm/fetch {}]))) (fn [_] (antd/error! "删除失败")))))

;; ─── 办公：OA 日程 ──────────────────────────────────────────────────
(rf/reg-event-fx :oa-calendar/fetch (fn [{:keys [db]} [_ p]] {:db (assoc-in db [:oa-calendar :loading?] true) :api/oa-calendar-list p}))
(rf/reg-fx :api/oa-calendar-list (fn [p] (api/oa-list-calendars p (fn [r] (when (= 200 (:code r)) (rf/dispatch [:oa-calendar/set-list (:data r)]))) (fn [_] (antd/error! "加载日程失败")))))
(rf/reg-event-db :oa-calendar/set-list (fn [db [_ d]] (let [items (if (sequential? d) d (:rows d []))] (assoc db :oa-calendar {:items items :total (:total d 0) :loading? false :modal-visible? false :form-data {}}))))
(rf/reg-event-db :oa-calendar/open (fn [db _] (assoc db :oa-calendar {:items (get-in db [:oa-calendar :items] []) :total (get-in db [:oa-calendar :total] 0) :loading? false :modal-visible? true :form-data {}})))
(rf/reg-event-db :oa-calendar/close (fn [db _] (assoc-in db [:oa-calendar :modal-visible?] false)))
(rf/reg-event-fx :oa-calendar/submit (fn [_ [_ v]] {:api/oa-calendar-create v}))
(rf/reg-fx :api/oa-calendar-create (fn [p] (api/oa-create-calendar p (fn [r] (when (= 200 (:code r)) (rf/dispatch [:oa-calendar/close]) (antd/success! "已添加") (rf/dispatch [:oa-calendar/fetch {}]))) (fn [_] (antd/error! "添加失败")))))
(rf/reg-event-fx :oa-calendar/delete (fn [_ [_ id]] {:api/oa-calendar-del id}))
(rf/reg-fx :api/oa-calendar-del (fn [id] (api/oa-delete-calendar id (fn [r] (when (= 200 (:code r)) (antd/success! "删除成功") (rf/dispatch [:oa-calendar/fetch {}]))) (fn [_] (antd/error! "删除失败")))))

;; ─── 办公：OA 会议 ──────────────────────────────────────────────────
(rf/reg-event-fx :oa-meeting/fetch (fn [{:keys [db]} [_ p]] {:db (assoc-in db [:oa-meeting :loading?] true) :api/oa-meeting-list p}))
(rf/reg-fx :api/oa-meeting-list (fn [p] (api/oa-list-meetings p (fn [r] (when (= 200 (:code r)) (rf/dispatch [:oa-meeting/set-list (:data r)]))) (fn [_] (antd/error! "加载会议失败")))))
(rf/reg-event-db :oa-meeting/set-list (fn [db [_ d]] (let [items (if (sequential? d) d (:rows d []))] (assoc db :oa-meeting {:items items :total (:total d 0) :loading? false :modal-visible? false :form-data {}}))))
(rf/reg-event-db :oa-meeting/open (fn [db _] (assoc db :oa-meeting {:items (get-in db [:oa-meeting :items] []) :total (get-in db [:oa-meeting :total] 0) :loading? false :modal-visible? true :form-data {}})))
(rf/reg-event-db :oa-meeting/close (fn [db _] (assoc-in db [:oa-meeting :modal-visible?] false)))
(rf/reg-event-fx :oa-meeting/submit (fn [_ [_ v]] {:api/oa-meeting-create v}))
(rf/reg-fx :api/oa-meeting-create (fn [p] (api/oa-create-meeting p (fn [r] (when (= 200 (:code r)) (rf/dispatch [:oa-meeting/close]) (antd/success! "已创建") (rf/dispatch [:oa-meeting/fetch {}]))) (fn [_] (antd/error! "创建失败")))))
(rf/reg-event-fx :oa-meeting/delete (fn [_ [_ id]] {:api/oa-meeting-del id}))
(rf/reg-fx :api/oa-meeting-del (fn [id] (api/oa-delete-meeting id (fn [r] (when (= 200 (:code r)) (antd/success! "删除成功") (rf/dispatch [:oa-meeting/fetch {}]))) (fn [_] (antd/error! "删除失败")))))

;; ─── 办公：CRM 客户 ──────────────────────────────────────────────────
(rf/reg-event-fx :crm/fetch (fn [{:keys [db]} [_ p]] {:db (assoc-in db [:crm :loading?] true) :api/crm-list p}))
(rf/reg-fx :api/crm-list (fn [p] (api/crm-list-customers p (fn [r] (when (= 200 (:code r)) (rf/dispatch [:crm/set-list (:data r)]))) (fn [_] (antd/error! "加载客户失败")))))
(rf/reg-event-db :crm/set-list (fn [db [_ d]] (let [items (if (sequential? d) d (:rows d []))] (assoc db :crm {:items items :total (:total d 0) :loading? false :modal-visible? false :editing nil :form-data {}}))))
(rf/reg-event-db :crm/open (fn [db _] (assoc db :crm {:items (get-in db [:crm :items] []) :total (get-in db [:crm :total] 0) :loading? false :modal-visible? true :editing nil :form-data {}})))
(rf/reg-event-db :crm/edit (fn [db [_ it]] (-> db (assoc-in [:crm :modal-visible?] true) (assoc-in [:crm :editing] it) (assoc-in [:crm :form-data] it))))
(rf/reg-event-db :crm/close (fn [db _] (assoc-in db [:crm :modal-visible?] false)))
(rf/reg-event-fx :crm/submit (fn [{:keys [db]} [_ v]] (let [e (get-in db [:crm :editing])] (if e {:api/crm-update [(:customer_id e) v]} {:api/crm-create v}))))
(rf/reg-fx :api/crm-create (fn [p] (api/crm-create-customer p (fn [r] (when (= 200 (:code r)) (rf/dispatch [:crm/close]) (antd/success! "保存成功") (rf/dispatch [:crm/fetch {}]))) (fn [_] (antd/error! "保存失败")))))
(rf/reg-fx :api/crm-update (fn [[id p]] (api/crm-update-customer id p (fn [r] (when (= 200 (:code r)) (rf/dispatch [:crm/close]) (antd/success! "保存成功") (rf/dispatch [:crm/fetch {}]))) (fn [_] (antd/error! "保存失败")))))
(rf/reg-event-fx :crm/delete (fn [_ [_ id]] {:api/crm-del id}))
(rf/reg-fx :api/crm-del (fn [id] (api/crm-delete-customer id (fn [r] (when (= 200 (:code r)) (antd/success! "删除成功") (rf/dispatch [:crm/fetch {}]))) (fn [_] (antd/error! "删除失败")))))
