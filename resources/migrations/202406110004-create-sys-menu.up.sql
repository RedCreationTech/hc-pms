CREATE TABLE sys_menu (
  menu_id BIGSERIAL PRIMARY KEY,
  menu_name VARCHAR(50) NOT NULL,
  parent_id BIGINT DEFAULT 0,
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
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT ''
);
--;;
CREATE INDEX idx_sys_menu_parent_id ON sys_menu(parent_id);
--;;
CREATE INDEX idx_sys_menu_menu_type ON sys_menu(menu_type);
--;;
CREATE INDEX idx_sys_menu_status ON sys_menu(status);
--;;
COMMENT ON TABLE sys_menu IS '菜单权限表';
--;;
COMMENT ON COLUMN sys_menu.menu_id IS '菜单ID';
--;;
COMMENT ON COLUMN sys_menu.menu_name IS '菜单名称';
--;;
COMMENT ON COLUMN sys_menu.parent_id IS '父菜单ID';
--;;
COMMENT ON COLUMN sys_menu.order_num IS '显示顺序';
--;;
COMMENT ON COLUMN sys_menu.path IS '路由地址';
--;;
COMMENT ON COLUMN sys_menu.component IS '组件路径';
--;;
COMMENT ON COLUMN sys_menu.query IS '路由参数';
--;;
COMMENT ON COLUMN sys_menu.is_frame IS '是否为外链（0是 1否）';
--;;
COMMENT ON COLUMN sys_menu.is_cache IS '是否缓存（0缓存 1不缓存）';
--;;
COMMENT ON COLUMN sys_menu.menu_type IS '菜单类型（M目录 C菜单 F按钮）';
--;;
COMMENT ON COLUMN sys_menu.visible IS '菜单状态（0显示 1隐藏）';
--;;
COMMENT ON COLUMN sys_menu.status IS '菜单状态（0正常 1停用）';
--;;
COMMENT ON COLUMN sys_menu.perms IS '权限标识';
--;;
COMMENT ON COLUMN sys_menu.icon IS '菜单图标';
