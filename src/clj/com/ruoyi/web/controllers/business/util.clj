(ns com.ruoyi.web.controllers.business.util
  "业务控制器通用工具.")


(defn kquery
  "把 :query-params(string key)转为 keyword key map,供领域服务读取."
  [request]
  (into {}
        (map (fn [[k v]] [(keyword k) v]))
        (:query-params request)))
