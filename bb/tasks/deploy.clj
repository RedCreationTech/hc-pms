(ns tasks.deploy
  "GPU 主机部署:构建 uberjar -> 上传 -> systemd 滚动重启 -> 健康验证,失败自动回滚。

  部署参数在 .runtime/deploy.edn(不入库),由 deploy:provision 首次装机时生成,也可手写。

  密钥纪律: JWT_SECRET 与 COOKIE_SECRET 只写入服务器 dir/app.env(600),管理员口令只落
  本地 .runtime/admin-password.txt;两者都不入库,脚本全程不打印明文。

  前提: ssh 已配好目标主机别名(DEPLOY_HOST,默认 gpu)并可 root 登录,
  且该主机装有 /usr/lib/jvm/java-21-openjdk-amd64。
  健康探测默认打到 http://127.0.0.1:<端口>(在部署主机本机执行);
  设 DEPLOY_URL_HOST=<对外地址> 则改由当前机器直连,验证外部可达性。

  任务: deploy:provision 首次装机 | deploy 发布 | deploy:status 状态
        deploy:logs 看日志 | deploy:rollback 回滚上一版"
  ;; .runtime/deploy.edn 形如
  ;; {:host "gpu" :port 34269 :dir "/mnt/hc-pms-34269" :service "hc-pms-34269"
  ;;  :remote-jar "hc-pms-standalone.jar" :url "http://127.0.0.1:34269"}
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [clojure.edn :as edn]
    [clojure.string :as str])
  (:import
    (java.security
      SecureRandom)))


(def state-file ".runtime/deploy.edn")
(def app-name "hc-pms")
(def local-jar "target/rouyi-standalone.jar")
(def remote-jar "hc-pms-standalone.jar")
(def security-ns "com.ruoyi.infra.security")
(def remote-java "/usr/lib/jvm/java-21-openjdk-amd64/bin/java")


(defn- info
  [& xs]
  (println (apply str "▶ " xs)))


(defn- fail!
  [& xs]
  (binding [*out* *err*] (println (apply str "✖ " xs)))
  (System/exit 1))


(defn- sh!
  "执行本地命令,非 0 退出即抛错(交回调用方决定是否回滚);返回 trim 后的 stdout,可用 input 喂 stdin。
   报错里打印完整 argv:这些命令只含主机名与路径,密钥一律走文件与 stdin,不会出现在参数里。"
  [args & [input]]
  (let [{:keys [exit out err]} @(p/process args {:in (or input "") :out :string :err :string})]
    (when-not (zero? exit)
      (throw (ex-info (str "命令失败(" exit "): " (str/join " " args) "\n" (str/trim err))
                      {:exit exit})))
    (str/trim out)))


(defn- ssh!
  "在部署主机上跑一条命令;input 作为 stdin(用来喂 heredoc 脚本,避免引号地狱)。"
  [host command & [input]]
  (sh! ["ssh" "-o" "BatchMode=yes" "-o" "ConnectTimeout=10" host command] input))


(defn- scp!
  [host local remote]
  (sh! ["scp" "-o" "BatchMode=yes" local (str host ":" remote)]))


(defn- config
  []
  (when-not (fs/regular-file? state-file)
    (fail! "缺少 " state-file ";首次部署跑 bb deploy:provision,或按本 ns 文档手写。"))
  (edn/read-string (slurp state-file)))


