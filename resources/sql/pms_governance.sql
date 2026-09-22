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

-- :name appt/list :? :*
SELECT * FROM pms_appointment WHERE project_id=:project_id ORDER BY code,revision DESC,created_at DESC;
--;;
-- :name appt/record :? :1
SELECT * FROM pms_appointment WHERE project_id=:project_id AND appointment_id=:appointment_id;
--;;
-- :name appt/latest :? :1
SELECT MAX(revision) AS revision FROM pms_appointment WHERE project_id=:project_id AND code=:code;
--;;
-- :name appt/insert! :! :n
INSERT INTO pms_appointment(appointment_id,project_id,code,revision,status,issued_by,issued_on,note,snapshot,snapshot_sha256,content,headcount)
VALUES(:appointment_id,:project_id,:code,:revision,'issued',:issued_by,:issued_on,:note,:snapshot,:snapshot_sha256,:content,:headcount);
--;;
