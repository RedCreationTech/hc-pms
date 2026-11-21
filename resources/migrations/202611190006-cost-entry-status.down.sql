-- MySQL 语法: DROP INDEX 必须带 ON 表名.
DROP INDEX idx_pms_cost_entry_status ON pms_cost_entry;
--;;
ALTER TABLE pms_cost_entry DROP COLUMN status;
--;;
