CREATE TABLE pms_time_day (
 user_id BIGINT NOT NULL, work_date VARCHAR(10) NOT NULL, used_minutes INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,work_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE TABLE pms_time_entry (
 entry_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 task_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL, work_date VARCHAR(10) NOT NULL,
 minutes INTEGER NOT NULL, note VARCHAR(1000) NOT NULL,
 status VARCHAR(20) NOT NULL, submitted_by BIGINT NOT NULL, reviewer_id BIGINT NOT NULL,
 review_note VARCHAR(1000) NOT NULL DEFAULT '', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 reviewed_at TIMESTAMP NULL, FOREIGN KEY(project_id) REFERENCES pms_project(project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_pms_time_project ON pms_time_entry(project_id, status, work_date);
--;;
CREATE INDEX idx_pms_time_user ON pms_time_entry(user_id, work_date);
--;;
CREATE TABLE pms_cost_version (
 version_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 kind VARCHAR(20) NOT NULL, period VARCHAR(7) NOT NULL, currency VARCHAR(3) NOT NULL,
 name VARCHAR(200) NOT NULL, revenue_minor BIGINT NOT NULL, version_no INTEGER NOT NULL,
 status VARCHAR(20) NOT NULL, submitted_by BIGINT NOT NULL, reviewer_id BIGINT NOT NULL,
 review_note VARCHAR(1000) NOT NULL DEFAULT '', snapshot_json LONGTEXT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at TIMESTAMP NULL,
 UNIQUE(project_id,kind,period,version_no), FOREIGN KEY(project_id) REFERENCES pms_project(project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE TABLE pms_cost_entry (
 entry_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 version_id VARCHAR(36) NOT NULL, category VARCHAR(30) NOT NULL,
 label VARCHAR(200) NOT NULL, amount_minor BIGINT NOT NULL, source_ref VARCHAR(200),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(version_id,source_ref), FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
 FOREIGN KEY(version_id) REFERENCES pms_cost_version(version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE TABLE pms_cost_allocation (
 allocation_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 version_id VARCHAR(36) NOT NULL, idempotency_key VARCHAR(100) NOT NULL,
 amount_minor BIGINT NOT NULL, from_date VARCHAR(10) NOT NULL, to_date VARCHAR(10) NOT NULL,
 input_hash VARCHAR(64) NOT NULL, input_json LONGTEXT NOT NULL, result_json LONGTEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(project_id,idempotency_key), FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
 FOREIGN KEY(version_id) REFERENCES pms_cost_version(version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE TABLE pms_closure_item (
 item_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, kind VARCHAR(20) NOT NULL,
 title VARCHAR(200) NOT NULL, required INTEGER NOT NULL DEFAULT 1,
 owner_id BIGINT, due_date VARCHAR(10), status VARCHAR(20) NOT NULL DEFAULT 'open',
 evidence_ref VARCHAR(200), comment VARCHAR(1000), completed_by BIGINT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(project_id) REFERENCES pms_project(project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_pms_closure_project ON pms_closure_item(project_id,kind);
--;;
CREATE TABLE pms_lesson (
 lesson_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 title VARCHAR(200) NOT NULL, category VARCHAR(40) NOT NULL, content LONGTEXT NOT NULL,
 created_by BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(project_id) REFERENCES pms_project(project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE TABLE pms_closure_approval (
 approval_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 status VARCHAR(20) NOT NULL, submitted_by BIGINT NOT NULL, reviewer_id BIGINT NOT NULL,
 project_version INTEGER NOT NULL,
 snapshot_hash VARCHAR(64) NOT NULL, snapshot_json LONGTEXT NOT NULL,
 review_note VARCHAR(1000) NOT NULL DEFAULT '', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 reviewed_at TIMESTAMP NULL, FOREIGN KEY(project_id) REFERENCES pms_project(project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE TABLE pms_lifecycle_state (
 project_id VARCHAR(36) PRIMARY KEY, resume_status VARCHAR(20),
 paused_reason VARCHAR(1000), archived_at TIMESTAMP NULL,
 FOREIGN KEY(project_id) REFERENCES pms_project(project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
