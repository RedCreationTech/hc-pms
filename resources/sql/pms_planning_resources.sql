-- :name planning/resources :? :*
SELECT * FROM pms_plan_resource WHERE project_id=:project_id ORDER BY name,resource_id
--;;

-- :name planning/resource :? :1
SELECT * FROM pms_plan_resource WHERE project_id=:project_id AND resource_id=:resource_id
--;;

-- :name planning/create-resource! :! :n
INSERT INTO pms_plan_resource(resource_id,project_id,name,resource_type,user_id,daily_capacity)
VALUES (:resource_id,:project_id,:name,:resource_type,:user_id,:daily_capacity)
--;;

-- :name planning/update-resource! :! :n
UPDATE pms_plan_resource SET name=:name,resource_type=:resource_type,user_id=:user_id,daily_capacity=:daily_capacity
WHERE project_id=:project_id AND resource_id=:resource_id
--;;

-- :name planning/delete-resource! :! :n
DELETE FROM pms_plan_resource WHERE project_id=:project_id AND resource_id=:resource_id
--;;

-- :name planning/capacities :? :*
SELECT project_id,resource_id,capacity_date AS date,capacity_hours FROM pms_plan_capacity WHERE project_id=:project_id ORDER BY capacity_date,resource_id
--;;

-- :name planning/delete-capacity! :! :n
DELETE FROM pms_plan_capacity WHERE project_id=:project_id AND resource_id=:resource_id AND capacity_date=:date
--;;

-- :name planning/delete-capacities! :! :n
DELETE FROM pms_plan_capacity WHERE project_id=:project_id AND resource_id=:resource_id
--;;

-- :name planning/create-capacity! :! :n
INSERT INTO pms_plan_capacity(project_id,resource_id,capacity_date,capacity_hours)
VALUES (:project_id,:resource_id,:date,:capacity_hours)
--;;

-- :name planning/allocations :? :*
SELECT * FROM pms_plan_allocation WHERE project_id=:project_id ORDER BY allocation_id
--;;

-- :name planning/create-allocation! :! :n
INSERT INTO pms_plan_allocation(allocation_id,project_id,task_id,resource_id,hours_per_day)
VALUES (:allocation_id,:project_id,:task_id,:resource_id,:hours_per_day)
--;;

-- :name planning/delete-allocation! :! :n
DELETE FROM pms_plan_allocation WHERE project_id=:project_id AND allocation_id=:allocation_id
--;;

-- :name planning/shared-person-projects :? :*
SELECT DISTINCT p.project_id,p.start_date FROM pms_project p
JOIN pms_plan_resource r ON r.project_id=p.project_id
WHERE p.project_id<>:project_id AND p.status NOT IN ('closed','cancelled')
AND r.resource_type='person' AND r.user_id IN
(SELECT user_id FROM pms_plan_resource WHERE project_id=:project_id AND resource_type='person')
ORDER BY p.project_id
--;;
