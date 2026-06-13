CREATE TABLE sys_role (
  role_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_name VARCHAR(30) NOT NULL,
  role_key VARCHAR(100) NOT NULL,
  role_sort INT DEFAULT 0,
  data_scope CHAR(1) DEFAULT '1',
  menu_check_strictly TINYINT(1) DEFAULT 1,
  dept_check_strictly TINYINT(1) DEFAULT 1,
  status CHAR(1) DEFAULT '0',
  del_flag CHAR(1) DEFAULT '0',
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT ''
);
--;;
CREATE INDEX idx_sys_role_role_key ON sys_role(role_key);
--;;
