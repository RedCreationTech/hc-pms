CREATE TABLE pms_risk_template (
  template_id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  title VARCHAR(200) NOT NULL,
  category VARCHAR(40),
  probability INTEGER NOT NULL,
  impact INTEGER NOT NULL,
  mitigation LONGTEXT,
  stage VARCHAR(40),
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
  CHECK(probability >= 1 AND probability <= 5),
  CHECK(impact >= 1 AND impact <= 5),
  CHECK(status IN ('active', 'discarded'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_pms_risk_template_project ON pms_risk_template(project_id,status);
--;;
