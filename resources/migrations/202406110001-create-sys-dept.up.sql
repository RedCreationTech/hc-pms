CREATE TABLE sys_dept (
  dept_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  parent_id BIGINT DEFAULT 0,
  ancestors VARCHAR(255) DEFAULT '',
  dept_name VARCHAR(50) NOT NULL,
  order_num INT DEFAULT 0,
  leader VARCHAR(20),
  phone VARCHAR(11),
  email VARCHAR(50),
  status CHAR(1) DEFAULT '0',
  del_flag CHAR(1) DEFAULT '0',
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX idx_sys_dept_parent_id ON sys_dept(parent_id);
--;;
CREATE INDEX idx_sys_dept_ancestors ON sys_dept(ancestors);
--;;
