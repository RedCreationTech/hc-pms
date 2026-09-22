-- :name integration/inbox :? :*
SELECT * FROM pms_inbox WHERE project_id=:project_id ORDER BY created_at DESC,message_id
--;;
-- :name integration/inbox-key :? :1
SELECT * FROM pms_inbox WHERE project_id=:project_id AND source=:source AND event_id=:event_id
--;;
-- :name integration/insert-inbox! :! :n
INSERT INTO pms_inbox(message_id,project_id,source,event_id,entity_type,external_key,source_revision,payload_hash,payload_json,status,received_by)
VALUES (:message_id,:project_id,:source,:event_id,:entity_type,:external_key,:source_revision,:payload_hash,:payload_json,:status,:received_by)
--;;
-- :name integration/facts :? :*
SELECT * FROM pms_external_fact WHERE project_id=:project_id ORDER BY source,entity_type,external_key
--;;
-- :name integration/fact :? :1
SELECT * FROM pms_external_fact WHERE project_id=:project_id AND source=:source AND entity_type=:entity_type AND external_key=:external_key
--;;
-- :name integration/insert-fact! :! :n
INSERT INTO pms_external_fact(fact_id,project_id,source,entity_type,external_key,source_revision,message_id,payload_hash,payload_json)
VALUES (:fact_id,:project_id,:source,:entity_type,:external_key,:source_revision,:message_id,:payload_hash,:payload_json)
--;;
-- :name integration/update-fact! :! :n
UPDATE pms_external_fact SET source_revision=:source_revision,message_id=:message_id,payload_hash=:payload_hash,payload_json=:payload_json,updated_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND fact_id=:fact_id AND source_revision < :source_revision
--;;
-- :name integration/outbox :? :*
SELECT * FROM pms_outbox WHERE project_id=:project_id ORDER BY created_at DESC,message_id
--;;
-- :name integration/message :? :1
SELECT * FROM pms_outbox WHERE project_id=:project_id AND message_id=:message_id
--;;
-- :name integration/outbox-key :? :1
SELECT * FROM pms_outbox WHERE project_id=:project_id AND target=:target AND idempotency_key=:idempotency_key
--;;
-- :name integration/insert-outbox! :! :n
INSERT INTO pms_outbox(message_id,project_id,target,topic,source_id,idempotency_key,payload_json,payload_hash,status,created_by)
VALUES (:message_id,:project_id,:target,:topic,:source_id,:idempotency_key,:payload_json,:payload_hash,'queued',:created_by)
--;;
-- :name integration/claim! :! :n
UPDATE pms_outbox SET status='sending',attempts=attempts+1,lease_id=:lease_id,lease_started_at=:lease_started_at,updated_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND message_id=:message_id AND status IN ('queued','retry_wait') AND attempts < 5 * (retry_cycle + 1)
--;;
-- :name integration/attempt! :! :n
INSERT INTO pms_delivery_attempt(attempt_id,project_id,message_id,attempt_no,status)
VALUES (:attempt_id,:project_id,:message_id,:attempt_no,'sending')
--;;
-- :name integration/finish! :! :n
UPDATE pms_outbox SET status=:status,next_retry_at=:next_retry_at,updated_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND message_id=:message_id AND lease_id=:lease_id AND status='sending'
--;;
-- :name integration/receipt! :! :n
UPDATE pms_delivery_attempt SET status=:status,http_status=:http_status,receipt_id=:receipt_id,receipt_hash=:receipt_hash,error_code=:error_code,completed_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND attempt_id=:attempt_id AND status='sending'
--;;
-- :name integration/attempts :? :*
SELECT * FROM pms_delivery_attempt WHERE project_id=:project_id ORDER BY started_at DESC,attempt_no DESC
--;;
-- :name integration/retry! :! :n
UPDATE pms_outbox SET status='queued',retry_cycle=retry_cycle+1,next_retry_at=NULL,lease_id=NULL,lease_started_at=NULL,updated_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND message_id=:message_id AND status IN ('retry_wait','dead_letter','sending')
--;;
-- :name integration/abandon-attempt! :! :n
UPDATE pms_delivery_attempt SET status='indeterminate',error_code='lease_expired',completed_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND attempt_id=:attempt_id AND status='sending'
--;;
