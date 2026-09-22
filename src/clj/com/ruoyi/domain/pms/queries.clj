(ns com.ruoyi.domain.pms.queries
  "PMS运行时与集成测试共享的SQL资源注册清单.")

(def filenames
  "业务查询文件, 各模块由HugSQL绑定并使用参数化SQL."
  ["sql/pms.sql" "sql/pms_planning.sql" "sql/pms_planning_resources.sql"
   "sql/pms_planning_baselines.sql" "sql/pms_governance.sql"
   "sql/pms_finance.sql" "sql/pms_closure.sql" "sql/pms_integration.sql" "sql/pms_delivery.sql"])
