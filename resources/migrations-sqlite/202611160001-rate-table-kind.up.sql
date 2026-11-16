-- H07 有效期费率表: 平台配置 kind 白名单新增 rate-table (表重建更新 CHECK).
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
  CHECK(kind IN ('project-template','coding-rule','quarterly-target','rd-pool','period-lock','approval-policy','rate-table')),
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
