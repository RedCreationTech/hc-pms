CREATE TABLE sys_config (
  config_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  config_name VARCHAR(100) DEFAULT '',
  config_key VARCHAR(100) NOT NULL,
  config_value VARCHAR(500) DEFAULT '',
  config_type CHAR(1) DEFAULT 'N',
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT ''
);
--;;
CREATE UNIQUE INDEX idx_sys_config_key ON sys_config(config_key);
--;;
