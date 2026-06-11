CREATE TABLE sys_menu (
  menu_id INTEGER PRIMARY KEY,
  menu_name VARCHAR(50) NOT NULL,
  parent_id INTEGER DEFAULT 0,
  order_num INT DEFAULT 0,
  path VARCHAR(200) DEFAULT '',
  component VARCHAR(255) DEFAULT NULL,
  query VARCHAR(255) DEFAULT NULL,
  route_name VARCHAR(255) DEFAULT NULL,
  is_frame INT DEFAULT 1,
  is_cache INT DEFAULT 0,
  menu_type CHAR(1) DEFAULT '',
  visible CHAR(1) DEFAULT '0',
  status CHAR(1) DEFAULT '0',
  perms VARCHAR(100) DEFAULT NULL,
  icon VARCHAR(100) DEFAULT '#',
  create_by VARCHAR(64) DEFAULT '',
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TEXT DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT ''
);
--;;
CREATE INDEX idx_sys_menu_parent_id ON sys_menu(parent_id);
--;;
CREATE INDEX idx_sys_menu_menu_type ON sys_menu(menu_type);
--;;
CREATE INDEX idx_sys_menu_status ON sys_menu(status);
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
--;;
--;;
--;;
--;;
--;;
