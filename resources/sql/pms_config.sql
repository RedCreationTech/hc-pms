-- :name config/list :? :*
SELECT c.*,COALESCE(u.nick_name,u.user_name) AS created_by_name FROM pms_config_record c
LEFT JOIN sys_user u ON u.user_id=c.created_by
WHERE c.kind=:kind ORDER BY c.code,c.revision DESC,c.created_at DESC;
--;;
-- :name config/all :? :*
SELECT * FROM pms_config_record ORDER BY kind,code,revision DESC;
--;;
-- :name config/record :? :1
SELECT * FROM pms_config_record WHERE config_id=:config_id;
--;;
-- :name config/insert! :! :n
INSERT INTO pms_config_record(config_id,kind,code,revision,status,created_by,payload)
VALUES(:config_id,:kind,:code,:revision,:status,:created_by,:payload);
--;;
-- :name config/update! :! :n
UPDATE pms_config_record SET status=:status,payload=:payload,updated_at=CURRENT_TIMESTAMP
WHERE config_id=:config_id;
--;;
-- :name config/project-count :? :1
SELECT COUNT(*) AS total FROM pms_project;
--;;
-- :name config/node-count :? :1
SELECT COUNT(*) AS total FROM pms_node WHERE node_type=:node_type;
--;;
-- :name config/gov-count :? :1
SELECT COUNT(DISTINCT code) AS total FROM pms_gov_record WHERE project_id=:project_id AND kind=:kind;
--;;
-- :name config/task-count :? :1
SELECT COUNT(*) AS total FROM pms_plan_task WHERE project_id=:project_id;
--;;
