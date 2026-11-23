(ns com.ruoyi.web.controllers.business.util
  "业务控制器通用工具."
  (:require
    [com.ruoyi.web.middleware.params :as params-mw]))


(defn kquery
  "把查询串参数转成 keyword key map, 供领域服务读取.

   :params 是 ring 合并后的查询串 + 表单字段, 排在后面以保持后者覆盖前者的语义.
   路由组声明 :params/parse? 时参数在入口已解析成整数, 这里的再归一因此是幂等的;
   控制器单测按 string 键构造请求, 结果一致."
  [request]
  (-> (params-mw/parse-params request) :query-params))