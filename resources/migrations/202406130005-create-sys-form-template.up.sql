-- 表单模板表
CREATE TABLE IF NOT EXISTS sys_form_template (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  form_name   VARCHAR(200) NOT NULL,
  form_key    VARCHAR(200) NOT NULL UNIQUE,
  schema_json LONGTEXT     NOT NULL,
  remark      VARCHAR(500) DEFAULT '',
  create_by   VARCHAR(64),
  create_time TIMESTAMP,
  update_by   VARCHAR(64),
  update_time TIMESTAMP
);
--;;
-- Index already created by UNIQUE constraint on form_key
--;;
