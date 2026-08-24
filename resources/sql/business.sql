-- 办公一体化 · BPM 业务查询（SQLite/MySQL 双兼容）

-- ============================ 流程分类 ============================
-- :name bpm/category-list :? :*
SELECT category_id, name, code, sort, status, create_time, remark
FROM biz_bpm_category
WHERE (:name IS NULL OR INSTR(name, :name) > 0)
ORDER BY sort, category_id
LIMIT :page_size OFFSET :offset
--;;

-- :name bpm/category-count :? :1
SELECT COUNT(*) AS total FROM biz_bpm_category
WHERE (:name IS NULL OR INSTR(name, :name) > 0)
--;;

-- :name bpm/find-category-by-id :? :1
SELECT category_id, name, code, sort, status, create_time, update_time, remark
FROM biz_bpm_category WHERE category_id = :category_id
--;;

-- :name bpm/insert-category :! :n
INSERT INTO biz_bpm_category (name, code, sort, status, create_by, create_time, remark)
VALUES (:name, :code, :sort, :status, :create_by, datetime('now'), :remark)
--;;

-- :name bpm/update-category :! :n
UPDATE biz_bpm_category
SET name = :name, code = :code, sort = :sort, status = :status,
    update_by = :update_by, update_time = datetime('now'), remark = :remark
WHERE category_id = :category_id
--;;

-- :name bpm/delete-category :! :n
DELETE FROM biz_bpm_category WHERE category_id = :category_id
--;;

-- ============================ 流程模型 ============================
-- :name bpm/model-list :? :*
SELECT m.model_id, m.model_key, m.model_name, m.category_id, m.version,
       m.form_type, m.status, m.create_by, m.create_time, m.remark,
       c.name AS category_name
FROM biz_bpm_model m
LEFT JOIN biz_bpm_category c ON m.category_id = c.category_id
WHERE (:model_name IS NULL OR INSTR(m.model_name, :model_name) > 0)
  AND (:category_id IS NULL OR m.category_id = :category_id)
ORDER BY m.model_id DESC
LIMIT :page_size OFFSET :offset
--;;

-- :name bpm/model-count :? :1
SELECT COUNT(*) AS total FROM biz_bpm_model m
WHERE (:model_name IS NULL OR INSTR(m.model_name, :model_name) > 0)
  AND (:category_id IS NULL OR m.category_id = :category_id)
--;;

-- :name bpm/find-model-by-id :? :1
SELECT * FROM biz_bpm_model WHERE model_id = :model_id
--;;

-- :name bpm/find-model-by-key :? :1
SELECT * FROM biz_bpm_model WHERE model_key = :model_key ORDER BY version DESC LIMIT 1
--;;

-- :name bpm/insert-model :! :n
INSERT INTO biz_bpm_model (model_key, model_name, category_id, version, form_type,
                           form_json, bpmn_xml, deployment_id, status,
                           create_by, create_time, remark)
VALUES (:model_key, :model_name, :category_id, :version, :form_type,
        :form_json, :bpmn_xml, :deployment_id, :status,
        :create_by, datetime('now'), :remark)
--;;

-- :name bpm/update-model :! :n
UPDATE biz_bpm_model
SET model_name = :model_name, category_id = :category_id, form_type = :form_type,
    form_json = :form_json, bpmn_xml = :bpmn_xml, deployment_id = :deployment_id,
    status = :status, update_by = :update_by, update_time = datetime('now'),
    remark = :remark
WHERE model_id = :model_id
--;;

-- :name bpm/update-model-deployment :! :n
UPDATE biz_bpm_model
SET deployment_id = :deployment_id, version = :version, status = :status,
    update_time = datetime('now')
WHERE model_id = :model_id
--;;

-- :name bpm/delete-model :! :n
DELETE FROM biz_bpm_model WHERE model_id = :model_id
--;;

-- ============================ 动态表单 ============================
-- :name bpm/form-list :? :*
SELECT form_id, form_name, form_key, status, create_time, remark
FROM biz_bpm_form
WHERE (:form_name IS NULL OR INSTR(form_name, :form_name) > 0)
ORDER BY form_id DESC
LIMIT :page_size OFFSET :offset
--;;

