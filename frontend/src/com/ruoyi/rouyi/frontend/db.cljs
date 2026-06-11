(ns com.ruoyi.rouyi.frontend.db
  "前端应用初始状态。")

(def default-db
  {:page :login
   :auth {:token nil :user nil :loading? false}
   :users {:loading? false :items [] :total 0 :filters {}}
   :roles {:loading? false :items []}
   :menus {:loading? false :items [] :tree []}
   :depts {:loading? false :items []}
   :posts {:loading? false :items []}
   :dicts {:loading? false :types [] :data []}
   :configs {:loading? false :items []}
   :oper-logs {:loading? false :items [] :total 0}
   :login-logs {:loading? false :items [] :total 0}
   :online-users {:loading? false :items [] :total 0}
   :jobs {:loading? false :items [] :total 0 :filters {}}
   :job-logs {:loading? false :items [] :total 0}
   :profile {:loading? false}
   :notification nil})
