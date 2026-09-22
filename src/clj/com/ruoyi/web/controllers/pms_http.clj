(ns com.ruoyi.web.controllers.pms-http
  "PMS扩展控制器共享的认证, 参数读取与安全HTTP响应."
  (:require [clojure.tools.logging :as log]
            [com.ruoyi.domain.pms.service :as pms]
            [ring.util.response :as response]))

(defn reply
  "按实际HTTP状态返回项目接口信封."
  [status message data]
  (-> (response/response {:code status :msg message :data data})
      (response/status status)
      (response/content-type "application/json")))

(defn invoke
  "解析实时数据库身份, 安全处理领域错误和未知异常."
  [svc request f]
  (try
    (reply 200 "操作成功" (f svc (pms/actor svc (:identity request))))
    (catch clojure.lang.ExceptionInfo e
      (if (:pms-error (ex-data e))
        (reply (:status (ex-data e)) (.getMessage e) nil)
        (do (log/error e "PMS扩展请求失败")
            (reply 500 "服务暂时不可用,请稍后重试" nil))))
    (catch Exception e
      (log/error e "PMS扩展请求失败")
      (reply 500 "服务暂时不可用,请稍后重试" nil))))

(defn project-id
  "读取路由中的项目标识."
  [request]
  (get-in request [:path-params :id]))

(defn param
  "读取路由中的业务对象标识."
  [request key]
  (get-in request [:path-params key]))
