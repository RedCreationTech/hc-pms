ALTER TABLE pms_cost_entry ADD COLUMN change_code VARCHAR(100);
--;;
CREATE INDEX idx_pms_cost_entry_change ON pms_cost_entry(project_id,change_code);
--;;
CREATE TABLE pms_cost_entry_new (
 entry_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 version_id VARCHAR(36) NOT NULL, category VARCHAR(30) NOT NULL,
 label VARCHAR(200) NOT NULL, amount_minor BIGINT NOT NULL, source_ref VARCHAR(200),
 change_code VARCHAR(100),
 created_at TEXT NOT NULL DEFAULT CURRENT_TEXT,
 UNIQUE(version_id,source_ref), UNIQUE(version_id,change_code),
 FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
 FOREIGN KEY(version_id) REFERENCES pms_cost_version(version_id)
);
--;;
INSERT INTO pms_cost_entry_new(entry_id,project_id,version_id,category,label,amount_minor,source_ref,change_code,created_at)
SELECT entry_id,project_id,version_id,category,label,amount_minor,source_ref,NULL,created_at FROM pms_cost_entry;
--;;
DROP TABLE pms_cost_entry;
--;;
ALTER TABLE pms_cost_entry_new RENAME TO pms_cost_entry;
--;;
CREATE INDEX idx_pms_cost_entry_change ON pms_cost_entry(project_id,change_code);
--;;