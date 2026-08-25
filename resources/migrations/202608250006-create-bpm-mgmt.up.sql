-- 办公一体化 · BPM 管理套件扩展表（MySQL）

-- 用户分组（审批人分组）
CREATE TABLE IF NOT EXISTS biz_bpm_user_group (
  group_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
  name        TEXT NOT NULL DEFAULT '',
  description TEXT NOT NULL DEFAULT '',
  user_ids    TEXT NOT NULL DEFAULT '',
  status      TEXT NOT NULL DEFAULT '0',
  create_by   TEXT NOT NULL DEFAULT '',
  create_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  update_by   TEXT NOT NULL DEFAULT '',
  update_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  remark      TEXT NOT NULL DEFAULT ''
);
--;;
CREATE INDEX idx_bpm_user_group_name ON biz_bpm_user_group(name);
--;;

-- 流程监听器（执行监听/任务监听）
CREATE TABLE IF NOT EXISTS biz_bpm_listener (
  listener_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name        TEXT NOT NULL DEFAULT '',
  type        TEXT NOT NULL DEFAULT '1',
  event       TEXT NOT NULL DEFAULT '',
  listener    TEXT NOT NULL DEFAULT '',
  status      TEXT NOT NULL DEFAULT '0',
  create_by   TEXT NOT NULL DEFAULT '',
  create_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  update_by   TEXT NOT NULL DEFAULT '',
  update_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  remark      TEXT NOT NULL DEFAULT ''
);
--;;
CREATE INDEX idx_bpm_listener_type ON biz_bpm_listener(type);
--;;

-- 流程表达式（可复用条件表达式）
CREATE TABLE IF NOT EXISTS biz_bpm_expression (
  expression_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name          TEXT NOT NULL DEFAULT '',
  format        TEXT NOT NULL DEFAULT '10',
  expression    TEXT NOT NULL DEFAULT '',
  status        TEXT NOT NULL DEFAULT '0',
  create_by     TEXT NOT NULL DEFAULT '',
  create_time   TEXT,
  update_by     TEXT NOT NULL DEFAULT '',
  update_time   TEXT,
  remark        TEXT NOT NULL DEFAULT ''
);
--;;
CREATE INDEX idx_bpm_expression_name ON biz_bpm_expression(name);
--;;

-- 流程设置（全局 BPM 配置项）
CREATE TABLE IF NOT EXISTS biz_bpm_settings (
  settings_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name        TEXT NOT NULL DEFAULT '',
  value       TEXT NOT NULL DEFAULT '',
  description TEXT NOT NULL DEFAULT '',
  status      TEXT NOT NULL DEFAULT '0',
  create_by   TEXT NOT NULL DEFAULT '',
  create_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  update_by   TEXT NOT NULL DEFAULT '',
  update_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  remark      TEXT NOT NULL DEFAULT ''
);
--;;
CREATE INDEX idx_bpm_settings_name ON biz_bpm_settings(name);
