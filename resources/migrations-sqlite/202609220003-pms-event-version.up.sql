ALTER TABLE pms_event ADD COLUMN aggregate_version INTEGER NOT NULL DEFAULT 0;
--;;
UPDATE pms_event SET aggregate_version = (
  SELECT COUNT(*) FROM pms_event prior
  WHERE prior.project_id = pms_event.project_id
    AND (prior.created_at < pms_event.created_at
      OR (prior.created_at = pms_event.created_at AND prior.event_id <= pms_event.event_id))
);
--;;
CREATE UNIQUE INDEX idx_pms_event_version ON pms_event(project_id, aggregate_version);
--;;
