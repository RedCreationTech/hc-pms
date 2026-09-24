-- 增量6 计划与进度深化: 治理记录新增 stage-weights (项目级阶段权重覆盖) / reschedule (节点计划重排) / progress-snapshot (进度快照趋势) / reminder (本地定时提醒) 四类;
-- 计划任务与执行反馈增加实际开始/完成日期; 登记每日进度扫描定时任务 (只写本地快照与待办).
PRAGMA foreign_keys=OFF;
--;;
CREATE TABLE pms_gov_record_new (
  record_id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  kind VARCHAR(30) NOT NULL,
  code VARCHAR(100) NOT NULL,
  revision INTEGER NOT NULL DEFAULT 1,
  status VARCHAR(30) NOT NULL,
  created_by BIGINT NOT NULL,
  owner_id BIGINT,
  payload TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(project_id,kind,code,revision),
  FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
  CHECK(kind IN ('charter','requirement','document','trace','risk','issue','meeting','action','change','gate-template','gate','stakeholder','raci','comm-plan','template-instance','dq','node-pause','stage-weights','reschedule','progress-snapshot','reminder')),
  CHECK(revision > 0),
  CHECK(status IN ('draft','in_review','approved','rejected','registered','open','mitigated','materialized','resolved','closed','recorded','planned','converted','ready','waived','active','assigned','discarded'))
);
--;;
INSERT INTO pms_gov_record_new (record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at)
  SELECT record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at FROM pms_gov_record;
--;;
DROP TABLE pms_gov_record;
--;;
ALTER TABLE pms_gov_record_new RENAME TO pms_gov_record;
--;;
CREATE INDEX idx_pms_gov_project_kind ON pms_gov_record(project_id,kind,status);
--;;
PRAGMA foreign_keys=ON;
--;;
ALTER TABLE pms_plan_task ADD COLUMN actual_start VARCHAR(10);
--;;
ALTER TABLE pms_plan_task ADD COLUMN actual_end VARCHAR(10);
--;;
ALTER TABLE pms_plan_feedback ADD COLUMN actual_start VARCHAR(10);
--;;
ALTER TABLE pms_plan_feedback ADD COLUMN actual_end VARCHAR(10);
--;;
INSERT OR IGNORE INTO sys_job (job_id, job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, remark) VALUES (9001, 'PMS进度扫描', 'PMS', 'com.ruoyi.task/pms-progress-scan', '0 0 6 * * ?', '3', '1', '0', '每日 06:00 生成各执行中项目的进度快照 (PV/EV/AC 趋势) 与本地逾期提醒, 只写本地待办不投递外部消息');
--;;
