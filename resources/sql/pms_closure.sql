-- :name closure/items :? :*
SELECT c.*,u.nick_name AS owner_name FROM pms_closure_item c LEFT JOIN sys_user u ON u.user_id=c.owner_id
WHERE c.project_id=:project_id ORDER BY c.created_at,c.item_id
--;;
-- :name closure/item :? :1
SELECT * FROM pms_closure_item WHERE project_id=:project_id AND item_id=:item_id
--;;
-- :name closure/insert-item! :! :n
INSERT INTO pms_closure_item(item_id,project_id,kind,title,required,owner_id,due_date,status)
VALUES (:item_id,:project_id,:kind,:title,:required,:owner_id,:due_date,'open')
--;;
-- :name closure/complete-item! :! :n
UPDATE pms_closure_item SET status='completed',evidence_ref=:evidence_ref,comment=:comment,completed_by=:completed_by
WHERE project_id=:project_id AND item_id=:item_id AND status='open'
--;;
-- :name closure/lessons :? :*
SELECT * FROM pms_lesson WHERE project_id=:project_id ORDER BY created_at,lesson_id
--;;
-- :name closure/insert-lesson! :! :n
INSERT INTO pms_lesson(lesson_id,project_id,title,category,content,created_by)
VALUES (:lesson_id,:project_id,:title,:category,:content,:created_by)
--;;
-- :name closure/approval :? :1
SELECT * FROM pms_closure_approval WHERE project_id=:project_id ORDER BY project_version DESC LIMIT 1
--;;
-- :name closure/insert-approval! :! :n
INSERT INTO pms_closure_approval(approval_id,project_id,status,submitted_by,reviewer_id,project_version,snapshot_hash,snapshot_json)
VALUES (:approval_id,:project_id,'submitted',:submitted_by,:reviewer_id,:project_version,:snapshot_hash,:snapshot_json)
--;;
-- :name closure/review! :! :n
UPDATE pms_closure_approval SET status=:status,review_note=:review_note,reviewed_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND approval_id=:approval_id AND status='submitted'
--;;
-- :name lifecycle/state :? :1
SELECT * FROM pms_lifecycle_state WHERE project_id=:project_id
--;;
-- :name lifecycle/insert-state! :! :n
INSERT INTO pms_lifecycle_state(project_id,resume_status,paused_reason) VALUES (:project_id,:resume_status,:paused_reason)
--;;
-- :name lifecycle/update-state! :! :n
UPDATE pms_lifecycle_state SET resume_status=:resume_status,paused_reason=:paused_reason WHERE project_id=:project_id
--;;
-- :name lifecycle/set-status! :! :n
UPDATE pms_project SET status=:status,updated_at=CURRENT_TIMESTAMP WHERE project_id=:project_id
--;;
-- :name lifecycle/archive! :! :n
UPDATE pms_lifecycle_state SET archived_at=CURRENT_TIMESTAMP WHERE project_id=:project_id
--;;
-- :name lifecycle/delete-member! :! :n
DELETE FROM pms_member WHERE project_id=:project_id AND user_id=:user_id
--;;
-- :name reopen/latest :? :1
SELECT * FROM pms_reopen_request WHERE project_id=:project_id ORDER BY project_version DESC LIMIT 1
--;;
-- :name reopen/insert! :! :n
INSERT INTO pms_reopen_request(request_id,project_id,project_version,submitted_by,reviewer_id,reason,scope,status)
VALUES (:request_id,:project_id,:project_version,:submitted_by,:reviewer_id,:reason,:scope,'submitted')
--;;
-- :name reopen/review! :! :n
UPDATE pms_reopen_request SET status=:status,review_note=:review_note,reviewed_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND request_id=:request_id AND status='submitted'
--;;
-- :name reopen/reset-archive! :! :n
UPDATE pms_lifecycle_state SET archived_at=NULL,reopened_version=:reopened_version WHERE project_id=:project_id
--;;
