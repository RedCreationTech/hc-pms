✔ (ns com.ruoyi.infra.cron
?   "Cron 表达式辅助工具。"
?   (:require
?    [clojure.string :as str])
?   (:import
?    [org.quartz CronExpression]
?    [org.quartz CronScheduleBuilder]))
  
✔ (defn- normalize [expression]
?   "将 5 字段 Unix cron 补全为 Quartz 6 字段（秒位为 0）。"
~   (if (and (seq expression)
✔            (= 5 (count (str/split expression #"\s+"))))
✘     (str "0 " expression)
✔     expression))
  
✔ (defn valid?
?   "校验 cron 表达式是否合法（Quartz 格式，5-7 位；5 位自动补秒）。"
?   [expression]
~   (and (seq expression)
✔        (CronExpression/isValidExpression (normalize expression))))
  
✔ (defn cron-schedule
?   "根据 cron 表达式和 misfire 策略构建 CronScheduleBuilder。"
?   [expression misfire-policy]
✔   (let [builder (CronScheduleBuilder/cronSchedule ^String (normalize expression))]
✔     (case (when misfire-policy (str misfire-policy))
✘       "1" (.withMisfireHandlingInstructionIgnoreMisfires builder)
✘       "2" (.withMisfireHandlingInstructionFireAndProceed builder)
✔       "3" (.withMisfireHandlingInstructionDoNothing builder)
✘       builder)))
