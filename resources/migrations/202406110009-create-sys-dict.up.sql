CREATE TABLE sys_dict_type (
  dict_id BIGSERIAL PRIMARY KEY,
  dict_name VARCHAR(100) DEFAULT '',
  dict_type VARCHAR(100) NOT NULL,
  status CHAR(1) DEFAULT '0',
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT ''
);
--;;
CREATE UNIQUE INDEX idx_sys_dict_type ON sys_dict_type(dict_type);
--;;
CREATE TABLE sys_dict_data (
  dict_code BIGSERIAL PRIMARY KEY,
  dict_sort INT DEFAULT 0,
  dict_label VARCHAR(100) DEFAULT '',
  dict_value VARCHAR(100) DEFAULT '',
  dict_type VARCHAR(100) NOT NULL,
  css_class VARCHAR(100) DEFAULT NULL,
  list_class VARCHAR(100) DEFAULT NULL,
  is_default CHAR(1) DEFAULT 'N',
  status CHAR(1) DEFAULT '0',
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT ''
);
--;;
CREATE INDEX idx_sys_dict_data_type ON sys_dict_data(dict_type);
--;;
CREATE INDEX idx_sys_dict_data_status ON sys_dict_data(status);
--;;
COMMENT ON TABLE sys_dict_type IS '字典类型表';
--;;
COMMENT ON TABLE sys_dict_data IS '字典数据表';