(defn- secret
  [n]
  (let [bytes (byte-array n)]
    (.nextBytes (SecureRandom.) bytes)
    (apply str (map #(format "%02x" (bit-and 255 %)) bytes))))


(defn- probe
  "读回 curl 的格式化输出。:url 指向环回时在部署主机上执行(装机机不可达),
  否则从当前机器直连,用来验证对外可达。"
  [{:keys [host url]} path fmt]
  (if (str/starts-with? url "http://127.0.0.1")
    (ssh! host (str "curl -s -m 60 -o /dev/null -w '" fmt "' \"" url path "\""))
    (sh! ["curl" "-s" "-m" "60" "-o" "/dev/null" "-w" fmt (str url path)])))


(defn- http-code
  [cfg path]
  (probe cfg path "%{http_code}"))


(defn- http-code-or-000
  "端口还没监听时 curl 会以非 0 退出;等待期间把这视作 000 而不是失败。"
  [cfg path]
  (try (http-code cfg path) (catch Exception _ "000")))


(defn- wait-ready!
  "轮询 /api/health 直到 200;冷启动含数据库迁移,给到 240 秒后抛错。"
  [{:keys [url] :as cfg}]
  (loop [i 0]
    (cond
      (= "200" (http-code-or-000 cfg "/api/health")) (info "就绪,用时 " i " 秒")
      (> i 240) (throw (ex-info (str url " 在 240 秒内没有返回健康状态") {:url url}))
      :else (do (Thread/sleep 1000) (recur (inc i))))))


(defn- verify!
  "验证:健康、首页、前端产物、匿名越权必须 401。"
  [{:keys [url] :as cfg}]
  (let [health (http-code cfg "/api/health")
        index (http-code cfg "/")
        [asset-code asset-size] (str/split (probe cfg "/js/app.js"
                                                  "%{http_code} %{size_download}") #" ")
        anon (http-code cfg "/api/system/user?page=1&size=1")]
    (println (str "  探测自 " url "  health=" health " 首页=" index
                  " app.js=" asset-code "/" asset-size "B"
                  " 匿名越权=" anon))
    (when-not (and (= "200" health) (= "200" index) (= "200" asset-code)
                   (> (parse-long asset-size) 1000000) (= "401" anon))
      (throw (ex-info "验证未通过: 期望 health/首页/app.js 均 200、app.js 大于 1MB、匿名访问 401"
                      {:health health :index index :asset asset-code})))
    (info "验证通过")))


(defn- attempt!
  "运行任务体,把任何异常收敛成一行错误并以非 0 退出(deploy 自己负责先回滚)。"
  [f]
  (try
    (f)
    (catch Exception e (fail! (or (.getMessage e) (str e))))))


(defn- restore-previous!
  [{:keys [host dir service]}]
  (info "回滚到上一版 jar")
  (ssh! host (str "systemctl stop " service " && cd " dir
                  " && if test -f app.previous.jar; then mv -f app.previous.jar " remote-jar "; fi"
                  " && systemctl start " service)))


(defn- prune-backups!
  [{:keys [host dir]}]
  (ssh! host (str "cd " dir "/backups && ls -1dt pre-deploy-* 2>/dev/null"
                  " | tail -n +11 | xargs -r rm -rf")))


(defn- publish!
  "换 jar 的实际过程;任何一步失败都回滚上一版并重新验证。"
  [{:keys [host dir service] :as cfg}]
  (let [backup (str dir "/backups/pre-deploy-" (System/currentTimeMillis))]
    (when-not (fs/regular-file? local-jar)
      (fail! "找不到 " local-jar ";bb.edn 里 deploy 依赖 uberjar,请确认构建成功"))
    (info "上传 " local-jar " -> " dir "/app.next.jar")
    (scp! host local-jar (str dir "/app.next.jar"))
    (info "停服 " service " 并冷备份 data/")
    (ssh! host (str "systemctl stop " service))
    (try
      (ssh! host (str "mkdir -p " backup " && cp -a " dir "/data/. " backup "/"))
      (ssh! host (str "cd " dir " && if test -f " remote-jar
                      "; then mv -f " remote-jar " app.previous.jar; fi"
                      " && mv -f app.next.jar " remote-jar))
      (ssh! host (str "systemctl start " service))
      (wait-ready! cfg)
      (verify! cfg)
      (prune-backups! cfg)
      (catch Exception e
        (binding [*out* *err*] (println "✖ 发布失败:" (or (.getMessage e) e)))
        (restore-previous! cfg)
        (try
          (wait-ready! cfg)
          (verify! cfg)
          (fail! "已回滚到上一版,服务恢复正常;bb deploy:logs 查原因后重试。")
          (catch Exception _
            (fail! "回滚后仍未通过验证;手工检查 " dir " (jar 备份在 " backup ")")))))))


(defn deploy!
  []
  (attempt! #(publish! (config))))


(defn- report!
  [{:keys [host service dir port] :as cfg}]
  (println (ssh! host (str "systemctl is-enabled " service " 2>&1"
                           " && systemctl show " service
                           " -p ActiveState -p SubState -p MainPID -p MemoryCurrent -p NRestarts"
                           " | paste -sd';' -"
                           " && ss -ltnp 2>/dev/null | grep ':" port "' | head -1"
                           " && df -h /mnt | tail -1"
                           " && ls -1 " dir "/backups 2>/dev/null | tail -3")))
  (verify! cfg))


(defn status!
  []
  (attempt! #(report! (config))))


(defn logs!
  [args]
  (attempt!
    #(let [{:keys [host dir]} (config)
           n (or (some-> (first args) parse-long) 80)]
       (println (ssh! host (str "tail -" n " " dir "/logs/service.log"))))))


(defn- revert!
  []
  (let [cfg (config)]
    (restore-previous! cfg)
    (wait-ready! cfg)
    (verify! cfg)
    (info "已回滚到上一版")))


(defn rollback!
  []
  (attempt! #'revert!))


(defn- env-text
  [port dir jwt cookie]
  (str/join "\n"
            [(str "PORT=" port)
             "HTTP_HOST=0.0.0.0"
             (str "JDBC_URL=jdbc:sqlite:" dir "/data/hc-pms.db")
             "MIGRATION_DIR=migrations-sqlite"
             (str "PMS_FILE_DIR=" dir "/data/pms-files")
             (str "FLOWABLE_JDBC_URL=jdbc:h2:file:" dir
                  "/data/flowable;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE")
             (str "JWT_SECRET=" jwt)
             (str "COOKIE_SECRET=" cookie)
             "TZ=Asia/Shanghai"
             "NREPL_PORT=0"
             "NREPL_HOST=127.0.0.1"
             ""]))


(defn- unit-text
  [service dir]
  (str/join "\n"
            ["[Unit]"
             (str "Description=" service " (Clojure uberjar, bare java -jar)")
             "After=network-online.target"
             "Wants=network-online.target"
             "[Service]"
             "Type=simple"
             "User=root"
             (str "WorkingDirectory=" dir)
             (str "EnvironmentFile=" dir "/app.env")
             (str "ExecStart=" remote-java " -Duser.timezone=Asia/Shanghai -Xms128m -Xmx1536m"
                  " -jar " dir "/" remote-jar)
             "Restart=always"
             "RestartSec=5"
             "MemoryMax=2G"
             "CPUQuota=200%"
             "LimitNOFILE=65536"
             (str "StandardOutput=append:" dir "/logs/service.log")
             (str "StandardError=append:" dir "/logs/service.log")
             "[Install]"
             "WantedBy=multi-user.target"
             ""]))


(defn- logrotate-text
  [dir]
  (str/join "\n"
            [(str dir "/logs/service.log {")
             "    size 10M"
             "    rotate 3"
             "    missingok"
             "    notifempty"
             "    copytruncate"
             "}"
             ""]))


;; 迁移种子会把 user_id=1 写成模板口令 admin123,而本服务没有拒绝模板口令的生产守卫,
;; 所以限时前台启动一结束就必须立刻换成随机口令;用应用自身的 hash-password 生成,
;; 保证与登录校验算法一致。
(def admin-patch-clj
  (str/join "\n"
            ["(let [hash-fn (requiring-resolve (symbol (str (System/getenv \"SEC_NS\") \"/hash-password\")))"
             "      check-fn (requiring-resolve (symbol (str (System/getenv \"SEC_NS\") \"/verify-password\")))"
             "      con (java.sql.DriverManager/getConnection (System/getenv \"JDBC_URL\"))"
             "      pw (System/getenv \"ADMIN_PASSWORD\")]"
             "  (with-open [p (.prepareStatement con \"UPDATE sys_user SET password=? WHERE user_id=1\")]"
             "    (.setString p 1 (hash-fn pw))"
             "    (println \"rows-updated:\" (.executeUpdate p)))"
             "  (with-open [q (.prepareStatement con \"SELECT password FROM sys_user WHERE user_id=1\")"
             "              rs (.executeQuery q)]"
             "    (.next rs)"
             "    (let [stored (.getString rs 1)]"
             "      (println \"verify-new:\" (boolean (check-fn pw stored))"
             "             \"template-rejected:\" (boolean (not (check-fn \"admin123\" stored))))))"
             "  (.close con))"
             ""]))


(defn- write-local!
  [path text]
  (spit path text)
  (fs/set-posix-file-permissions path "rw-------"))


(defn- pick-port!
  [host]
  (parse-long (ssh! host "python3 -"
                    (str "import socket\n"
                         "s=socket.socket()\n"
                         "s.bind(('0.0.0.0',0))\n"
                         "print(s.getsockname()[1])\n"
                         "s.close()\n"))))


(defn- install-remote!
  "上传 env、jar、systemd 单元、logrotate 与换密脚本,并装上单元。"
  [{:keys [host dir service]}]
  (ssh! host (str "mkdir -p " dir "/data " dir "/data/pms-files "
                  dir "/backups " dir "/logs && chmod 700 " dir))
  (doseq [[local remote] [[".runtime/app.env" (str dir "/app.env")]
                          [local-jar (str dir "/" remote-jar)]
                          [".runtime/unit.service" (str dir "/unit.service")]
                          [".runtime/logrotate.conf" (str dir "/logrotate.conf")]
                          [".runtime/patch.env" (str dir "/patch.env")]
                          [".runtime/patch_admin.clj" (str dir "/patch_admin.clj")]]]
    (scp! host local remote))
  (ssh! host (str "chmod 600 " dir "/app.env " dir "/patch.env " dir "/patch_admin.clj"))
  (ssh! host (str "mv " dir "/unit.service /etc/systemd/system/" service ".service"
                  " && mv " dir "/logrotate.conf /etc/logrotate.d/" service
                  " && systemctl daemon-reload && systemctl enable " service)))


(defn- bootstrap-database!
  "限时在前台跑一次以执行迁移(SQLite 与 Flowable 建表),再用应用自身函数换管理员口令。
   这一次把 HTTP_HOST 收到 127.0.0.1:迁移期间库里的口令还是模板值,不能让公网连进来。"
  [{:keys [host dir service]}]
  (info "限时前台启动一次以执行数据库迁移(仅绑定 127.0.0.1)")
  (ssh! host (str "cd " dir " && set -a && . ./app.env && set +a"
                  " && HTTP_HOST=127.0.0.1 timeout -s TERM 150 " remote-java
                  " -Duser.timezone=Asia/Shanghai -Xmx1536m -jar " remote-jar
                  " >> " dir "/logs/service.log 2>&1; true"))
  (info "轮换管理员口令(用应用自身的 hash-password)")
  (println (ssh! host (str "cd " dir " && set -a && . ./app.env && . ./patch.env && set +a"
                           " && " remote-java " -cp " remote-jar " clojure.main patch_admin.clj")))
  (ssh! host (str "rm -f " dir "/patch.env " dir "/patch_admin.clj"))
  (ssh! host (str "systemctl restart " service " || true")))


(defn- provision-1!
  "在一台空白 GPU 主机上首次装机:随机端口、/mnt 目录、app.env、systemd 单元、迁移、换密、验证。"
  [args]
  (when (fs/exists? state-file)
    (fail! "已存在 " state-file ";不会覆盖现有部署。换端口请手工编辑该文件并同步服务器。"))
  (when-not (fs/regular-file? local-jar)
    (fail! "找不到 " local-jar ";先跑 bb uberjar"))
  (let [host (or (first args) (System/getenv "DEPLOY_HOST") "gpu")
        port (pick-port! host)
        dir (str "/mnt/" app-name "-" port)
        service (str app-name "-" port)
        ;; 默认在部署主机本机探测(装机机未必能路由到该主机);
        ;; 想从外部验证就设 DEPLOY_URL_HOST=<对外地址>
        url (str "http://" (or (System/getenv "DEPLOY_URL_HOST") "127.0.0.1") ":" port)
        cfg {:host host :port port :dir dir :service service
             :remote-jar remote-jar :url url}
        admin (secret 12)]
    (fs/create-dirs ".runtime" {:posix-file-permissions "rwx------"})
    (write-local! ".runtime/app.env" (env-text port dir (secret 32) (secret 8)))
    (write-local! ".runtime/admin-password.txt"
                  (str "账号: admin\n口令: " admin "\n服务: " service
                       "\n主机: " host " 端口: " port "\n探测地址: " url "\n"))
    (write-local! ".runtime/unit.service" (unit-text service dir))
    (write-local! ".runtime/logrotate.conf" (logrotate-text dir))
    (write-local! ".runtime/patch.env" (str "SEC_NS=" security-ns "\nADMIN_PASSWORD=" admin "\n"))
    (write-local! ".runtime/patch_admin.clj" admin-patch-clj)
    (info "在 " host " 上创建 " dir)
    (install-remote! cfg)
    (bootstrap-database! cfg)
    (write-local! state-file (pr-str cfg))
    (wait-ready! cfg)
    (verify! cfg)
    (info "装机完成: " url " 目录 " dir " 服务 " service)
    (println "  管理员口令在 .runtime/admin-password.txt(600,不入库),存进密码管理器后删掉本地文件。")))


(defn provision!
  [args]
  (attempt! #(provision-1! args)))
