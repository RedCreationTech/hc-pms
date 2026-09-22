-- :name finance/time-day :? :1
SELECT * FROM pms_time_day WHERE user_id=:user_id AND work_date=:work_date
--;;
-- :name finance/insert-day! :! :n
INSERT INTO pms_time_day(user_id,work_date,used_minutes) VALUES (:user_id,:work_date,0)
--;;
-- :name finance/reserve-day! :! :n
UPDATE pms_time_day SET used_minutes=used_minutes + :minutes
WHERE user_id=:user_id AND work_date=:work_date AND used_minutes + :minutes <= 1440
--;;
-- :name finance/release-day! :! :n
UPDATE pms_time_day SET used_minutes=used_minutes - :minutes
WHERE user_id=:user_id AND work_date=:work_date AND used_minutes>=:minutes
--;;
-- :name finance/times :? :*
SELECT t.*,u.nick_name AS user_name,r.nick_name AS reviewer_name
FROM pms_time_entry t LEFT JOIN sys_user u ON u.user_id=t.user_id
LEFT JOIN sys_user r ON r.user_id=t.reviewer_id
WHERE t.project_id=:project_id ORDER BY t.created_at,t.entry_id
--;;
-- :name finance/time :? :1
SELECT * FROM pms_time_entry WHERE project_id=:project_id AND entry_id=:entry_id
--;;
-- :name finance/day-minutes :? :1
SELECT COALESCE(SUM(minutes),0) AS total FROM pms_time_entry
WHERE user_id=:user_id AND work_date=:work_date AND status<>'rejected'
--;;
-- :name finance/insert-time! :! :n
INSERT INTO pms_time_entry(entry_id,project_id,task_id,user_id,work_date,minutes,note,status,submitted_by,reviewer_id)
VALUES (:entry_id,:project_id,:task_id,:user_id,:work_date,:minutes,:note,'submitted',:submitted_by,:reviewer_id)
--;;
-- :name finance/review-time! :! :n
UPDATE pms_time_entry SET status=:status,review_note=:review_note,reviewed_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND entry_id=:entry_id AND status='submitted'
--;;
-- :name finance/approved-times :? :*
SELECT * FROM pms_time_entry WHERE project_id=:project_id AND status='approved'
AND work_date>=:from_date AND work_date<=:to_date ORDER BY task_id,entry_id
--;;
-- :name finance/versions :? :*
SELECT v.*,r.nick_name AS reviewer_name FROM pms_cost_version v
LEFT JOIN sys_user r ON r.user_id=v.reviewer_id
WHERE v.project_id=:project_id ORDER BY v.period DESC,v.version_no DESC,v.version_id
--;;
-- :name finance/version :? :1
SELECT * FROM pms_cost_version WHERE project_id=:project_id AND version_id=:version_id
--;;
-- :name finance/next-version :? :1
SELECT COALESCE(MAX(version_no),0)+1 AS next_no FROM pms_cost_version
WHERE project_id=:project_id AND kind=:kind AND period=:period
--;;
-- :name finance/insert-version! :! :n
INSERT INTO pms_cost_version(version_id,project_id,kind,period,currency,name,revenue_minor,version_no,status,submitted_by,reviewer_id)
VALUES (:version_id,:project_id,:kind,:period,:currency,:name,:revenue_minor,:version_no,'draft',:submitted_by,:reviewer_id)
--;;
-- :name finance/entries :? :*
SELECT * FROM pms_cost_entry WHERE project_id=:project_id AND version_id=:version_id ORDER BY created_at,entry_id
--;;
-- :name finance/insert-entry! :! :n
INSERT INTO pms_cost_entry(entry_id,project_id,version_id,category,label,amount_minor,source_ref)
VALUES (:entry_id,:project_id,:version_id,:category,:label,:amount_minor,:source_ref)
--;;
-- :name finance/entry :? :1
SELECT * FROM pms_cost_entry WHERE project_id=:project_id AND entry_id=:entry_id
--;;
-- :name finance/delete-entry! :! :n
DELETE FROM pms_cost_entry WHERE project_id=:project_id AND version_id=:version_id AND entry_id=:entry_id
--;;
-- :name finance/submit-version! :! :n
UPDATE pms_cost_version SET status='submitted',submitted_by=:submitted_by,snapshot_json=:snapshot_json
WHERE project_id=:project_id AND version_id=:version_id AND status='draft'
--;;
-- :name finance/review-version! :! :n
UPDATE pms_cost_version SET status=:status,review_note=:review_note,reviewed_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND version_id=:version_id AND status='submitted'
--;;
-- :name finance/allocations :? :*
SELECT * FROM pms_cost_allocation WHERE project_id=:project_id ORDER BY created_at,allocation_id
--;;
-- :name finance/allocation-key :? :1
SELECT * FROM pms_cost_allocation WHERE project_id=:project_id AND idempotency_key=:idempotency_key
--;;
-- :name finance/insert-allocation! :! :n
INSERT INTO pms_cost_allocation(allocation_id,project_id,version_id,idempotency_key,amount_minor,from_date,to_date,input_hash,input_json,result_json)
VALUES (:allocation_id,:project_id,:version_id,:idempotency_key,:amount_minor,:from_date,:to_date,:input_hash,:input_json,:result_json)
--;;
-- :name finance/cancel-version! :! :n
UPDATE pms_cost_version SET status='cancelled',review_note=:review_note
WHERE project_id=:project_id AND version_id=:version_id AND status IN ('draft','rejected')
--;;
