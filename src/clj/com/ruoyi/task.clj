(ns com.ruoyi.task
  "定时任务示例目标函数。

  所有 invoke_target 字符串必须指向本命名空间下的函数。"
  (:require
    [clojure.tools.logging :as log]))


(defn ry-no-params
  "无参示例任务。"
  []
  (log/info "示例任务 ry-no-params 执行成功"))


(defn ry-params
  "带字符串参数示例任务。"
  [s]
  (log/info "示例任务 ry-params 执行成功:" s))
