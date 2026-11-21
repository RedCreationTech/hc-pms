-- 回退 D07 变更损失列.
-- SQLite 的 DROP COLUMN 不能直接删掉参与表级 UNIQUE(version_id,change_code) 的列 (会报
-- "error in table ... after drop column: no such column"), 只能按 12 步建新表回退.
PRAGMA foreign_keys=OFF;
--;;
CREATE TABLE pms_cost_entry_new (
 entry_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
 version_id VARCHAR(36) NOT NULL, category VARCHAR(30) NOT NULL,
 label VARCHAR(200) NOT NULL, amount_minor BIGINT NOT NULL, source_ref VARCHAR(200),
 created_at TEXT NOT NULL DEFAULT (datetime('now')),
 UNIQUE(version_id,source_ref),
 FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
 FOREIGN KEY(version_id) REFERENCES pms_cost_version(version_id)
);
--;;
INSERT INTO pms_cost_entry_new(entry_id,project_id,version_id,category,label,amount_minor,source_ref,created_at)
SELECT entry_id,project_id,version_id,category,label,amount_minor,source_ref,created_at FROM pms_cost_entry;
--;;
DROP TABLE pms_cost_entry;
--;;
ALTER TABLE pms_cost_entry_new RENAME TO pms_cost_entry;
--;;
CREATE INDEX IF NOT EXISTS idx_pms_cost_entry_project ON pms_cost_entry(project_id);
--;;
PRAGMA foreign_keys=ON;
--;;