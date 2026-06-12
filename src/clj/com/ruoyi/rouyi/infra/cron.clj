(ns com.ruoyi.rouyi.infra.cron
  "Cron 表达式辅助工具。"
  (:import
   [org.quartz CronExpression]
   [org.quartz CronScheduleBuilder]))

(defn valid?
  "校验 cron 表达式是否合法（Quartz 格式，6-7 位）。"
  [expression]
  (and (seq expression)
       (CronExpression/isValidExpression expression)))

(defn cron-schedule
  "根据 cron 表达式和 misfire 策略构建 CronScheduleBuilder。"
  [expression misfire-policy]
  (let [builder (CronScheduleBuilder/cronSchedule ^String expression)]
    (case (when misfire-policy (str misfire-policy))
      "1" (.withMisfireHandlingInstructionIgnoreMisfires builder)
      "2" (.withMisfireHandlingInstructionFireAndProceed builder)
      "3" (.withMisfireHandlingInstructionDoNothing builder)
      builder)))