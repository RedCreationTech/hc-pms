-- 办公一体化 · BPM 业务表(MySQL)
-- biz_bpm_category 流程分类 / biz_bpm_model 流程模型 / biz_bpm_form 动态表单
-- biz_bpm_instance 流程实例映射 / biz_attachment 通用附件

-- 流程分类
CREATE TABLE IF NOT EXISTS biz_bpm_category (
  category_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name        VARCHAR(255) NOT NULL DEFAULT '',
  code        VARCHAR(255) NOT NULL DEFAULT '',
  sort        INT NOT NULL DEFAULT 0,
  status      CHAR(1) NOT NULL DEFAULT '0',
  create_by   VARCHAR(64) NOT NULL DEFAULT '',
  create_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  update_by   VARCHAR(64) NOT NULL DEFAULT '',
  update_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  remark      VARCHAR(500) NOT NULL DEFAULT ''
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_bpm_category_code ON biz_bpm_category(code);
--;;

-- 流程模型(存 BPMN XML + 表单配置,deployment_id 关联 Flowable)
CREATE TABLE IF NOT EXISTS biz_bpm_model (
  model_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  model_key     VARCHAR(255) NOT NULL,
  model_name    VARCHAR(255) NOT NULL DEFAULT '',
  category_id   BIGINT DEFAULT 0,
  version       INT NOT NULL DEFAULT 1,
  form_type     CHAR(1) NOT NULL DEFAULT '0',
  form_json     TEXT,
  bpmn_xml      LONGTEXT,
  deployment_id VARCHAR(64),
  status        CHAR(1) NOT NULL DEFAULT '1',
  create_by     VARCHAR(64) NOT NULL DEFAULT '',
  create_time   TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  update_by     VARCHAR(64) NOT NULL DEFAULT '',
  update_time   TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  remark        VARCHAR(500) NOT NULL DEFAULT ''
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_bpm_model_key ON biz_bpm_model(model_key);
--;;
CREATE INDEX idx_bpm_model_cat ON biz_bpm_model(category_id);
--;;

-- 动态表单定义
CREATE TABLE IF NOT EXISTS biz_bpm_form (
  form_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
  form_name   VARCHAR(255) NOT NULL DEFAULT '',
  form_key    VARCHAR(255) NOT NULL DEFAULT '',
  form_json   TEXT,
  status      CHAR(1) NOT NULL DEFAULT '0',
  create_by   VARCHAR(64) NOT NULL DEFAULT '',
  create_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  update_by   VARCHAR(64) NOT NULL DEFAULT '',
  update_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  remark      VARCHAR(500) NOT NULL DEFAULT ''
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_bpm_form_key ON biz_bpm_form(form_key);
--;;

-- 流程实例业务映射(关联 Flowable process_instance_id 与业务记录)
CREATE TABLE IF NOT EXISTS biz_bpm_instance (
  instance_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  process_instance_id VARCHAR(64) NOT NULL,
  model_id            BIGINT DEFAULT 0,
  model_key           VARCHAR(255) NOT NULL DEFAULT '',
  business_key        VARCHAR(255) NOT NULL DEFAULT '',
  form_data_json      TEXT,
  starter_id          VARCHAR(64) NOT NULL DEFAULT '',
  status              CHAR(1) NOT NULL DEFAULT '1',
  current_task        VARCHAR(255) NOT NULL DEFAULT '',
  create_time         TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  update_time         TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_bpm_instance_pid ON biz_bpm_instance(process_instance_id);
--;;
CREATE INDEX idx_bpm_instance_model ON biz_bpm_instance(model_id);
--;;

-- 通用附件(各模块复用,对接文件管理)
CREATE TABLE IF NOT EXISTS biz_attachment (
  attachment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  file_name     VARCHAR(255) NOT NULL DEFAULT '',
  file_path     VARCHAR(500) NOT NULL DEFAULT '',
  file_size     BIGINT DEFAULT 0,
  file_type     VARCHAR(255) NOT NULL DEFAULT '',
  biz_type      VARCHAR(64) NOT NULL DEFAULT '',
  biz_id        VARCHAR(64) NOT NULL DEFAULT '',
  upload_by     VARCHAR(64) NOT NULL DEFAULT '',
  create_time   TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_biz_attachment_biz ON biz_attachment(biz_type, biz_id);
