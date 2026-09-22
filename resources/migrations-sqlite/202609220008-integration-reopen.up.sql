CREATE TABLE pms_reopen_request (
 request_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 project_version INTEGER NOT NULL, submitted_by BIGINT NOT NULL, reviewer_id BIGINT NOT NULL,
 reason TEXT NOT NULL, scope TEXT NOT NULL, status VARCHAR(20) NOT NULL,
 review_note TEXT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at TIMESTAMP NULL,
 UNIQUE(project_id,project_version), FOREIGN KEY(project_id) REFERENCES pms_project(project_id)
);
--;;
ALTER TABLE pms_lifecycle_state ADD COLUMN reopened_version INTEGER;
--;;
CREATE TABLE pms_inbox (
 message_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 source VARCHAR(30) NOT NULL, event_id VARCHAR(100) NOT NULL, entity_type VARCHAR(40) NOT NULL,
 external_key VARCHAR(150) NOT NULL, source_revision INTEGER NOT NULL,
 payload_hash VARCHAR(64) NOT NULL, payload_json TEXT NOT NULL, status VARCHAR(20) NOT NULL,
 received_by BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(project_id,source,event_id), FOREIGN KEY(project_id) REFERENCES pms_project(project_id)
);
--;;
CREATE TABLE pms_external_fact (
 fact_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, source VARCHAR(30) NOT NULL,
 entity_type VARCHAR(40) NOT NULL, external_key VARCHAR(150) NOT NULL, source_revision INTEGER NOT NULL,
 message_id VARCHAR(36) NOT NULL, payload_hash VARCHAR(64) NOT NULL, payload_json TEXT NOT NULL,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(project_id,source,entity_type,external_key), FOREIGN KEY(project_id) REFERENCES pms_project(project_id)
);
--;;
CREATE TABLE pms_outbox (
 message_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, target VARCHAR(30) NOT NULL,
 topic VARCHAR(50) NOT NULL, source_id VARCHAR(36) NOT NULL, idempotency_key VARCHAR(100) NOT NULL,
 payload_json TEXT NOT NULL, payload_hash VARCHAR(64) NOT NULL, status VARCHAR(20) NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0, retry_cycle INTEGER NOT NULL DEFAULT 0,
 next_retry_at BIGINT, lease_id VARCHAR(36), lease_started_at BIGINT,
 created_by BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(project_id,target,idempotency_key), FOREIGN KEY(project_id) REFERENCES pms_project(project_id)
);
--;;
CREATE TABLE pms_delivery_attempt (
 attempt_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, message_id VARCHAR(36) NOT NULL,
 attempt_no INTEGER NOT NULL, status VARCHAR(20) NOT NULL, http_status INTEGER,
 receipt_hash VARCHAR(64), receipt_id VARCHAR(150), error_code VARCHAR(80),
 started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at TIMESTAMP NULL,
 UNIQUE(message_id,attempt_no), FOREIGN KEY(message_id) REFERENCES pms_outbox(message_id)
);
--;;
CREATE INDEX idx_outbox_queue ON pms_outbox(project_id,status,next_retry_at);
--;;
CREATE INDEX idx_inbox_object ON pms_inbox(project_id,source,entity_type,external_key);
