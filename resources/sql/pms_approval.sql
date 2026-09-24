-- PMS 可配置审批链: 审批流 (一次提交) 与审批步骤 (每级每个审批人一条).

-- :name approval/insert-flow! :! :n
INSERT INTO pms_approval_flow(flow_id,project_id,biz_type,biz_id,title,policy_id,policy_revision,policy_json,amount,status,current_level,submitted_by,created_ms)
VALUES(:flow_id,:project_id,:biz_type,:biz_id,:title,:policy_id,:policy_revision,:policy_json,:amount,'pending',:current_level,:submitted_by,:created_ms);
--;;
-- :name approval/insert-step! :! :n
INSERT INTO pms_approval_step(step_id,flow_id,level_no,level_name,mode,approver_id,status)
VALUES(:step_id,:flow_id,:level_no,:level_name,:mode,:approver_id,:status);
--;;
-- :name approval/flow :? :1
SELECT f.*,p.name AS project_name,p.project_no,COALESCE(u.nick_name,u.user_name) AS submitted_by_name
FROM pms_approval_flow f JOIN pms_project p ON p.project_id=f.project_id
LEFT JOIN sys_user u ON u.user_id=f.submitted_by
WHERE f.flow_id=:flow_id;
--;;
-- :name approval/pending-flow :? :1
SELECT * FROM pms_approval_flow WHERE biz_type=:biz_type AND biz_id=:biz_id AND status='pending';
--;;
-- :name approval/latest-flow :? :1
SELECT * FROM pms_approval_flow WHERE biz_type=:biz_type AND biz_id=:biz_id
ORDER BY created_ms DESC LIMIT 1;
--;;
-- :name approval/project-flows :? :*
SELECT f.*,COALESCE(u.nick_name,u.user_name) AS submitted_by_name FROM pms_approval_flow f
LEFT JOIN sys_user u ON u.user_id=f.submitted_by
WHERE f.project_id=:project_id ORDER BY f.created_ms DESC;
--;;
-- :name approval/steps :? :*
SELECT s.*,COALESCE(u.nick_name,u.user_name) AS approver_name FROM pms_approval_step s
LEFT JOIN sys_user u ON u.user_id=s.approver_id
WHERE s.flow_id=:flow_id ORDER BY s.level_no,s.approver_id;
--;;
-- :name approval/update-flow! :! :n
UPDATE pms_approval_flow SET status=:status,current_level=:current_level,
  finished_at=CASE WHEN :status = 'pending' THEN NULL ELSE CURRENT_TIMESTAMP END
WHERE flow_id=:flow_id AND status='pending';
--;;
-- :name approval/decide-step! :! :n
UPDATE pms_approval_step SET status=:status,comment=:comment,decided_at=CURRENT_TIMESTAMP
WHERE step_id=:step_id AND status='pending';
--;;
-- :name approval/set-level-status! :! :n
UPDATE pms_approval_step SET status=:status WHERE flow_id=:flow_id AND level_no=:level_no AND status=:from_status;
--;;
-- :name approval/close-open-steps! :! :n
UPDATE pms_approval_step SET status=:status WHERE flow_id=:flow_id AND status IN ('pending','waiting');
--;;
-- :name approval/my-pending :? :*
SELECT s.step_id,s.level_no,s.level_name,s.mode,f.flow_id,f.project_id,f.biz_type,f.biz_id,f.title,f.amount,
       f.submitted_by,f.created_at,p.name AS project_name,p.project_no,
       COALESCE(u.nick_name,u.user_name) AS submitted_by_name
FROM pms_approval_step s JOIN pms_approval_flow f ON f.flow_id=s.flow_id
JOIN pms_project p ON p.project_id=f.project_id
LEFT JOIN sys_user u ON u.user_id=f.submitted_by
WHERE s.approver_id=:user_id AND s.status='pending' AND f.status='pending'
ORDER BY f.created_ms;
--;;
-- :name approval/participant :? :1
SELECT s.step_id FROM pms_approval_step s JOIN pms_approval_flow f ON f.flow_id=s.flow_id
WHERE f.project_id=:project_id AND s.approver_id=:user_id AND s.status IN ('pending','approved','rejected','skipped') LIMIT 1;
--;;
-- :name approval/pending-approver-flows :? :*
SELECT f.flow_id,f.biz_type,f.title FROM pms_approval_step s JOIN pms_approval_flow f ON f.flow_id=s.flow_id
WHERE f.project_id=:project_id AND s.approver_id=:user_id AND f.status='pending' AND s.status IN ('pending','waiting');
--;;
-- :name approval/users-by-role :? :*
SELECT DISTINCT u.user_id FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.user_id
JOIN sys_role r ON r.role_id=ur.role_id
WHERE r.role_key=:role_key AND r.status='0' AND r.del_flag='0' AND u.status='0' AND u.del_flag='0'
ORDER BY u.user_id;
--;;
-- :name approval/dept :? :1
SELECT dept_id,parent_id,dept_name,leader_id FROM sys_dept WHERE dept_id=:dept_id AND del_flag='0';
--;;
-- :name approval/active-user :? :1
SELECT user_id,user_name,nick_name FROM sys_user WHERE user_id=:user_id AND status='0' AND del_flag='0';
--;;
-- :name approval/roles :? :*
SELECT role_id,role_name,role_key FROM sys_role WHERE status='0' AND del_flag='0' ORDER BY role_sort,role_id;
--;;
-- :name approval/pending-biz-ids :? :*
SELECT biz_id FROM pms_approval_flow WHERE biz_type=:biz_type AND status='pending';
--;;
-- :name approval/legacy-costs :? :*
SELECT v.version_id,v.project_id,v.name,v.kind,v.currency,v.submitted_by,p.project_no,p.name AS project_name
FROM pms_cost_version v JOIN pms_project p ON p.project_id=v.project_id
WHERE v.status='submitted' AND v.reviewer_id=:user_id
  AND NOT EXISTS (SELECT 1 FROM pms_approval_flow f WHERE f.biz_type='cost-version' AND f.biz_id=v.version_id AND f.status='pending');
--;;
-- :name approval/legacy-closures :? :*
SELECT a.approval_id,a.project_id,a.submitted_by,p.project_no,p.name AS project_name
FROM pms_closure_approval a JOIN pms_project p ON p.project_id=a.project_id
WHERE a.status='submitted' AND a.reviewer_id=:user_id
  AND NOT EXISTS (SELECT 1 FROM pms_approval_flow f WHERE f.biz_type='closure' AND f.biz_id=a.approval_id AND f.status='pending');
--;;
-- :name approval/legacy-reopens :? :*
SELECT r.request_id,r.project_id,r.submitted_by,p.project_no,p.name AS project_name
FROM pms_reopen_request r JOIN pms_project p ON p.project_id=r.project_id
WHERE r.status='submitted' AND r.reviewer_id=:user_id;
--;;
-- :name approval/legacy-baselines :? :*
SELECT b.baseline_id,b.project_id,b.plan_revision,b.submitted_by,p.project_no,p.name AS project_name
FROM pms_plan_baseline b JOIN pms_project p ON p.project_id=b.project_id
WHERE b.status='submitted' AND b.project_id IN (:v*:project_ids) AND b.submitted_by<>:user_id
  AND NOT EXISTS (SELECT 1 FROM pms_approval_flow f WHERE f.biz_type='plan-baseline' AND f.biz_id=b.baseline_id AND f.status='pending');
--;;
