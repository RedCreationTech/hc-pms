-- :name list-engineerings :? :*
SELECT * FROM biz_engineering
WHERE del_flag = '0'
  AND (:engineering_name IS NULL OR engineering_name LIKE '%' || :engineering_name || '%')
  AND (:engineering_code IS NULL OR engineering_code LIKE '%' || :engineering_code || '%')
  AND (:status IS NULL OR status = :status)
ORDER BY create_time DESC
LIMIT :limit OFFSET :offset
-- :name count-engineerings :? :1
SELECT COUNT(*) AS total FROM biz_engineering
WHERE del_flag = '0'
  AND (:engineering_name IS NULL OR engineering_name LIKE '%' || :engineering_name || '%')
  AND (:engineering_code IS NULL OR engineering_code LIKE '%' || :engineering_code || '%')
  AND (:status IS NULL OR status = :status)
-- :name get-engineering :? :1
SELECT * FROM biz_engineering WHERE id = :id AND del_flag = '0'
-- :name create-engineering! :! :n
INSERT INTO biz_engineering (engineering_name, engineering_code, description, status, create_by, remark)
VALUES (:engineering_name, :engineering_code, :description, :status, :create_by, :remark)
-- :name update-engineering! :! :n
UPDATE biz_engineering SET engineering_name = :engineering_name, engineering_code = :engineering_code, description = :description, status = :status, update_by = :update_by, update_time = CURRENT_TIMESTAMP, remark = :remark WHERE id = :id AND del_flag = '0'
-- :name delete-engineering! :! :n
UPDATE biz_engineering SET del_flag = '2', update_time = CURRENT_TIMESTAMP WHERE id = :id

-- :name list-projects :? :*
SELECT * FROM biz_project
WHERE del_flag = '0'
  AND (:engineering_id IS NULL OR engineering_id = :engineering_id)
  AND (:project_name IS NULL OR project_name LIKE '%' || :project_name || '%')
  AND (:project_code IS NULL OR project_code LIKE '%' || :project_code || '%')
  AND (:construction_unit IS NULL OR construction_unit LIKE '%' || :construction_unit || '%')
ORDER BY create_time DESC
LIMIT :limit OFFSET :offset
-- :name count-projects :? :1
SELECT COUNT(*) AS total FROM biz_project
WHERE del_flag = '0'
  AND (:engineering_id IS NULL OR engineering_id = :engineering_id)
  AND (:project_name IS NULL OR project_name LIKE '%' || :project_name || '%')
  AND (:project_code IS NULL OR project_code LIKE '%' || :project_code || '%')
  AND (:construction_unit IS NULL OR construction_unit LIKE '%' || :construction_unit || '%')
-- :name get-project :? :1
SELECT * FROM biz_project WHERE id = :id AND del_flag = '0'
-- :name create-project! :! :n
INSERT INTO biz_project (engineering_id, engineering_name, project_name, project_code, project_address, construction_unit, contractor_unit, supervision_unit, design_unit, survey_unit, project_manager, project_leader, contact_phone, contract_period, start_date, end_date, building_area, project_cost, structure_type, building_floors, project_overview, extra_json, create_by, remark)
VALUES (:engineering_id, :engineering_name, :project_name, :project_code, :project_address, :construction_unit, :contractor_unit, :supervision_unit, :design_unit, :survey_unit, :project_manager, :project_leader, :contact_phone, :contract_period, :start_date, :end_date, :building_area, :project_cost, :structure_type, :building_floors, :project_overview, :extra_json, :create_by, :remark)
-- :name update-project! :! :n
UPDATE biz_project SET engineering_id = :engineering_id, engineering_name = :engineering_name, project_name = :project_name, project_code = :project_code, project_address = :project_address, construction_unit = :construction_unit, contractor_unit = :contractor_unit, supervision_unit = :supervision_unit, design_unit = :design_unit, survey_unit = :survey_unit, project_manager = :project_manager, project_leader = :project_leader, contact_phone = :contact_phone, contract_period = :contract_period, start_date = :start_date, end_date = :end_date, building_area = :building_area, project_cost = :project_cost, structure_type = :structure_type, building_floors = :building_floors, project_overview = :project_overview, extra_json = :extra_json, update_by = :update_by, update_time = CURRENT_TIMESTAMP, remark = :remark WHERE id = :id AND del_flag = '0'
-- :name delete-project! :! :n
UPDATE biz_project SET del_flag = '2', update_time = CURRENT_TIMESTAMP WHERE id = :id

