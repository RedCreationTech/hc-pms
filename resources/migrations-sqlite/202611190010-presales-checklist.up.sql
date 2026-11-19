-- A04 产品线应交资料清单: 平台配置 kind 白名单新增 presales-checklist (表重建更新 CHECK).
PRAGMA foreign_keys=OFF;
--;;
CREATE TABLE pms_config_record_new (
  config_id VARCHAR(36) PRIMARY KEY,
  kind VARCHAR(30) NOT NULL,
  code VARCHAR(100) NOT NULL,
  revision INTEGER NOT NULL DEFAULT 1,
  status VARCHAR(30) NOT NULL,
  created_by BIGINT NOT NULL,
  payload TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(kind,code,revision),
  CHECK(kind IN ('project-template','coding-rule','quarterly-target','rd-pool','period-lock','approval-policy','rate-table','exchange-rate','tax-rate','report-metric','presales-checklist')),
  CHECK(revision > 0),
  CHECK(status IN ('draft','published','retired','frozen','locked'))
);
--;;
INSERT INTO pms_config_record_new (config_id,kind,code,revision,status,created_by,payload,created_at,updated_at)
SELECT config_id,kind,code,revision,status,created_by,payload,created_at,updated_at FROM pms_config_record;
--;;
DROP TABLE pms_config_record;
--;;
ALTER TABLE pms_config_record_new RENAME TO pms_config_record;
--;;
CREATE INDEX idx_pms_config_kind ON pms_config_record(kind,code,revision);
--;;
PRAGMA foreign_keys=ON;
--;;
-- A04 售前资料交接: 治理记录新增 presales-handover (按产品线应交资料, 缺料退回与重交);
-- 重建 pms_gov_record 以扩展 kind 与 status 白名单.
PRAGMA foreign_keys=OFF;
--;;
CREATE TABLE pms_gov_record_new (
  record_id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  kind VARCHAR(30) NOT NULL,
  code VARCHAR(227) NOT NULL,
  revision INTEGER NOT NULL DEFAULT 1,
  status VARCHAR(30) NOT NULL,
  created_by BIGINT NOT NULL,
  owner_id BIGINT,
  payload TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(project_id,kind,code,revision),
  FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
  CHECK(kind IN ('charter','requirement','document','trace','risk','issue','meeting','action','change','gate-template','gate','stakeholder','raci','comm-plan','template-instance','dq','node-pause','stage-weights','reschedule','progress-snapshot','reminder','notification','presales-handover')),
  CHECK(revision > 0),
  CHECK(status IN ('draft','in_review','approved','rejected','registered','open','mitigated','materialized','resolved','closed','recorded','planned','converted','ready','waived','active','assigned','discarded','unread','read','handled','dismissed','pending','returned','submitted','approved'))
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
