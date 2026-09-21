-- 办公一体化 · BPM 业务表(SQLite)
-- biz_bpm_category 流程分类 / biz_bpm_model 流程模型 / biz_bpm_form 动态表单
-- biz_bpm_instance 流程实例映射 / biz_attachment 通用附件

-- 流程分类
CREATE TABLE IF NOT EXISTS biz_bpm_category (
  category_id INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT NOT NULL DEFAULT '',
  code        TEXT NOT NULL DEFAULT '',
  sort        INTEGER NOT NULL DEFAULT 0,
  status      TEXT NOT NULL DEFAULT '0',
  create_by   TEXT NOT NULL DEFAULT '',
  create_time TEXT,
  update_by   TEXT NOT NULL DEFAULT '',
  update_time TEXT,
  remark      TEXT NOT NULL DEFAULT ''
);
--;;
CREATE INDEX IF NOT EXISTS idx_bpm_category_code ON biz_bpm_category(code);
--;;

-- 流程模型(存 BPMN XML + 表单配置,deployment_id 关联 Flowable)
CREATE TABLE IF NOT EXISTS biz_bpm_model (
  model_id      INTEGER PRIMARY KEY AUTOINCREMENT,
  model_key     TEXT NOT NULL,
  model_name    TEXT NOT NULL DEFAULT '',
  category_id   INTEGER DEFAULT 0,
  version       INTEGER NOT NULL DEFAULT 1,
  form_type     TEXT NOT NULL DEFAULT '0',
  form_json     TEXT,
  bpmn_xml      TEXT,
  deployment_id TEXT,
  status        TEXT NOT NULL DEFAULT '1',
  create_by     TEXT NOT NULL DEFAULT '',
  create_time   TEXT,
  update_by     TEXT NOT NULL DEFAULT '',
  update_time   TEXT,
  remark        TEXT NOT NULL DEFAULT ''
);
--;;
CREATE INDEX IF NOT EXISTS idx_bpm_model_key ON biz_bpm_model(model_key);
--;;
CREATE INDEX IF NOT EXISTS idx_bpm_model_cat ON biz_bpm_model(category_id);
--;;

-- 动态表单定义
CREATE TABLE IF NOT EXISTS biz_bpm_form (
  form_id     INTEGER PRIMARY KEY AUTOINCREMENT,
  form_name   TEXT NOT NULL DEFAULT '',
  form_key    TEXT NOT NULL DEFAULT '',
  form_json   TEXT,
  status      TEXT NOT NULL DEFAULT '0',
  create_by   TEXT NOT NULL DEFAULT '',
  create_time TEXT,
  update_by   TEXT NOT NULL DEFAULT '',
  update_time TEXT,
  remark      TEXT NOT NULL DEFAULT ''
);
--;;
CREATE INDEX IF NOT EXISTS idx_bpm_form_key ON biz_bpm_form(form_key);
--;;

-- 流程实例业务映射(关联 Flowable process_instance_id 与业务记录)
CREATE TABLE IF NOT EXISTS biz_bpm_instance (
  instance_id         INTEGER PRIMARY KEY AUTOINCREMENT,
  process_instance_id TEXT NOT NULL,
  model_id            INTEGER DEFAULT 0,
  model_key           TEXT NOT NULL DEFAULT '',
  business_key        TEXT NOT NULL DEFAULT '',
  form_data_json      TEXT,
  starter_id          TEXT NOT NULL DEFAULT '',
  status              TEXT NOT NULL DEFAULT '1',
  current_task        TEXT NOT NULL DEFAULT '',
  create_time         TEXT,
  update_time         TEXT
);
--;;
CREATE INDEX IF NOT EXISTS idx_bpm_instance_pid ON biz_bpm_instance(process_instance_id);
--;;
CREATE INDEX IF NOT EXISTS idx_bpm_instance_model ON biz_bpm_instance(model_id);
--;;

-- 通用附件(各模块复用,对接文件管理)
CREATE TABLE IF NOT EXISTS biz_attachment (
  attachment_id INTEGER PRIMARY KEY AUTOINCREMENT,
  file_name     TEXT NOT NULL DEFAULT '',
  file_path     TEXT NOT NULL DEFAULT '',
  file_size     INTEGER DEFAULT 0,
  file_type     TEXT NOT NULL DEFAULT '',
  biz_type      TEXT NOT NULL DEFAULT '',
  biz_id        TEXT NOT NULL DEFAULT '',
  upload_by     TEXT NOT NULL DEFAULT '',
  create_time   TEXT
);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_attachment_biz ON biz_attachment(biz_type, biz_id);