-- :name list-subcontract-teams :? :*
SELECT * FROM biz_subcontract_team WHERE del_flag = '0' AND project_id = :project_id ORDER BY create_time DESC
-- :name create-subcontract-team! :! :n
INSERT INTO biz_subcontract_team (project_id, team_name, leader_name, contact_phone, work_scope, extra_json, create_by, remark)
VALUES (:project_id, :team_name, :leader_name, :contact_phone, :work_scope, :extra_json, :create_by, :remark)
-- :name update-subcontract-team! :! :n
UPDATE biz_subcontract_team SET team_name = :team_name, leader_name = :leader_name, contact_phone = :contact_phone, work_scope = :work_scope, extra_json = :extra_json, update_by = :update_by, update_time = CURRENT_TIMESTAMP, remark = :remark WHERE id = :id AND del_flag = '0'
-- :name delete-subcontract-team! :! :n
UPDATE biz_subcontract_team SET del_flag = '2', update_time = CURRENT_TIMESTAMP WHERE id = :id

-- :name list-solutions :? :*
SELECT * FROM biz_solution
WHERE del_flag = '0'
  AND (:solution_name IS NULL OR solution_name LIKE '%' || :solution_name || '%')
  AND (:engineering_id IS NULL OR engineering_id = :engineering_id)
  AND (:project_id IS NULL OR project_id = :project_id)
  AND (:status IS NULL OR status = :status)
ORDER BY update_time DESC
LIMIT :limit OFFSET :offset
-- :name count-solutions :? :1
SELECT COUNT(*) AS total FROM biz_solution
WHERE del_flag = '0'
  AND (:solution_name IS NULL OR solution_name LIKE '%' || :solution_name || '%')
  AND (:engineering_id IS NULL OR engineering_id = :engineering_id)
  AND (:project_id IS NULL OR project_id = :project_id)
  AND (:status IS NULL OR status = :status)
-- :name get-solution :? :1
SELECT * FROM biz_solution WHERE id = :id AND del_flag = '0'
-- :name create-solution! :! :n
INSERT INTO biz_solution (engineering_id, project_id, engineering_name, project_name, solution_name, status, progress, create_by, remark)
VALUES (:engineering_id, :project_id, :engineering_name, :project_name, :solution_name, :status, :progress, :create_by, :remark)
-- :name update-solution! :! :n
UPDATE biz_solution SET engineering_id = :engineering_id, project_id = :project_id, engineering_name = :engineering_name, project_name = :project_name, solution_name = :solution_name, status = :status, progress = :progress, update_by = :update_by, update_time = CURRENT_TIMESTAMP, remark = :remark WHERE id = :id AND del_flag = '0'
-- :name delete-solution! :! :n
UPDATE biz_solution SET del_flag = '2', update_time = CURRENT_TIMESTAMP WHERE id = :id

-- :name get-solution-section :? :1
SELECT * FROM biz_solution_section WHERE solution_id = :solution_id AND section_key = :section_key AND del_flag = '0'
-- :name upsert-solution-section! :! :n
INSERT INTO biz_solution_section (solution_id, section_key, section_title, content_json, sort_order, create_by, update_by, remark)
VALUES (:solution_id, :section_key, :section_title, :content_json, :sort_order, :create_by, :update_by, :remark)
ON CONFLICT(solution_id, section_key) DO UPDATE SET section_title = excluded.section_title, content_json = excluded.content_json, sort_order = excluded.sort_order, update_by = excluded.update_by, update_time = CURRENT_TIMESTAMP, remark = excluded.remark, del_flag = '0'

-- :name list-resources :? :*
SELECT * FROM biz_resource
WHERE del_flag = '0'
  AND resource_type = :resource_type
  AND (:name IS NULL OR name LIKE '%' || :name || '%')
  AND (:code IS NULL OR code LIKE '%' || :code || '%')
  AND (:category IS NULL OR category LIKE '%' || :category || '%')
  AND (:status IS NULL OR status = :status)
ORDER BY sort_order ASC, create_time DESC
LIMIT :limit OFFSET :offset
-- :name count-resources :? :1
SELECT COUNT(*) AS total FROM biz_resource
WHERE del_flag = '0'
  AND resource_type = :resource_type
  AND (:name IS NULL OR name LIKE '%' || :name || '%')
  AND (:code IS NULL OR code LIKE '%' || :code || '%')
  AND (:category IS NULL OR category LIKE '%' || :category || '%')
  AND (:status IS NULL OR status = :status)
