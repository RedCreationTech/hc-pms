CREATE TABLE sys_role (
  role_id BIGSERIAL PRIMARY KEY,
  role_name VARCHAR(30) NOT NULL,
  role_key VARCHAR(100) NOT NULL,
  role_sort INT DEFAULT 0,
  data_scope CHAR(1) DEFAULT '1',
  menu_check_strictly BOOLEAN DEFAULT true,
  dept_check_strictly BOOLEAN DEFAULT true,
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
COMMENT ON TABLE sys_role IS '角色信息表';
--;;
COMMENT ON COLUMN sys_role.role_id IS '角色ID';
--;;
COMMENT ON COLUMN sys_role.role_name IS '角色名称';
--;;
COMMENT ON COLUMN sys_role.role_key IS '角色权限字符串';
--;;
COMMENT ON COLUMN sys_role.role_sort IS '显示顺序';
--;;
COMMENT ON COLUMN sys_role.data_scope IS '数据范围（1全部数据权限 2自定数据权限 3本部门数据权限 4本部门及以下数据权限）';
--;;
COMMENT ON COLUMN sys_role.menu_check_strictly IS '菜单树选择项是否关联显示';
--;;
COMMENT ON COLUMN sys_role.dept_check_strictly IS '部门树选择项是否关联显示';
--;;
COMMENT ON COLUMN sys_role.status IS '角色状态（0正常 1停用）';
--;;
COMMENT ON COLUMN sys_role.del_flag IS '删除标志（0代表存在 2代表删除）';
