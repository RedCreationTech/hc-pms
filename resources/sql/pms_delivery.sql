-- :name delivery/list :? :*
SELECT * FROM pms_delivery_record WHERE project_id=:project_id AND kind=:kind ORDER BY created_at DESC,record_id;
--;;
-- :name delivery/record :? :1
SELECT * FROM pms_delivery_record WHERE project_id=:project_id AND record_id=:record_id;
--;;
-- :name delivery/insert! :! :n
INSERT INTO pms_delivery_record(record_id,project_id,kind,code,revision,status,created_by,owner_id,payload)
VALUES(:record_id,:project_id,:kind,:code,:revision,:status,:created_by,:owner_id,:payload);
--;;
-- :name delivery/update! :! :n
UPDATE pms_delivery_record SET status=:status,owner_id=:owner_id,payload=:payload,updated_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND record_id=:record_id;
--;;
