(ns com.ruoyi.rouyi.web.request-test
  "集成测试 — 启动完整系统并通过 HTTP 请求测试 API。"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [com.ruoyi.rouyi.test-utils :refer [system-state system-fixture GET PUT]]
            [peridot.core :as p]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(use-fixtures :once (system-fixture))

(defn- handler []
  (:handler/ring (system-state)))

(defn- parse-json [resp]
  (when (:body resp)
    (try (json/read-str (:body resp) :key-fn keyword)
         (catch Exception _ nil))))

(defn- login-token []
  (let [ctx (-> (p/session (handler))
                (p/request "/api/auth/login"
                           :request-method :post
                           :content-type "application/json"
                           :body (json/write-str {:username "admin" :password "admin123"})))
        resp (:response ctx)]
    (when-let [body (parse-json resp)]
      (get-in body [:data :token]))))

(defn- auth-headers [token]
  {"authorization" (str "Bearer " token)})

;; ─── 健康检查 ──────────────────────────────────────────────────────

(deftest health-test
  (testing "健康检查 API"
    (let [resp (GET (handler) "/api/health" {} {})]
      (is (= 200 (:status resp))))))

;; ─── 认证 ──────────────────────────────────────────────────────────

(deftest login-test
  (testing "登录 API"
    (let [token (login-token)]
      (is (string? token))
      (is (pos? (count token))))))

(deftest get-info-test
  (testing "获取用户信息 API"
    (let [token (login-token)
          resp (GET (handler) "/api/auth/getInfo" {} (auth-headers token))
          body (parse-json resp)]
      (is (= 200 (:status resp)))
      (is (= 200 (:code body))))))

;; ─── 系统管理 ──────────────────────────────────────────────────────

(deftest user-list-test
  (testing "用户列表 API"
    (let [resp (GET (handler) "/api/system/user" {} (auth-headers (login-token)))
          body (parse-json resp)]
      (is (= 200 (:status resp))))))

(deftest role-list-test
  (testing "角色列表 API"
    (let [resp (GET (handler) "/api/system/role" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))

(deftest menu-list-test
  (testing "菜单列表 API"
    (let [resp (GET (handler) "/api/system/menu" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))

(deftest dept-list-test
  (testing "部门列表 API"
    (let [resp (GET (handler) "/api/system/dept" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))

(deftest post-list-test
  (testing "岗位列表 API"
    (let [resp (GET (handler) "/api/system/post" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))

(deftest dict-type-list-test
  (testing "字典类型列表 API"
    (let [resp (GET (handler) "/api/system/dict/type" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))

(deftest config-list-test
  (testing "参数列表 API"
    (let [resp (GET (handler) "/api/system/config" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))

(deftest notice-list-test
  (testing "通知公告列表 API"
    (let [resp (GET (handler) "/api/system/notice" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))

;; ─── 监控 ──────────────────────────────────────────────────────────

(deftest server-monitor-test
  (testing "服务器监控 API"
    (let [resp (GET (handler) "/api/system/server" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))

(deftest datasource-monitor-test
  (testing "数据源监控 API"
    (let [resp (GET (handler) "/api/system/datasource" {} (auth-headers (login-token)))
          body (parse-json resp)]
      (is (= 200 (:status resp)))
      (is (some? (get-in body [:data :active_connections]))))))

(deftest online-list-test
  (testing "在线用户列表 API"
    (let [token (login-token)
          resp (GET (handler) "/api/system/online" {} (auth-headers token))
          body (parse-json resp)]
      (is (= 200 (:status resp)))
      (is (vector? (get-in body [:data :rows]))))))

(deftest operlog-list-test
  (testing "操作日志列表 API"
    (let [resp (GET (handler) "/api/system/oper-log" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))

(deftest loginlog-list-test
  (testing "登录日志列表 API"
    (let [resp (GET (handler) "/api/system/login-log" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))

(deftest job-list-test
  (testing "定时任务列表 API"
    (let [resp (GET (handler) "/api/system/job" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))

(deftest job-run-once-test
  (testing "定时任务立即执行"
    (let [token (login-token)
          create-ctx (-> (p/session (handler))
                         (p/request "/api/system/job"
                                    :request-method :post
                                    :content-type "application/json"
                                    :headers (auth-headers token)
                                    :body (json/write-str {:job_name "test-job"
                                                           :job_group "DEFAULT"
                                                           :invoke_target "com.ruoyi.rouyi.task/ry-no-params"
                                                           :cron_expression "0 0 1 * * ?"
                                                           :misfire_policy "3"
                                                           :concurrent "1"
                                                           :status "0"
                                                           :create_by "admin"
                                                           :remark "test"})))
          job-id (get-in (parse-json (:response create-ctx)) [:data :job_id])
          run-resp (PUT (handler) (str "/api/system/job/" job-id "/run") {} (auth-headers token))
          _ (Thread/sleep 1200)
          all-log-resp (GET (handler) "/api/system/job-log?page-num=1&page-size=10" {} (auth-headers token))
          log-resp (GET (handler) (str "/api/system/job-log?page-num=1&page-size=10&job_name=test-job") {} (auth-headers token))
          log-body (parse-json log-resp)
          all-log-body (parse-json all-log-resp)]
      (is (some? job-id))
      (is (= 200 (:status run-resp)))
      (is (pos? (count (get-in all-log-body [:data :rows]))))
      (is (pos? (count (get-in log-body [:data :rows])))))))

;; ─── 代码生成 ──────────────────────────────────────────────────────

(deftest gen-tables-test
  (testing "代码生成-表列表 API"
    (let [resp (GET (handler) "/api/tool/gen/tables" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))



;; ─── 导出 ──────────────────────────────────────────────────────────

(deftest export-role-test
  (testing "导出角色数据 API"
    (let [resp (GET (handler) "/api/system/role/export" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))

(deftest export-user-test
  (testing "导出用户数据 API"
    (let [resp (GET (handler) "/api/system/user/export" {} (auth-headers (login-token)))]
      (is (= 200 (:status resp))))))
