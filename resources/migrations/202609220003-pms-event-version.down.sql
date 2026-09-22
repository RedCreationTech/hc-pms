DROP INDEX idx_pms_event_version ON pms_event;
--;;
ALTER TABLE pms_event DROP COLUMN aggregate_version;
--;;
