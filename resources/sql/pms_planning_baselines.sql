-- :name planning/baselines :? :*
SELECT b.baseline_id,b.project_id,b.plan_revision,b.status,b.change_id,b.snapshot_hash,b.submitted_by,b.submitted_at,b.submit_comment,
b.reviewed_by,b.reviewed_at,b.review_comment,u.user_name AS submitted_name,r.user_name AS reviewed_name
FROM pms_plan_baseline b LEFT JOIN sys_user u ON u.user_id=b.submitted_by
LEFT JOIN sys_user r ON r.user_id=b.reviewed_by WHERE b.project_id=:project_id ORDER BY b.plan_revision DESC
--;;

-- :name planning/baseline :? :1
SELECT * FROM pms_plan_baseline WHERE project_id=:project_id AND baseline_id=:baseline_id
--;;

-- :name planning/create-baseline! :! :n
INSERT INTO pms_plan_baseline(baseline_id,project_id,plan_revision,status,snapshot_json,snapshot_hash,submitted_by,submit_comment,change_id)
VALUES (:baseline_id,:project_id,:plan_revision,'submitted',:snapshot_json,:snapshot_hash,:submitted_by,:submit_comment,:change_id)
--;;

-- :name planning/review-baseline! :! :n
UPDATE pms_plan_baseline SET status=:status,reviewed_by=:reviewed_by,review_comment=:review_comment,reviewed_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND baseline_id=:baseline_id AND status='submitted'
--;;
