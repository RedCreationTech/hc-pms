ALTER TABLE pms_cost_entry DROP INDEX idx_pms_cost_entry_change;
--;;
ALTER TABLE pms_cost_entry DROP CONSTRAINT pms_cost_entry_change_uq;
--;;
ALTER TABLE pms_cost_entry DROP COLUMN change_code;
--;;