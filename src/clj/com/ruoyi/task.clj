(ns com.ruoyi.task
  "定时任务示例目标函数.

  所有 invoke_target 字符串必须指向本命名空间下的函数."
  (:require
    [clojure.tools.logging :as log]))


(defn ry-no-params
  "无参示例任务."
  []
  (log/info "示例任务 ry-no-params 执行成功"))


(defn ry-params
  "带字符串参数示例任务."
  [s]
  (log/info "示例任务 ry-params 执行成功:" s))


(defonce ^:private pms-service (atom nil))


(defn register-pms-service!
  "由 :app.pms/service 启动时注入, 供定时任务以系统身份调用 PMS 扫描."
  [svc]
  (reset! pms-service svc))


(defn pms-progress-scan
  "每日进度扫描: 对执行中项目生成进度快照与本地逾期提醒 (增量6 B19/H06). 以 admin (user_id 1) 作为系统身份记录审计."
  []
  (if-let [svc @pms-service]
    (let [actor ((requiring-resolve 'com.ruoyi.domain.pms.service/actor) svc {:user-id 1})
          result ((requiring-resolve 'com.ruoyi.domain.pms.scan/run-all!) svc actor)]
      (log/info "PMS 进度扫描完成:" (:date result) "项目数" (:project_count result))
      result)
    (log/warn "PMS 进度扫描跳过: PMS 服务尚未初始化")))
