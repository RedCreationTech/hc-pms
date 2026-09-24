-- :name pms/projects :? :*
SELECT p.*, COALESCE(u.nick_name, u.user_name) AS manager_name, d.dept_name
FROM pms_project p LEFT JOIN sys_user u ON u.user_id = p.manager_id
LEFT JOIN sys_dept d ON d.dept_id = p.dept_id
WHERE (:admin = 1 OR p.manager_id = :user_id
       OR EXISTS (SELECT 1 FROM pms_member pm WHERE pm.project_id = p.project_id AND pm.user_id = :user_id))
  AND (:q IS NULL OR INSTR(LOWER(p.name), LOWER(:q)) > 0 OR INSTR(LOWER(p.project_no), LOWER(:q)) > 0 OR INSTR(LOWER(p.customer), LOWER(:q)) > 0)
  AND (:status IS NULL OR p.status = :status)
ORDER BY p.created_at DESC, p.project_id LIMIT :page_size OFFSET :offset
--;;

-- :name pms/project-count :? :1
SELECT COUNT(*) AS total FROM pms_project p
WHERE (:admin = 1 OR p.manager_id = :user_id
       OR EXISTS (SELECT 1 FROM pms_member pm WHERE pm.project_id = p.project_id AND pm.user_id = :user_id))
  AND (:q IS NULL OR INSTR(LOWER(p.name), LOWER(:q)) > 0 OR INSTR(LOWER(p.project_no), LOWER(:q)) > 0 OR INSTR(LOWER(p.customer), LOWER(:q)) > 0)
  AND (:status IS NULL OR p.status = :status)
--;;

-- :name pms/project :? :1
SELECT p.*, COALESCE(u.nick_name, u.user_name) AS manager_name, d.dept_name
FROM pms_project p LEFT JOIN sys_user u ON u.user_id = p.manager_id
LEFT JOIN sys_dept d ON d.dept_id = p.dept_id
WHERE p.project_id = :project_id
--;;

-- :name pms/project-no :? :1
SELECT project_id FROM pms_project WHERE project_no = :project_no
--;;

-- :name pms/insert-project! :! :n
INSERT INTO pms_project (project_id,project_no,name,customer,contract_no,project_type,manager_id,dept_id,start_date,end_date,status,version,created_by)
VALUES (:project_id,:project_no,:name,:customer,:contract_no,:project_type,:manager_id,:dept_id,:start_date,:end_date,'draft',1,:created_by)
--;;

-- :name pms/update-project! :! :n
UPDATE pms_project SET project_no=:project_no,name=:name,customer=:customer,contract_no=:contract_no,
project_type=:project_type,manager_id=:manager_id,dept_id=:dept_id,start_date=:start_date,end_date=:end_date,
version=version+1,updated_at=CURRENT_TIMESTAMP WHERE project_id=:project_id AND version=:version
--;;

-- :name pms/transition! :! :n
UPDATE pms_project SET status=:status,version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE project_id=:project_id AND version=:version
--;;

-- :name pms/touch-project! :! :n
UPDATE pms_project SET version=version+1,updated_at=CURRENT_TIMESTAMP WHERE project_id=:project_id AND version=:version
--;;

-- :name pms/nodes :? :*
SELECT * FROM pms_node WHERE project_id=:project_id ORDER BY created_at,node_code
--;;

-- :name pms/node :? :1
SELECT * FROM pms_node WHERE node_id=:node_id AND project_id=:project_id
--;;

-- :name pms/node-code :? :1
SELECT node_id,node_type FROM pms_node WHERE project_id=:project_id AND node_code=:node_code
--;;

-- :name pms/insert-node! :! :n
INSERT INTO pms_node (node_id,project_id,parent_id,node_type,node_code,name)
VALUES (:node_id,:project_id,:parent_id,:node_type,:node_code,:name)
--;;

