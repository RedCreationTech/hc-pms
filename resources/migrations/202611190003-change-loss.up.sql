ALTER TABLE pms_cost_entry ADD COLUMN change_code VARCHAR(100) NULL;
--;;
ALTER TABLE pms_cost_entry ADD CONSTRAINT pms_cost_entry_change_uq UNIQUE(version_id,change_code);
--;;
CREATE INDEX idx_pms_cost_entry_change ON pms_cost_entry(project_id,change_code);
--;;