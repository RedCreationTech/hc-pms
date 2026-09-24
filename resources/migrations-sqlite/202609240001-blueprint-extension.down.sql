-- 回退蓝图对齐扩展: 删除新增菜单/权限与配置表, 重建治理/交付记录为扩展前的类型约束 (丢弃扩展类型记录), 计划任务与工时列在 SQLite 中保留 (ALTER DROP COLUMN 需要 3.35+, 保留列不影响旧代码).
DELETE FROM sys_role_menu WHERE menu_id IN (5003,5004,5005,5006,5007,5042,5043,5044);
--;;
DELETE FROM sys_menu WHERE menu_id IN (5003,5004,5005,5006,5007,5042,5043,5044);
--;;
DROP TABLE IF EXISTS pms_config_record;
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
  CHECK(kind IN ('charter','requirement','document','trace','risk','issue','meeting','action','change','gate-template','gate','stakeholder','raci','comm-plan')),
  CHECK(revision > 0),
  CHECK(status IN ('draft','in_review','approved','rejected','registered','open','mitigated','materialized','resolved','closed','recorded','planned','converted','ready','waived','active','assigned','discarded'))
);
--;;
INSERT INTO pms_gov_record_old (record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at)
  SELECT record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at FROM pms_gov_record
  WHERE kind NOT IN ('template-instance','dq','node-pause');
--;;
DROP TABLE pms_gov_record;
--;;
ALTER TABLE pms_gov_record_old RENAME TO pms_gov_record;
--;;
CREATE INDEX idx_pms_gov_project_kind ON pms_gov_record(project_id,kind,status);
--;;
CREATE TABLE pms_delivery_record_old (
  record_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
  kind VARCHAR(30) NOT NULL, code VARCHAR(100) NOT NULL, revision INTEGER NOT NULL DEFAULT 1,
  status VARCHAR(30) NOT NULL, created_by BIGINT NOT NULL, owner_id BIGINT,
  payload TEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(project_id,kind,code,revision),
  FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
  CHECK(kind IN ('configuration','material','bom','assembly','test','shipment','service')),
  CHECK(status IN ('registered','draft','in_review','approved','rejected','frozen','partial','ready','in_progress','released','shipped','received','conditional','returned','open','closed'))
);
--;;
INSERT INTO pms_delivery_record_old (record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at)
  SELECT record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at FROM pms_delivery_record
  WHERE kind NOT IN ('survey','handover','site-task');
--;;
DROP TABLE pms_delivery_record;
--;;
ALTER TABLE pms_delivery_record_old RENAME TO pms_delivery_record;
--;;
CREATE INDEX idx_delivery_project_kind ON pms_delivery_record(project_id,kind,status);
--;;
PRAGMA foreign_keys=ON;
--;;
