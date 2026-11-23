(ns com.ruoyi.web.middleware.params
  "把查询串参数在入口处一次性解析成领域层要用的形状.

   现状: 每个控制器各自归一 (:query-params 是字符串键的字符串值, :params 是
   ring 合并后的同类映射), 领域层再逐字段 Integer/parseInt. 业务域里有 19 处
   bu/kquery 调用, 加上系统模块各写各的, 属于同一个解析动作的多份复制,
   也是非法查询串变成 500 的来源.

   仅在显式声明 :params/parse? 的路由组上生效; 未声明的路由组保持原样,
   不受本中间件影响.")


(defn- multipart?
  "multipart 部件是带 :tempfile 的映射, 不能当数值解析."
  [v]
  (and (map? v) (contains? v :tempfile)))


(defn- numeric-string?
  [v]
  (boolean (and (string? v) (re-matches #"-?\d+" v))))


(defn- coerce-value
  "数字串转成整数; 只放行会往返成同一字符串的值, 保住 007, 1.0, 1e3 这类原样."
  [v]
  (if (numeric-string? v)
    (let [n (parse-long v)]
      (if (and n (= (str n) v)) n v))
    v))


(defn- params-priority
  "路由可以声明 params/priority: :form (默认) 或 :query.

   PMS 读 `?page=0` 这样的查询串做校验, 同时创建接口把表单当 JSON 体,
   因此声明 :query; 其余业务模块需要 multipart 表单字段覆盖同名查询串参数."
  [request]
  (get-in request [:reitit.core/match :data :params/priority] :form))


(defn- merge-sources
  "合并查询串与表单字段; 同名时后合并的一方生效."
  [query form priority]
  (if (= :query priority)
    (merge form query)
    (merge query form)))


(defn- to-keyword
  [k]
  (if (keyword? k) k (keyword (str k))))


(defn parse-params
  "把查询串参数解析成 keyword 键; 数值字符串转成整数.
   两个来源同名时的取值优先级由 params/priority 决定 (见 params-priority)."
  ([request] (parse-params request :form))
  ([request priority]
   (let [fields (merge-sources (:query-params request) (:params request) priority)
         entries (map (fn [[k v]]
                        [(to-keyword k) (if (multipart? v) v (coerce-value v))])
                      fields)
         parsed (into {} entries)]
     (assoc request ::parsed? true
            :query-params parsed
            :params parsed))))


(defn wrap-parse-params
  "按路由声明 :params/parse? 决定是否解析; 已经解析过的请求原样通过."
  [handler]
  (fn [request]
    (if (and (get-in request [:reitit.core/match :data :params/parse?])
             (not (::parsed? request)))
      (handler (parse-params request (params-priority request)))
      (handler request))))
