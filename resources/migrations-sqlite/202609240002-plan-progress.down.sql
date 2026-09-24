-- 回退增量6: 重建治理记录为增量5 的类型约束 (丢弃 stage-weights/reschedule/progress-snapshot/reminder 记录), 删除实际日期列 (SQLite 3.35+ DROP COLUMN) 与进度扫描定时任务.
DELETE FROM sys_job WHERE job_id = 9001;
--;;
ALTER TABLE pms_plan_feedback DROP COLUMN actual_end;
--;;
ALTER TABLE pms_plan_feedback DROP COLUMN actual_start;
--;;
ALTER TABLE pms_plan_task DROP COLUMN actual_end;
--;;
ALTER TABLE pms_plan_task DROP COLUMN actual_start;
--;;
PRAGMA foreign_keys=OFF;
--;;
CREATE TABLE pms_gov_record_old (
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
  CHECK(kind IN ('charter','requirement','document','trace','risk','issue','meeting','action','change','gate-template','gate','stakeholder','raci','comm-plan','template-instance','dq','node-pause')),
  CHECK(revision > 0),
  CHECK(status IN ('draft','in_review','approved','rejected','registered','open','mitigated','materialized','resolved','closed','recorded','planned','converted','ready','waived','active','assigned','discarded'))
);
--;;
INSERT INTO pms_gov_record_old (record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at)
  SELECT record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at FROM pms_gov_record
  WHERE kind NOT IN ('stage-weights','reschedule','progress-snapshot','reminder');
--;;
DROP TABLE pms_gov_record;
--;;
ALTER TABLE pms_gov_record_old RENAME TO pms_gov_record;
--;;
CREATE INDEX idx_pms_gov_project_kind ON pms_gov_record(project_id,kind,status);
--;;
PRAGMA foreign_keys=ON;
--;;
