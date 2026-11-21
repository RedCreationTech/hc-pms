-- MySQL 语法: DROP INDEX 必须带 ON 表名.
DROP INDEX idx_pms_cost_entry_change ON pms_cost_entry;
--;;
ALTER TABLE pms_cost_entry DROP CONSTRAINT pms_cost_entry_change_uq;
--;;
ALTER TABLE pms_cost_entry DROP COLUMN change_code;
--;;
