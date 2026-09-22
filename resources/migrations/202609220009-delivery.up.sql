CREATE TABLE pms_delivery_record (
  record_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
  kind VARCHAR(30) NOT NULL, code VARCHAR(100) NOT NULL, revision INTEGER NOT NULL DEFAULT 1,
  status VARCHAR(30) NOT NULL, created_by BIGINT NOT NULL, owner_id BIGINT,
  payload LONGTEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(project_id,kind,code,revision),
  FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
  CHECK(kind IN ('configuration','material','bom','assembly','test','shipment','service')),
  CHECK(status IN ('registered','draft','in_review','approved','rejected','frozen','partial','ready','in_progress','released','shipped','received','conditional','returned','open','closed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_delivery_project_kind ON pms_delivery_record(project_id,kind,status);
--;;
