CREATE TABLE pms_appointment (
  appointment_id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  code VARCHAR(100) NOT NULL,
  revision INTEGER NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'issued',
  issued_by BIGINT NOT NULL,
  issued_on VARCHAR(10) NOT NULL,
  note VARCHAR(500) NOT NULL DEFAULT '',
  snapshot TEXT NOT NULL,
  snapshot_sha256 VARCHAR(64) NOT NULL,
  content TEXT NOT NULL,
  headcount INTEGER NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(project_id,code,revision),
  FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
  CHECK(revision > 0),
  CHECK(status = 'issued'),
  CHECK(headcount >= 1)
);
--;;
CREATE INDEX idx_pms_appointment_project ON pms_appointment(project_id,code,revision);
--;;
