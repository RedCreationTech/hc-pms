CREATE TABLE pms_gov_record (
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
  CHECK(kind IN ('charter','requirement','document','trace','risk','issue','meeting','action','change','gate-template','gate')),
  CHECK(revision > 0),
  CHECK(status IN ('draft','in_review','approved','rejected','registered','open','mitigated','materialized','resolved','closed','recorded','planned','converted','ready','waived'))
);
--;;
CREATE INDEX idx_pms_gov_project_kind ON pms_gov_record(project_id,kind,status);
--;;
