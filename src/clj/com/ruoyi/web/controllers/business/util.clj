(ns com.ruoyi.web.controllers.business.util
  "业务控制器通用工具."
  (:require
    [com.ruoyi.web.middleware.params :as params-mw]
    [ring.util.response :as response]))


(defn kquery
  "把查询串参数转成 keyword key map, 供领域服务读取.

   :params 是 ring 合并后的查询串 + 表单字段, 排在后面以保持后者覆盖前者的语义.
   路由组声明 :params/parse? 时参数在入口已解析成整数, 这里的再归一因此是幂等的;
   控制器单测按 string 键构造请求, 结果一致."
  [request]
  (-> (params-mw/parse-params request) :query-params))

(defn wrap-err
  "把领域异常转成失败响应: ex-data 里的整数 :status 作为业务码(400/403/404/409),
   否则 500.

   之前 6 个业务控制器各自复制了一份硬编码 500 的版本, 领域层抛出的
   :status 400/404/409 全被吞成 500, 前端无法区分 '我填错了' 与 '服务端挂了'."
  [f]
  (try
    (f)
    (catch Exception e
      (let [status (:status (ex-data e))]
        (-> (response/response {:code (if (integer? status) status 500)
                                :msg (.getMessage e)})
            (response/content-type "application/json"))))))
