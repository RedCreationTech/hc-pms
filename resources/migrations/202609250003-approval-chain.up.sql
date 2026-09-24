-- S3 可配置审批: 平台配置新增 "审批策略" 类型; 审批流与审批步骤表.
ALTER TABLE pms_config_record DROP CHECK pms_config_record_chk_1;
--;;
ALTER TABLE pms_config_record ADD CONSTRAINT pms_config_record_kind_chk
  CHECK (kind IN ('project-template','coding-rule','quarterly-target','rd-pool','period-lock','approval-policy'));
--;;
CREATE TABLE pms_approval_flow (
  flow_id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  biz_type VARCHAR(30) NOT NULL,
  biz_id VARCHAR(64) NOT NULL,
  title VARCHAR(300) NOT NULL,
  policy_id VARCHAR(36),
  policy_revision INTEGER,
  policy_json LONGTEXT NOT NULL,
  amount VARCHAR(40),
  status VARCHAR(20) NOT NULL,
  current_level INTEGER NOT NULL,
  submitted_by BIGINT NOT NULL,
  created_ms BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at TIMESTAMP NULL,
  FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
  CHECK(biz_type IN ('plan-baseline','charter','cost-version','closure')),
  CHECK(status IN ('pending','approved','rejected','cancelled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_pms_approval_flow_biz ON pms_approval_flow(biz_type,biz_id,status);
--;;
CREATE INDEX idx_pms_approval_flow_project ON pms_approval_flow(project_id,created_ms);
--;;
CREATE TABLE pms_approval_step (
  step_id VARCHAR(36) PRIMARY KEY,
  flow_id VARCHAR(36) NOT NULL,
  level_no INTEGER NOT NULL,
  level_name VARCHAR(100) NOT NULL,
  mode VARCHAR(10) NOT NULL,
  approver_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  comment VARCHAR(1000),
  decided_at TIMESTAMP NULL,
  FOREIGN KEY(flow_id) REFERENCES pms_approval_flow(flow_id),
  CHECK(mode IN ('any','all')),
  CHECK(status IN ('waiting','pending','approved','rejected','skipped','cancelled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_pms_approval_step_user ON pms_approval_step(approver_id,status);
--;;
CREATE INDEX idx_pms_approval_step_flow ON pms_approval_step(flow_id,level_no);
--;;
