CREATE TABLE pms_plan (
 project_id VARCHAR(36) PRIMARY KEY, revision INTEGER NOT NULL DEFAULT 0,
 calendar_json LONGTEXT NOT NULL,
 FOREIGN KEY(project_id) REFERENCES pms_project(project_id)
);
--;;
CREATE TABLE pms_plan_task (
 task_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 parent_id VARCHAR(36), wbs_code VARCHAR(64) NOT NULL, name VARCHAR(200) NOT NULL,
 task_type VARCHAR(20) NOT NULL, duration_days INTEGER NOT NULL,
 owner_id BIGINT, start_date VARCHAR(10) NOT NULL, description VARCHAR(2000) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'todo', percent_complete INTEGER NOT NULL DEFAULT 0,
 remaining_days INTEGER NOT NULL DEFAULT 0, source_type VARCHAR(40), source_id VARCHAR(36),
 UNIQUE(project_id,wbs_code), UNIQUE(project_id,task_id),
 FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
 FOREIGN KEY(project_id,parent_id) REFERENCES pms_plan_task(project_id,task_id)
);
--;;
CREATE TABLE pms_plan_dependency (
 dependency_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 predecessor_id VARCHAR(36) NOT NULL, successor_id VARCHAR(36) NOT NULL,
 dependency_type VARCHAR(2) NOT NULL, lag_days INTEGER NOT NULL DEFAULT 0,
 UNIQUE(project_id,predecessor_id,successor_id),
 FOREIGN KEY(project_id,predecessor_id) REFERENCES pms_plan_task(project_id,task_id),
 FOREIGN KEY(project_id,successor_id) REFERENCES pms_plan_task(project_id,task_id)
);
--;;
CREATE TABLE pms_plan_resource (
 resource_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 name VARCHAR(200) NOT NULL, resource_type VARCHAR(20) NOT NULL,
 user_id BIGINT, daily_capacity DECIMAL(8,2) NOT NULL,
 UNIQUE(project_id,resource_id), FOREIGN KEY(project_id) REFERENCES pms_project(project_id)
);
--;;
CREATE TABLE pms_plan_capacity (
 project_id VARCHAR(36) NOT NULL, resource_id VARCHAR(36) NOT NULL,
 capacity_date VARCHAR(10) NOT NULL, capacity_hours DECIMAL(8,2) NOT NULL,
 PRIMARY KEY(resource_id,capacity_date),
 FOREIGN KEY(project_id,resource_id) REFERENCES pms_plan_resource(project_id,resource_id)
);
--;;
CREATE TABLE pms_plan_allocation (
 allocation_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 task_id VARCHAR(36) NOT NULL, resource_id VARCHAR(36) NOT NULL,
 hours_per_day DECIMAL(8,2) NOT NULL, UNIQUE(task_id,resource_id),
 FOREIGN KEY(project_id,task_id) REFERENCES pms_plan_task(project_id,task_id),
 FOREIGN KEY(project_id,resource_id) REFERENCES pms_plan_resource(project_id,resource_id)
);
--;;
CREATE TABLE pms_plan_baseline (
 baseline_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 plan_revision INTEGER NOT NULL, status VARCHAR(20) NOT NULL, change_id VARCHAR(36),
 snapshot_json LONGTEXT NOT NULL, snapshot_hash VARCHAR(64) NOT NULL,
 submitted_by BIGINT NOT NULL, submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 submit_comment VARCHAR(2000) NOT NULL, reviewed_by BIGINT,
 reviewed_at TIMESTAMP NULL, review_comment VARCHAR(2000),
 UNIQUE(project_id,plan_revision), FOREIGN KEY(project_id) REFERENCES pms_project(project_id)
);
--;;
CREATE TABLE pms_plan_feedback (
 feedback_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 task_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL, percent_complete INTEGER NOT NULL,
 remaining_days INTEGER NOT NULL, comment VARCHAR(2000) NOT NULL,
 project_version INTEGER NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(project_id,project_version),
 FOREIGN KEY(project_id,task_id) REFERENCES pms_plan_task(project_id,task_id)
);
--;;
CREATE INDEX idx_plan_baseline_status ON pms_plan_baseline(project_id,status);
--;;
CREATE INDEX idx_plan_feedback_task ON pms_plan_feedback(project_id,task_id);
--;;
