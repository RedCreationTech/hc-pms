-- D04/D05 直发申请: 交付记录新增 direct-ship 类型, 状态沿用 draft/in_review/approved/rejected;
-- 重建 pms_delivery_record 以扩展 kind 白名单.
PRAGMA foreign_keys=OFF;
--;;
CREATE TABLE pms_delivery_record_new (
  record_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
  kind VARCHAR(30) NOT NULL, code VARCHAR(100) NOT NULL, revision INTEGER NOT NULL DEFAULT 1,
  status VARCHAR(30) NOT NULL, created_by BIGINT NOT NULL, owner_id BIGINT,
  payload TEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(project_id,kind,code,revision),
  FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
  CHECK(kind IN ('configuration','material','bom','assembly','test','shipment','service','survey','handover','site-task','direct-ship')),
  CHECK(status IN ('registered','draft','in_review','approved','rejected','frozen','partial','ready','in_progress','released','shipped','received','conditional','returned','open','closed'))
);
--;;
INSERT INTO pms_delivery_record_new (record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at)
  SELECT record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at FROM pms_delivery_record;
--;;
DROP TABLE pms_delivery_record;
--;;
ALTER TABLE pms_delivery_record_new RENAME TO pms_delivery_record;
--;;
CREATE INDEX idx_delivery_project_kind ON pms_delivery_record(project_id,kind,status);
--;;
PRAGMA foreign_keys=ON;
--;;
