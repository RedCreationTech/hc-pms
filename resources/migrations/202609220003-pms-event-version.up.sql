ALTER TABLE pms_event ADD COLUMN aggregate_version INTEGER NOT NULL DEFAULT 0;
--;;
UPDATE pms_event e JOIN (
  SELECT event_id, ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY created_at,event_id) AS sequence_no
  FROM pms_event
) ranked ON ranked.event_id = e.event_id
SET e.aggregate_version = ranked.sequence_no;
--;;
CREATE UNIQUE INDEX idx_pms_event_version ON pms_event(project_id, aggregate_version);
--;;
