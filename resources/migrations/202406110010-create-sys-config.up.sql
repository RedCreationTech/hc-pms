CREATE TABLE sys_config (
  config_id BIGSERIAL PRIMARY KEY,
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

CREATE UNIQUE INDEX idx_sys_config_key ON sys_config(config_key);

COMMENT ON TABLE sys_config IS '参数配置表';
COMMENT ON COLUMN sys_config.config_id IS '参数ID';
COMMENT ON COLUMN sys_config.config_name IS '参数名称';
COMMENT ON COLUMN sys_config.config_key IS '参数键名';
COMMENT ON COLUMN sys_config.config_value IS '参数键值';
COMMENT ON COLUMN sys_config.config_type IS '系统内置（Y是 N否）';
