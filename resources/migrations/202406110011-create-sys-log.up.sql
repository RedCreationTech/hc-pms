CREATE TABLE sys_oper_log (
  oper_id BIGSERIAL PRIMARY KEY,
  title VARCHAR(50) DEFAULT '',
  business_type INT DEFAULT 0,
  method VARCHAR(500) DEFAULT '',
  request_method VARCHAR(10) DEFAULT '',
  operator_type INT DEFAULT 0,
  oper_name VARCHAR(50) DEFAULT '',
  dept_name VARCHAR(50) DEFAULT '',
  oper_url VARCHAR(500) DEFAULT '',
  oper_ip VARCHAR(128) DEFAULT '',
  oper_location VARCHAR(255) DEFAULT '',
  oper_param TEXT,
  json_result TEXT,
  status INT DEFAULT 0,
  error_msg VARCHAR(4000) DEFAULT '',
  oper_time TIMESTAMP,
  cost_time BIGINT DEFAULT 0
);
--;;
CREATE INDEX idx_sys_oper_log_oper_time ON sys_oper_log(oper_time);
--;;
CREATE INDEX idx_sys_oper_log_oper_name ON sys_oper_log(oper_name);
--;;
COMMENT ON TABLE sys_oper_log IS '操作日志记录';
--;;
CREATE TABLE sys_login_log (
  info_id BIGSERIAL PRIMARY KEY,
  user_name VARCHAR(50) DEFAULT '',
  ipaddr VARCHAR(128) DEFAULT '',
  login_location VARCHAR(255) DEFAULT '',
  browser VARCHAR(50) DEFAULT '',
  os VARCHAR(50) DEFAULT '',
  status CHAR(1) DEFAULT '0',
  msg VARCHAR(255) DEFAULT '',
  login_time TIMESTAMP
);
--;;
CREATE INDEX idx_sys_login_log_login_time ON sys_login_log(login_time);
--;;
CREATE INDEX idx_sys_login_log_user_name ON sys_login_log(user_name);
--;;
COMMENT ON TABLE sys_login_log IS '系统访问记录';
