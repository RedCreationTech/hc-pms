CREATE TABLE sys_online (
  session_id VARCHAR(50) PRIMARY KEY,
  login_name VARCHAR(50) DEFAULT '',
  dept_name VARCHAR(50) DEFAULT '',
  ipaddr VARCHAR(128) DEFAULT '',
  login_location VARCHAR(255) DEFAULT '',
  browser VARCHAR(50) DEFAULT '',
  os VARCHAR(50) DEFAULT '',
  status VARCHAR(10) DEFAULT 'on_line',
  start_timestamp INTEGER,
  last_access_time INTEGER,
  expire_time INT DEFAULT 1800000
);
--;;
