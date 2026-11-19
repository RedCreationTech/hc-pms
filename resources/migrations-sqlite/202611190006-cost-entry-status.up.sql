-- G16 报表口径: 费用明细补状态列, 供报表指标按 approved 等状态精确过滤, 不按"有行即算"的联想口径取数.
ALTER TABLE pms_cost_entry ADD COLUMN status VARCHAR(30);
--;;
CREATE INDEX idx_pms_cost_entry_status ON pms_cost_entry(project_id,status);
--;;
UPDATE pms_cost_entry SET status = (SELECT v.status FROM pms_cost_version v WHERE v.version_id = pms_cost_entry.version_id);
--;;
