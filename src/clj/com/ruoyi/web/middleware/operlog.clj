(ns com.ruoyi.web.middleware.operlog
  "操作日志中间件:自动记录所有 API 请求到 sys_oper_log 表.
  依赖 auth/wrap-jwt-auth 前置设置 :identity(否则 oper_name 为 anonymous)."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]))


(def ^:private skip-paths
  "不记录日志的路径(GET 不记录,这里只列非 GET 白名单)"
  #{"/api/auth/login"
    "/api/health"
    "/api/user/profile"})


(def ^:private get-methods
  "GET/HEAD/OPTIONS 请求不记录日志."
  #{:get :head :options})


;; ── 业务类型映射(匹配 RuoYi-Vue BusinessType 枚举) ──────────────
;;
;;   0 = 其他    1 = 新增    2 = 修改    3 = 删除
;;   4 = 授权    5 = 导出    6 = 导入    7 = 强退
;;   9 = 清空

(defn- infer-business-type
  "根据 HTTP 方法和 URI 推测业务类型."
  [method uri]
  (case method
    :post (cond
            (re-find #"/auth/logout" uri)      7   ; 强退
            (re-find #"/import" uri)           6   ; 导入
            :else                              1)  ; 新增
    :put  (cond
            (re-find #"/run" uri)              0   ; 执行一次
            (re-find #"/changeStatus" uri)     2   ; 修改
            (re-find #"/resetPwd" uri)         2   ; 修改
            (re-find #"/dataScope" uri)        4   ; 授权
            (re-find #"/authUser" uri)         4   ; 授权
            :else                              2)  ; 修改
    :delete (cond
              (re-find #"/clear" uri)          9   ; 清空
              (= uri "/api/system/oper-log")   9   ; 清空(根路径 DELETE = clear)
              (= uri "/api/system/login-log")  9   ; 清空(根路径 DELETE = clear)
              :else                            3)  ; 删除
    0)) ; 默认其他

(defn- infer-title
  "根据 URI 提取中文模块标题."
  [uri]
  (cond
    (re-find #"/system/user" uri)        "用户管理"
    (re-find #"/system/role" uri)        "角色管理"
    (re-find #"/system/menu" uri)        "菜单管理"
    (re-find #"/system/dept" uri)        "部门管理"
    (re-find #"/system/post" uri)        "岗位管理"
    (re-find #"/system/dict" uri)        "字典管理"
    (re-find #"/system/config" uri)      "参数管理"
    (re-find #"/system/notice" uri)      "通知公告"
    (re-find #"/system/profile" uri)     "个人信息"
    (re-find #"/system/online" uri)      "在线用户"
    (re-find #"/system/job" uri)         "定时任务"
    (re-find #"/system/oper-log" uri)    "操作日志"
    (re-find #"/system/login-log" uri)   "登录日志"
    (re-find #"/auth/login" uri)         "登录"
    (re-find #"/auth/logout" uri)        "退出"
    (re-find #"/auth/getInfo" uri)       "获取用户信息"
    :else (str (re-find #"/[^/]+/[^/]+" uri) "")))


(defn- format-params
  "格式化请求参数,过长时截断."
  [params]
  (let [s (if (instance? String params)
            params
            (try (json/generate-string params)
                 (catch Exception _ (str params))))]
    (if (> (count s) 200)
      (str (subs s 0 200) "...")
      s)))


(defn- safe-str
  [v]
  (or (str v) ""))


(defn wrap-oper-log
  "操作日志中间件包装器.
  要求在调用链中 auth/wrap-jwt-auth 先于本中间件执行,
  这样 (:identity request) 中才包含当前登录用户信息.
  调用 query-fn 写入 sys_oper_log 表."
  [handler]
  (fn [request]
    (let [uri (:uri request)
          start (System/currentTimeMillis)
          response (handler request)
          cost-ms (- (System/currentTimeMillis) start)]
      (when (and (str/starts-with? uri "/api/")
                 (not (skip-paths uri))
                 (not (contains? get-methods (:request-method request))))
        (let [identity (:identity request)
              method (:request-method request)
              log-entry {:title          (infer-title uri)
                         :business_type  (infer-business-type method uri)
                         :method         (safe-str (get-in request [:headers "x-request-handler"] ""))
                         :request_method (name method)
                         :operator_type  1
                         :oper_name      (or (:user-name identity) (get-in identity [:claims :user-name]) "anonymous")
                         :dept_name      (or (:dept_name identity) "")
                         :oper_url       uri
                         :oper_ip        (or (get-in request [:headers "x-forwarded-for"])
                                             (:remote-addr request "127.0.0.1"))
                         :oper_location  ""
                         :oper_param     (format-params (:params request))
                         :json_result    (safe-str (:body response))
                         :status         (if (>= (:status response) 400) 1 0)
                         :error_msg      ""
                         :cost_time      cost-ms}]
          (try
            (when-let [query-fn (get-in request [:components :query-fn])]
              (query-fn :create-oper-log! log-entry))
            (catch Exception e
              (log/warn e "Failed to write operation log")))))
      response)))
