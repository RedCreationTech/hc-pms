-- :name list-jobs :? :*
SELECT * FROM sys_job WHERE 1=1
  AND (:job_name IS NULL OR job_name LIKE '%' || :job_name || '%')
  AND (:job_group IS NULL OR job_group = :job_group)
  AND (:status IS NULL OR status = :status)
ORDER BY job_id

-- :name find-job-by-id :? :1
SELECT * FROM sys_job WHERE job_id = :job_id

-- :name create-job! :! :n
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
VALUES (:job_name, :job_group, :invoke_target, :cron_expression, :misfire_policy, :concurrent, :status, :create_by, CURRENT_TIMESTAMP, :remark)

-- :name update-job! :! :n
UPDATE sys_job
SET job_name = COALESCE(:job_name, job_name),
    job_group = COALESCE(:job_group, job_group),
    invoke_target = COALESCE(:invoke_target, invoke_target),
    cron_expression = COALESCE(:cron_expression, cron_expression),
    misfire_policy = COALESCE(:misfire_policy, misfire_policy),
    concurrent = COALESCE(:concurrent, concurrent),
    status = COALESCE(:status, status),
    update_by = :update_by,
    update_time = CURRENT_TIMESTAMP,
    remark = COALESCE(:remark, remark)
WHERE job_id = :job_id

-- :name delete-job! :! :n
DELETE FROM sys_job WHERE job_id = :job_id

-- :name list-job-logs :? :*
SELECT * FROM sys_job_log WHERE 1=1
  AND (:job_name IS NULL OR job_name LIKE '%' || :job_name || '%')
  AND (:job_group IS NULL OR job_group = :job_group)
  AND (:status IS NULL OR status = :status)
ORDER BY job_log_id DESC
LIMIT :page_size OFFSET :offset

-- :name count-job-logs :? :1
SELECT COUNT(*) AS total FROM sys_job_log WHERE 1=1
  AND (:job_name IS NULL OR job_name LIKE '%' || :job_name || '%')
  AND (:job_group IS NULL OR job_group = :job_group)
  AND (:status IS NULL OR status = :status)

-- :name create-job-log! :! :n
INSERT INTO sys_job_log (job_name, job_group, invoke_target, job_message, status, exception_info, create_time)
VALUES (:job_name, :job_group, :invoke_target, :job_message, :status, :exception_info, CURRENT_TIMESTAMP)

-- :name clear-job-logs! :! :n
DELETE FROM sys_job_log WHERE 1=1
  AND (:job_name IS NULL OR job_name = :job_name)
  AND (:job_group IS NULL OR job_group = :job_group)
