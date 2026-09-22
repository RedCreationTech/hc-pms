-- :name gov/list :? :*
SELECT * FROM pms_gov_record WHERE project_id=:project_id AND kind=:kind ORDER BY created_at DESC,revision DESC,record_id;
--;;
-- :name gov/record :? :1
SELECT * FROM pms_gov_record WHERE project_id=:project_id AND record_id=:record_id;
--;;
-- :name gov/insert! :! :n
INSERT INTO pms_gov_record(record_id,project_id,kind,code,revision,status,created_by,owner_id,payload)
VALUES(:record_id,:project_id,:kind,:code,:revision,:status,:created_by,:owner_id,:payload);
--;;
-- :name gov/update! :! :n
UPDATE pms_gov_record SET status=:status,owner_id=:owner_id,payload=:payload,updated_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND record_id=:record_id;
--;;
