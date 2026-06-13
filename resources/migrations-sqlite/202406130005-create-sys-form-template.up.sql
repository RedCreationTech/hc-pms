-- 表单模板表
CREATE TABLE IF NOT EXISTS sys_form_template (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  form_name   TEXT    NOT NULL,
  form_key    TEXT    NOT NULL UNIQUE,
  schema_json TEXT    NOT NULL,
  remark      TEXT    DEFAULT '',
  create_by   TEXT,
  create_time TEXT,
  update_by   TEXT,
  update_time TEXT
);
--;;
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_form_template_key ON sys_form_template(form_key);
--;;
--;;
--;;
--;;
--;;
