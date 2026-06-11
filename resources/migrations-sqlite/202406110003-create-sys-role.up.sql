CREATE TABLE sys_role (
  role_id INTEGER PRIMARY KEY,
  role_name VARCHAR(30) NOT NULL,
  role_key VARCHAR(100) NOT NULL,
  role_sort INT DEFAULT 0,
  data_scope CHAR(1) DEFAULT '1',
  menu_check_strictly BOOLEAN DEFAULT true,
  dept_check_strictly BOOLEAN DEFAULT true,
  status CHAR(1) DEFAULT '0',
  del_flag CHAR(1) DEFAULT '0',
  create_by VARCHAR(64) DEFAULT '',
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TEXT DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT ''
);
--;;
CREATE INDEX idx_sys_role_role_key ON sys_role(role_key);
--;;
--;;
--;;
--;;
--;;
--;;
--;;
--;;
--;;
--;;
