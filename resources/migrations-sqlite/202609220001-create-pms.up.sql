CREATE TABLE pms_project (
  project_id VARCHAR(36) PRIMARY KEY,
  project_no VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(200) NOT NULL,
  customer VARCHAR(200) NOT NULL DEFAULT '',
  contract_no VARCHAR(100) NOT NULL DEFAULT '',
  project_type VARCHAR(20) NOT NULL DEFAULT 'equipment',
  manager_id BIGINT NOT NULL,
  dept_id BIGINT NOT NULL,
  start_date VARCHAR(10),
  end_date VARCHAR(10),
  status VARCHAR(20) NOT NULL DEFAULT 'draft',
  version INTEGER NOT NULL DEFAULT 1,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX idx_pms_project_manager ON pms_project(manager_id);
--;;
CREATE INDEX idx_pms_project_status ON pms_project(status);
--;;
CREATE TABLE pms_node (
  node_id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  parent_id VARCHAR(36),
  node_type VARCHAR(20) NOT NULL,
  node_code VARCHAR(64) NOT NULL,
  name VARCHAR(200) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (project_id, node_code),
  UNIQUE (project_id, node_id),
  FOREIGN KEY (project_id) REFERENCES pms_project(project_id),
  FOREIGN KEY (project_id, parent_id) REFERENCES pms_node(project_id, node_id)
);
--;;
CREATE INDEX idx_pms_node_parent ON pms_node(project_id, parent_id);
--;;
CREATE TABLE pms_member (
  project_id VARCHAR(36) NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (project_id, user_id),
  FOREIGN KEY (project_id) REFERENCES pms_project(project_id)
);
--;;
CREATE INDEX idx_pms_member_user ON pms_member(user_id);
--;;
CREATE TABLE pms_event (
  event_id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  description VARCHAR(500) NOT NULL,
  actor_id BIGINT NOT NULL,
  actor_name VARCHAR(100) NOT NULL,
  from_status VARCHAR(20),
  to_status VARCHAR(20),
  payload TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (project_id) REFERENCES pms_project(project_id)
);
--;;
CREATE INDEX idx_pms_event_project ON pms_event(project_id, created_at);
--;;
