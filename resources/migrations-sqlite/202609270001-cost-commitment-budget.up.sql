-- H12 承诺成本与预算控制: 新增承诺台账和预算控制规则两张表, 覆盖合同/采购/人工/其他承诺登记, 独立审批与预算占用门控.
CREATE TABLE pms_cost_commitment (
  commitment_id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  kind VARCHAR(20) NOT NULL,
  code VARCHAR(100) NOT NULL,
  supplier VARCHAR(200) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  gross_minor BIGINT NOT NULL,
  base_currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
  base_minor BIGINT NOT NULL,
  exchange_rate VARCHAR(20) NOT NULL DEFAULT '1',
  description VARCHAR(1000) NOT NULL DEFAULT '',
  version_no INTEGER NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'draft',
  released_minor BIGINT NOT NULL DEFAULT 0,
  submitted_by BIGINT NOT NULL,
  reviewer_id BIGINT NOT NULL,
  review_note VARCHAR(1000) NOT NULL DEFAULT '',
  control_note VARCHAR(1000) NOT NULL DEFAULT '',
  snapshot_json TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_at TEXT NULL,
  released_at TEXT NULL,
  UNIQUE(project_id, code, version_no),
  FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
  CHECK(kind IN ('contract','purchase','labor','other')),
  CHECK(status IN ('draft','submitted','approved','rejected','released','cancelled')),
  CHECK(gross_minor >= 0 AND base_minor >= 0 AND released_minor >= 0 AND released_minor <= base_minor),
  CHECK(currency IN ('CNY','USD','EUR','GBP','HKD')),
  CHECK(base_currency IN ('CNY','USD','EUR','GBP','HKD')),
  CHECK(version_no > 0)
);
--;;
CREATE INDEX idx_pms_commitment_project ON pms_cost_commitment(project_id, status, kind);
--;;
CREATE TABLE pms_budget_control_rule (
  rule_id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36),
  baseline VARCHAR(20) NOT NULL,
  threshold_pct INTEGER NOT NULL,
  action VARCHAR(20) NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  note VARCHAR(1000) NOT NULL DEFAULT '',
  created_by BIGINT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
  CHECK(baseline IN ('estimate','budget')),
  CHECK(action IN ('warn','require_approval','block')),
  CHECK(threshold_pct >= 5 AND threshold_pct <= 500)
);
--;;
CREATE INDEX idx_pms_budget_rule_scope ON pms_budget_control_rule(project_id, baseline, enabled);
--;;
INSERT OR IGNORE INTO pms_budget_control_rule(rule_id,project_id,baseline,threshold_pct,action,enabled,note,created_by)
VALUES ('00000000-0000-0000-0000-000000000bd1', NULL, 'budget', 80, 'warn', 1, '系统默认: 预算占用率达到80%提醒', 1);
--;;
INSERT OR IGNORE INTO pms_budget_control_rule(rule_id,project_id,baseline,threshold_pct,action,enabled,note,created_by)
VALUES ('00000000-0000-0000-0000-000000000bd2', NULL, 'budget', 100, 'block', 1, '系统默认: 预算占用率达到100%阻断新承诺', 1);
--;;
