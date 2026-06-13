CREATE TABLE sys_user (
  user_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  dept_id BIGINT,
  user_name VARCHAR(30) NOT NULL,
  nick_name VARCHAR(30) NOT NULL,
  user_type VARCHAR(2) DEFAULT '00',
  email VARCHAR(50) DEFAULT '',
  phonenumber VARCHAR(11) DEFAULT '',
  sex CHAR(1) DEFAULT '0',
  avatar VARCHAR(200) DEFAULT '',
  password VARCHAR(100) DEFAULT '',
  status CHAR(1) DEFAULT '0',
  del_flag CHAR(1) DEFAULT '0',
  login_ip VARCHAR(128) DEFAULT '',
  login_date TIMESTAMP,
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT ''
);
--;;
CREATE INDEX idx_sys_user_dept_id ON sys_user(dept_id);
--;;
CREATE INDEX idx_sys_user_user_name ON sys_user(user_name);
--;;
CREATE INDEX idx_sys_user_status ON sys_user(status);
--;;
