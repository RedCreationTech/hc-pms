(ns com.ruoyi.frontend.db
  "前端应用初始状态。")

(defn- get-stored-token
  "从 localStorage 读取保存的 token。"
  []
  (try
    (.getItem js/localStorage "ruoyi_token")
    (catch js/Error _ nil)))

(defn- get-stored-user
  "从 localStorage 读取保存的用户信息。"
  []
  (try
    (when-let [s (.getItem js/localStorage "ruoyi_user")]
      (js->clj (.parse js/JSON s) :keywordize-keys true))
    (catch js/Error _ nil)))

(def default-db
  (let [token (get-stored-token)
        user  (get-stored-user)]
    {:page (if token :dashboard :login)
     :tabs {:items [{:key :dashboard :label "首页" :closable false}]
            :active :dashboard}
     :auth {:token token :user user :loading? false}
     :theme {:mode :light :primary-color "#1677ff" :compact? false}
     :users {:loading? false :items [] :total 0
             :query-params {} :page 1 :page-size 10
             :selected-ids [] :show-search? true
             :import-visible? false :import-loading? false :import-file nil
             :columns {:user_id {:label "用户编号" :visible? true}
                       :user_name {:label "用户名称" :visible? true}
                       :nick_name {:label "用户昵称" :visible? true}
                       :dept_name {:label "部门" :visible? true}
                       :phonenumber {:label "手机号码" :visible? true}
                       :status {:label "状态" :visible? true}
                       :create_time {:label "创建时间" :visible? true}}}
     :roles {:loading? false :items [] :query-params {}}
     :menus {:loading? false :items [] :tree []}
     :depts {:loading? false :items [] :tree []}
     :posts {:loading? false :items [] :total 0 :query-params {}}
     :dicts {:loading? false :types [] :data []}
     :configs {:loading? false :items []}
     :notices {:loading? false :items [] :total 0}
     :oper-logs {:loading? false :items [] :total 0}
     :login-logs {:loading? false :items [] :total 0}
     :online-users {:loading? false :items [] :total 0}
     :jobs {:loading? false :items [] :total 0 :filters {}}
     :job-logs {:loading? false :items [] :total 0}
     :profile {:loading? false}
     :dashboard {:loading? false :stats nil}
     :server {:loading? false :data nil}
     :integrant {:data nil :trace {}}
     :cache {:loading? false :data nil :names [] :selected-name nil :keys [] :value nil :value-visible? false}
     :file {:items [] :loading? false}
     :fb {:items [] :selected-id nil :code-visible? false}
     :form-templates {:items [] :loading? false :modal-visible? false :drawer-visible? false :saving? false}
     :gen {:tables-loading? false :tables [] :selected-tables [] :preview-loading? false :preview-visible? false}
     :notification nil}))