-- :name pms/members :? :*
SELECT m.*,u.user_name,u.nick_name FROM pms_member m JOIN sys_user u ON u.user_id=m.user_id
WHERE m.project_id=:project_id ORDER BY m.role,u.user_name
--;;

-- :name pms/member :? :1
SELECT * FROM pms_member WHERE project_id=:project_id AND user_id=:user_id
--;;

-- :name pms/insert-member! :! :n
INSERT INTO pms_member (project_id,user_id,role) VALUES (:project_id,:user_id,:role)
--;;

-- :name pms/update-member! :! :n
UPDATE pms_member SET role=:role WHERE project_id=:project_id AND user_id=:user_id
--;;

-- :name pms/insert-event! :! :n
INSERT INTO pms_event (event_id,project_id,event_type,description,actor_id,actor_name,from_status,to_status,payload,aggregate_version)
VALUES (:event_id,:project_id,:event_type,:description,:actor_id,:actor_name,:from_status,:to_status,:payload,:aggregate_version)
--;;

-- :name pms/events :? :*
SELECT * FROM pms_event WHERE project_id=:project_id ORDER BY aggregate_version DESC
--;;

-- :name pms/dashboard :? :1
SELECT COUNT(*) AS total,
COALESCE(SUM(CASE WHEN p.status IN ('initiated','planning','execution','paused','closing') THEN 1 ELSE 0 END),0) AS active,
COALESCE(SUM(CASE WHEN p.end_date < :today AND p.status NOT IN ('closed','cancelled') THEN 1 ELSE 0 END),0) AS overdue,
COALESCE(SUM(CASE WHEN p.status='draft' THEN 1 ELSE 0 END),0) AS draft
FROM pms_project p WHERE (:admin = 1 OR p.manager_id = :user_id
       OR EXISTS (SELECT 1 FROM pms_member pm WHERE pm.project_id = p.project_id AND pm.user_id = :user_id))
--;;

-- :name pms/user :? :1
SELECT user_id,user_name,nick_name,dept_id FROM sys_user WHERE user_id=:user_id AND status='0' AND del_flag='0'
--;;

-- :name pms/users :? :*
SELECT user_id,user_name,nick_name,dept_id FROM sys_user WHERE status='0' AND del_flag='0' ORDER BY user_name
--;;

-- :name pms/depts :? :*
SELECT dept_id,dept_name,parent_id FROM sys_dept WHERE status='0' AND del_flag='0' ORDER BY order_num,dept_id
--;;

-- :name pms/dept :? :1
SELECT dept_id FROM sys_dept WHERE dept_id=:dept_id AND status='0' AND del_flag='0'
--;;

-- :name pms/user-roles :? :*
SELECT r.role_key FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.role_id WHERE ur.user_id=:user_id AND r.status='0' AND r.del_flag='0'
--;;

-- :name pms/user-perms :? :*
SELECT DISTINCT m.perms FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id=m.menu_id
JOIN sys_role r ON r.role_id=rm.role_id JOIN sys_user_role ur ON ur.role_id=r.role_id
WHERE ur.user_id=:user_id AND r.status='0' AND r.del_flag='0' AND m.status='0'
--;;

-- :name pms/update-root! :! :n
UPDATE pms_node SET node_code=:project_no,name=:name WHERE project_id=:project_id AND node_type='main'
--;;

-- :name pms/authorized-projects :? :*
SELECT p.*, COALESCE(u.nick_name, u.user_name) AS manager_name, d.dept_name
FROM pms_project p LEFT JOIN sys_user u ON u.user_id = p.manager_id
LEFT JOIN sys_dept d ON d.dept_id = p.dept_id
WHERE (:admin = 1 OR p.manager_id = :user_id
       OR EXISTS (SELECT 1 FROM pms_member pm WHERE pm.project_id = p.project_id AND pm.user_id = :user_id))
ORDER BY p.created_at DESC, p.project_id
--;;

