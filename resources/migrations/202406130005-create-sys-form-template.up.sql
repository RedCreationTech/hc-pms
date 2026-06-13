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
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_form_template_key ON sys_form_template(form_key);
--;;