-- :name bpm/form-count :? :1
SELECT COUNT(*) AS total FROM biz_bpm_form
WHERE (:form_name IS NULL OR INSTR(form_name, :form_name) > 0)
--;;

-- :name bpm/find-form-by-id :? :1
SELECT * FROM biz_bpm_form WHERE form_id = :form_id
--;;

-- :name bpm/insert-form :! :n
INSERT INTO biz_bpm_form (form_name, form_key, form_json, status, create_by, create_time, remark)
VALUES (:form_name, :form_key, :form_json, :status, :create_by, datetime('now'), :remark)
--;;

-- :name bpm/update-form :! :n
UPDATE biz_bpm_form
SET form_name = :form_name, form_key = :form_key, form_json = :form_json,
    status = :status, update_by = :update_by, update_time = datetime('now'), remark = :remark
WHERE form_id = :form_id
--;;

-- :name bpm/delete-form :! :n
DELETE FROM biz_bpm_form WHERE form_id = :form_id
--;;

-- ============================ 流程实例映射 ============================
-- :name bpm/instance-list :? :*
SELECT i.instance_id, i.process_instance_id, i.model_id, i.model_key,
       i.business_key, i.form_data_json, i.starter_id, i.status, i.current_task,
       i.create_time, m.model_name
FROM biz_bpm_instance i
LEFT JOIN biz_bpm_model m ON i.model_id = m.model_id
WHERE (:starter_id IS NULL OR i.starter_id = :starter_id)
  AND (:model_key IS NULL OR i.model_key = :model_key)
ORDER BY i.instance_id DESC
LIMIT :page_size OFFSET :offset
--;;

-- :name bpm/instance-count :? :1
SELECT COUNT(*) AS total FROM biz_bpm_instance i
WHERE (:starter_id IS NULL OR i.starter_id = :starter_id)
  AND (:model_key IS NULL OR i.model_key = :model_key)
--;;

-- :name bpm/find-instance-by-id :? :1
SELECT * FROM biz_bpm_instance WHERE instance_id = :instance_id
--;;

-- :name bpm/find-instance-by-pid :? :1
SELECT * FROM biz_bpm_instance WHERE process_instance_id = :process_instance_id
--;;

-- :name bpm/insert-instance :! :n
INSERT INTO biz_bpm_instance (process_instance_id, model_id, model_key, business_key,
                              form_data_json, starter_id, status, current_task, create_time)
VALUES (:process_instance_id, :model_id, :model_key, :business_key,
        :form_data_json, :starter_id, :status, :current_task, datetime('now'))
--;;

-- :name bpm/update-instance-status :! :n
UPDATE biz_bpm_instance
SET status = :status, current_task = :current_task, update_time = datetime('now')
WHERE process_instance_id = :process_instance_id
--;;

-- :name bpm/delete-instance :! :n
DELETE FROM biz_bpm_instance WHERE instance_id = :instance_id
--;;

-- ============================ 通用附件 ============================
-- :name bpm/attachment-list :? :*
SELECT attachment_id, file_name, file_path, file_size, file_type, biz_type, biz_id, upload_by, create_time
FROM biz_attachment
WHERE (:biz_type IS NULL OR biz_type = :biz_type)
  AND (:biz_id IS NULL OR biz_id = :biz_id)
ORDER BY attachment_id DESC
--;;

-- :name bpm/insert-attachment :! :n
INSERT INTO biz_attachment (file_name, file_path, file_size, file_type, biz_type, biz_id, upload_by, create_time)
VALUES (:file_name, :file_path, :file_size, :file_type, :biz_type, :biz_id, :upload_by, datetime('now'))
--;;

-- :name bpm/delete-attachment :! :n
DELETE FROM biz_attachment WHERE attachment_id = :attachment_id
--;;

-- :name bpm/delete-attachments-by-biz :! :n
DELETE FROM biz_attachment WHERE biz_type = :biz_type AND biz_id = :biz_id
--;;
