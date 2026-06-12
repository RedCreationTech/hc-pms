(ns com.ruoyi.rouyi.web.controllers.monitor
  "系统监控控制器，提供服务监控、数据源监控等。"
  (:require
   [ring.util.response :as response]))

(defn- ok ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn server-info
  "获取服务器信息。"
  [_ _]
  (ok {:os (System/getProperty "os.name")
       :arch (System/getProperty "os.arch")
       :javaVersion (System/getProperty "java.version")
       :javaVm (System/getProperty "java.vm.name")
       :processors (.availableProcessors (Runtime/getRuntime))
       :maxMemory (quot (.maxMemory (Runtime/getRuntime)) 1048576)
       :totalMemory (quot (.totalMemory (Runtime/getRuntime)) 1048576)
       :freeMemory (quot (.freeMemory (Runtime/getRuntime)) 1048576)}))

(defn datasource-info
  "获取数据源监控信息。"
  [{:keys [query-fn]} _]
  (try
    (let [result (query-fn :datasource-info {})]
      (ok result))
    (catch Exception e
      (ok {:status "error" :message (.getMessage e)}))))
