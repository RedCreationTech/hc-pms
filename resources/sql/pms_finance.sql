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
-- :name finance/insert-correction! :! :n
INSERT INTO pms_time_entry(entry_id,project_id,task_id,user_id,work_date,minutes,note,status,submitted_by,reviewer_id,corrects_entry_id,correction_reason)
VALUES (:entry_id,:project_id,:task_id,:user_id,:work_date,:minutes,:note,'submitted',:submitted_by,:reviewer_id,:corrects_entry_id,:correction_reason)
--;;
-- :name finance/set-time-status! :! :n
UPDATE pms_time_entry SET status=:status WHERE project_id=:project_id AND entry_id=:entry_id AND status=:from_status
--;;
-- :name finance/approved-times-in-period :? :*
SELECT project_id,user_id,task_id,work_date,minutes FROM pms_time_entry
WHERE status='approved' AND work_date>=:from_date AND work_date<=:to_date ORDER BY project_id,entry_id
--;;
-- :name finance/insert-plain-version! :! :n
INSERT INTO pms_cost_version(version_id,project_id,kind,period,currency,name,revenue_minor,version_no,status,submitted_by,reviewer_id)
VALUES (:version_id,:project_id,:kind,:period,:currency,:name,0,:version_no,'draft',:submitted_by,:reviewer_id)
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
-- :name finance/commitments :? :*
SELECT c.*,r.nick_name AS reviewer_name FROM pms_cost_commitment c
LEFT JOIN sys_user r ON r.user_id=c.reviewer_id
WHERE c.project_id=:project_id ORDER BY c.created_at DESC, c.commitment_id
--;;
-- :name finance/commitment :? :1
SELECT * FROM pms_cost_commitment WHERE project_id=:project_id AND commitment_id=:commitment_id
--;;
-- :name finance/commitment-next-no :? :1
SELECT COALESCE(MAX(version_no),0)+1 AS next_no FROM pms_cost_commitment
WHERE project_id=:project_id AND code=:code
--;;
-- :name finance/insert-commitment! :! :n
INSERT INTO pms_cost_commitment(commitment_id,project_id,kind,code,supplier,currency,gross_minor,base_currency,base_minor,exchange_rate,description,version_no,status,submitted_by,reviewer_id)
VALUES (:commitment_id,:project_id,:kind,:code,:supplier,:currency,:gross_minor,:base_currency,:base_minor,:exchange_rate,:description,:version_no,'draft',:submitted_by,:reviewer_id)
--;;
-- :name finance/update-commitment-draft! :! :n
UPDATE pms_cost_commitment SET kind=:kind, supplier=:supplier, currency=:currency,
  gross_minor=:gross_minor, base_currency=:base_currency, base_minor=:base_minor,
  exchange_rate=:exchange_rate, description=:description
WHERE project_id=:project_id AND commitment_id=:commitment_id AND status='draft'
--;;
-- :name finance/submit-commitment! :! :n
UPDATE pms_cost_commitment SET status='submitted', submitted_by=:submitted_by,
  snapshot_json=:snapshot_json, control_note=:control_note
WHERE project_id=:project_id AND commitment_id=:commitment_id AND status='draft'
--;;
-- :name finance/review-commitment! :! :n
UPDATE pms_cost_commitment SET status=:status, review_note=:review_note, reviewed_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND commitment_id=:commitment_id AND status='submitted'
--;;
-- :name finance/release-commitment! :! :n
UPDATE pms_cost_commitment SET released_minor=:released_minor, status=:status, released_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND commitment_id=:commitment_id AND status='approved'
--;;
-- :name finance/cancel-commitment! :! :n
UPDATE pms_cost_commitment SET status='cancelled', review_note=:review_note
WHERE project_id=:project_id AND commitment_id=:commitment_id AND status IN ('draft','rejected')
--;;
-- :name finance/commitment-consumed :? :1
SELECT COALESCE(SUM(base_minor),0) AS committed_minor FROM pms_cost_commitment
WHERE project_id=:project_id AND status IN ('submitted','approved')
--;;
-- :name finance/budget-rules :? :*
SELECT * FROM pms_budget_control_rule
WHERE (project_id=:project_id OR project_id IS NULL)
ORDER BY (project_id IS NULL), baseline, threshold_pct
--;;
-- :name finance/budget-rule :? :1
SELECT * FROM pms_budget_control_rule WHERE rule_id=:rule_id
--;;
-- :name finance/insert-budget-rule! :! :n
INSERT INTO pms_budget_control_rule(rule_id,project_id,baseline,threshold_pct,action,enabled,note,created_by)
VALUES (:rule_id,:project_id,:baseline,:threshold_pct,:action,:enabled,:note,:created_by)
--;;
-- :name finance/update-budget-rule! :! :n
UPDATE pms_budget_control_rule SET threshold_pct=:threshold_pct, action=:action,
  enabled=:enabled, note=:note, updated_at=CURRENT_TIMESTAMP
WHERE rule_id=:rule_id
--;;
-- :name finance/latest-approved-total :? :1
SELECT v.version_id, v.currency,
  COALESCE((SELECT SUM(e.amount_minor) FROM pms_cost_entry e WHERE e.project_id=v.project_id AND e.version_id=v.version_id),0) AS total_minor
FROM pms_cost_version v
WHERE v.project_id=:project_id AND v.kind=:kind AND v.status='approved'
ORDER BY v.period DESC, v.version_no DESC LIMIT 1
--;;
-- :name finance/disable-budget-rule! :! :n
UPDATE pms_budget_control_rule SET enabled=0, note=:note, updated_at=CURRENT_TIMESTAMP
WHERE rule_id=:rule_id
--;;