-- 组合看板/我的待办按授权项目集合一次性批量读取, 避免逐项目逐类型 N+1 查询; 结果在内存按 project_id 分组.
-- :name pms/gov-in-projects :? :*
SELECT * FROM pms_gov_record WHERE project_id IN (:v*:project_ids) AND kind IN (:v*:kinds)
ORDER BY project_id,kind,created_at DESC,revision DESC,record_id
--;;

-- :name pms/delivery-in-projects :? :*
SELECT * FROM pms_delivery_record WHERE project_id IN (:v*:project_ids) AND kind IN (:v*:kinds)
ORDER BY project_id,kind,created_at DESC,record_id
--;;

-- :name pms/tasks-in-projects :? :*
SELECT t.*,u.nick_name AS owner_name FROM pms_plan_task t LEFT JOIN sys_user u ON u.user_id=t.owner_id
WHERE t.project_id IN (:v*:project_ids) ORDER BY t.project_id,t.wbs_code,t.task_id
--;;

-- :name pms/nodes-in-projects :? :*
SELECT * FROM pms_node WHERE project_id IN (:v*:project_ids) ORDER BY project_id,created_at,node_code
--;;

-- :name pms/cost-versions-in-projects :? :*
SELECT v.*,r.nick_name AS reviewer_name FROM pms_cost_version v LEFT JOIN sys_user r ON r.user_id=v.reviewer_id
WHERE v.project_id IN (:v*:project_ids) ORDER BY v.project_id,v.period DESC,v.version_no DESC,v.version_id
--;;

-- :name pms/cost-entries-in-projects :? :*
SELECT * FROM pms_cost_entry WHERE project_id IN (:v*:project_ids) ORDER BY project_id,version_id,created_at,entry_id
--;;

-- :name pms/times-in-projects :? :*
SELECT t.*,u.nick_name AS user_name,r.nick_name AS reviewer_name
FROM pms_time_entry t LEFT JOIN sys_user u ON u.user_id=t.user_id
LEFT JOIN sys_user r ON r.user_id=t.reviewer_id
WHERE t.project_id IN (:v*:project_ids) ORDER BY t.project_id,t.created_at,t.entry_id
--;;

-- :name pms/search-gov :? :*
SELECT r.* FROM pms_gov_record r JOIN pms_project p ON p.project_id = r.project_id
WHERE (:admin = 1 OR p.manager_id = :user_id
       OR EXISTS (SELECT 1 FROM pms_member pm WHERE pm.project_id = p.project_id AND pm.user_id = :user_id))
  AND (INSTR(LOWER(r.code), :q) > 0 OR INSTR(LOWER(r.payload), :q) > 0)
ORDER BY r.created_at DESC LIMIT 500
--;;

-- :name pms/search-delivery :? :*
SELECT r.* FROM pms_delivery_record r JOIN pms_project p ON p.project_id = r.project_id
WHERE (:admin = 1 OR p.manager_id = :user_id
       OR EXISTS (SELECT 1 FROM pms_member pm WHERE pm.project_id = p.project_id AND pm.user_id = :user_id))
  AND (INSTR(LOWER(r.code), :q) > 0 OR INSTR(LOWER(r.payload), :q) > 0)
ORDER BY r.created_at DESC LIMIT 500
--;;

-- :name pms/search-tasks :? :*
SELECT t.* FROM pms_plan_task t JOIN pms_project p ON p.project_id = t.project_id
WHERE (:admin = 1 OR p.manager_id = :user_id
       OR EXISTS (SELECT 1 FROM pms_member pm WHERE pm.project_id = p.project_id AND pm.user_id = :user_id))
  AND (INSTR(LOWER(t.wbs_code), :q) > 0 OR INSTR(LOWER(t.name), :q) > 0 OR INSTR(LOWER(t.description), :q) > 0)
ORDER BY t.wbs_code LIMIT 500
--;;
