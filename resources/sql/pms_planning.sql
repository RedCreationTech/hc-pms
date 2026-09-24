-- :name planning/plan :? :1
SELECT * FROM pms_plan WHERE project_id=:project_id
--;;

-- :name planning/insert-plan! :! :n
INSERT INTO pms_plan(project_id,revision,calendar_json) VALUES (:project_id,0,:calendar_json)
--;;

-- :name planning/bump-plan! :! :n
UPDATE pms_plan SET revision=revision+1 WHERE project_id=:project_id
--;;

-- :name planning/calendar! :! :n
UPDATE pms_plan SET calendar_json=:calendar_json WHERE project_id=:project_id
--;;

-- :name planning/tasks :? :*
SELECT t.*,u.nick_name AS owner_name FROM pms_plan_task t LEFT JOIN sys_user u ON u.user_id=t.owner_id WHERE t.project_id=:project_id ORDER BY t.wbs_code,t.task_id
--;;

-- :name planning/task :? :1
SELECT * FROM pms_plan_task WHERE project_id=:project_id AND task_id=:task_id
--;;

-- :name planning/task-code :? :1
SELECT task_id FROM pms_plan_task WHERE project_id=:project_id AND wbs_code=:wbs_code
--;;

-- :name planning/create-task! :! :n
INSERT INTO pms_plan_task(task_id,project_id,parent_id,wbs_code,name,task_type,duration_days,owner_id,start_date,description,remaining_days,source_type,source_id,node_id,stage_code)
VALUES (:task_id,:project_id,:parent_id,:wbs_code,:name,:task_type,:duration_days,:owner_id,:start_date,:description,:duration_days,:source_type,:source_id,:node_id,:stage_code)
--;;

-- :name planning/update-task! :! :n
UPDATE pms_plan_task SET parent_id=:parent_id,wbs_code=:wbs_code,name=:name,task_type=:task_type,
duration_days=:duration_days,owner_id=:owner_id,start_date=:start_date,description=:description,node_id=:node_id,stage_code=:stage_code,
remaining_days=CASE WHEN status='todo' THEN :duration_days ELSE remaining_days END
WHERE project_id=:project_id AND task_id=:task_id
--;;

-- :name planning/delete-task! :! :n
DELETE FROM pms_plan_task WHERE project_id=:project_id AND task_id=:task_id
--;;

-- :name planning/dependencies :? :*
SELECT * FROM pms_plan_dependency WHERE project_id=:project_id ORDER BY dependency_id
--;;

-- :name planning/create-dependency! :! :n
INSERT INTO pms_plan_dependency(dependency_id,project_id,predecessor_id,successor_id,dependency_type,lag_days)
VALUES (:dependency_id,:project_id,:predecessor_id,:successor_id,:dependency_type,:lag_days)
--;;

-- :name planning/delete-dependency! :! :n
DELETE FROM pms_plan_dependency WHERE project_id=:project_id AND dependency_id=:dependency_id
--;;

-- :name planning/feedback :? :*
SELECT f.*,u.user_name FROM pms_plan_feedback f LEFT JOIN sys_user u ON u.user_id=f.user_id
WHERE f.project_id=:project_id ORDER BY f.project_version DESC
--;;

-- :name planning/create-feedback! :! :n
INSERT INTO pms_plan_feedback(feedback_id,project_id,task_id,user_id,status,percent_complete,remaining_days,comment,project_version)
VALUES (:feedback_id,:project_id,:task_id,:user_id,:status,:percent_complete,:remaining_days,:comment,:project_version)
--;;

-- :name planning/task-progress! :! :n
UPDATE pms_plan_task SET status=:status,percent_complete=:percent_complete,remaining_days=:remaining_days
WHERE project_id=:project_id AND task_id=:task_id
--;;

-- :name planning/task-finance-references :? :1
SELECT COUNT(*) AS total FROM pms_time_entry WHERE project_id=:project_id AND task_id=:task_id
--;;