-- :name get-resource :? :1
SELECT * FROM biz_resource WHERE id = :id AND resource_type = :resource_type AND del_flag = '0'
-- :name create-resource! :! :n
INSERT INTO biz_resource (resource_type, name, code, category, publish_unit, publish_date, effective_date, file_name, file_type, vector_status, tags, related_project_name, structured_fields, summary, content, status, sort_order, extra_json, create_by, remark)
VALUES (:resource_type, :name, :code, :category, :publish_unit, :publish_date, :effective_date, :file_name, :file_type, :vector_status, :tags, :related_project_name, :structured_fields, :summary, :content, :status, :sort_order, :extra_json, :create_by, :remark)
-- :name update-resource! :! :n
UPDATE biz_resource SET name = :name, code = :code, category = :category, publish_unit = :publish_unit, publish_date = :publish_date, effective_date = :effective_date, file_name = :file_name, file_type = :file_type, vector_status = :vector_status, tags = :tags, related_project_name = :related_project_name, structured_fields = :structured_fields, summary = :summary, content = :content, status = :status, sort_order = :sort_order, extra_json = :extra_json, update_by = :update_by, update_time = CURRENT_TIMESTAMP, remark = :remark WHERE id = :id AND resource_type = :resource_type AND del_flag = '0'
-- :name delete-resource! :! :n
UPDATE biz_resource SET del_flag = '2', update_time = CURRENT_TIMESTAMP WHERE id = :id AND resource_type = :resource_type

-- :name list-attachments :? :*
SELECT * FROM biz_attachment
WHERE del_flag = '0'
  AND (:biz_type IS NULL OR biz_type = :biz_type)
  AND (:biz_id IS NULL OR biz_id = :biz_id)
  AND (:section_key IS NULL OR section_key = :section_key)
  AND (:file_purpose IS NULL OR file_purpose = :file_purpose)
ORDER BY sort_order ASC, create_time DESC
-- :name get-attachment :? :1
SELECT * FROM biz_attachment WHERE id = :id AND del_flag = '0'
-- :name create-attachment! :! :n
INSERT INTO biz_attachment (biz_type, biz_id, section_key, file_purpose, original_name, stored_name, storage_type, storage_path, file_url, mime_type, extension, file_size, sort_order, create_by, remark)
VALUES (:biz_type, :biz_id, :section_key, :file_purpose, :original_name, :stored_name, :storage_type, :storage_path, :file_url, :mime_type, :extension, :file_size, :sort_order, :create_by, :remark)
-- :name delete-attachment! :! :n
UPDATE biz_attachment SET del_flag = '2', update_time = CURRENT_TIMESTAMP WHERE id = :id

-- :name list-gallery-items :? :*
SELECT gi.*, a.file_url, a.original_name, a.storage_path, a.mime_type
FROM biz_gallery_item gi
LEFT JOIN biz_attachment a ON a.id = gi.attachment_id AND a.del_flag = '0'
WHERE gi.del_flag = '0' AND gi.atlas_id = :atlas_id
ORDER BY gi.sort_order ASC, gi.create_time DESC
-- :name create-gallery-item! :! :n
INSERT INTO biz_gallery_item (atlas_id, attachment_id, image_title, image_desc, is_cover, sort_order, create_by, remark)
VALUES (:atlas_id, :attachment_id, :image_title, :image_desc, :is_cover, :sort_order, :create_by, :remark)
-- :name update-gallery-item! :! :n
UPDATE biz_gallery_item SET image_title = :image_title, image_desc = :image_desc, is_cover = :is_cover, sort_order = :sort_order, update_by = :update_by, update_time = CURRENT_TIMESTAMP, remark = :remark WHERE id = :id AND atlas_id = :atlas_id AND del_flag = '0'
-- :name clear-gallery-cover! :! :n
UPDATE biz_gallery_item SET is_cover = 'N', update_time = CURRENT_TIMESTAMP WHERE atlas_id = :atlas_id AND del_flag = '0'
-- :name delete-gallery-item! :! :n
UPDATE biz_gallery_item SET del_flag = '2', update_time = CURRENT_TIMESTAMP WHERE id = :id AND atlas_id = :atlas_id
