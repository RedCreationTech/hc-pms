-- 初始化部门数据
INSERT INTO sys_dept (dept_id, parent_id, dept_name, order_num, status, del_flag) VALUES
(1, 0, '若依科技', 0, '0', '0'),
(2, 1, '深圳总公司', 1, '0', '0'),
(3, 1, '长沙分公司', 2, '0', '0'),
(4, 2, '研发部门', 1, '0', '0'),
(5, 2, '市场部门', 2, '0', '0'),
(6, 3, '财务部门', 1, '0', '0');
--;;
-- 初始化岗位数据
INSERT INTO sys_post (post_id, post_code, post_name, post_sort, status) VALUES
(1, 'ceo', '董事长', 1, '0'),
(2, 'se', '项目经理', 2, '0'),
(3, 'hr', '人力资源', 3, '0'),
(4, 'user', '普通员工', 4, '0');
--;;
-- 初始化角色数据
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, status, del_flag) VALUES
(1, '超级管理员', 'admin', 1, '1', '0', '0'),
(2, '普通角色', 'common', 2, '2', '0', '0');
--;;
-- 初始化菜单数据
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon) VALUES
(1, '系统管理', 0, 1, 'system', NULL, 'M', '0', '0', NULL, 'system'),
(2, '系统监控', 0, 2, 'monitor', NULL, 'M', '0', '0', NULL, 'monitor'),
(3, '用户管理', 1, 1, 'user', 'system/user/index', 'C', '0', '0', 'system:user:list', 'user'),
(4, '角色管理', 1, 2, 'role', 'system/role/index', 'C', '0', '0', 'system:role:list', 'peoples'),
(5, '菜单管理', 1, 3, 'menu', 'system/menu/index', 'C', '0', '0', 'system:menu:list', 'tree-table'),
(6, '部门管理', 1, 4, 'dept', 'system/dept/index', 'C', '0', '0', 'system:dept:list', 'tree'),
(7, '岗位管理', 1, 5, 'post', 'system/post/index', 'C', '0', '0', 'system:post:list', 'post'),
(8, '字典管理', 1, 6, 'dict', 'system/dict/index', 'C', '0', '0', 'system:dict:list', 'dict'),
(9, '参数管理', 1, 7, 'config', 'system/config/index', 'C', '0', '0', 'system:config:list', 'edit'),
(10, '通知公告', 1, 8, 'notice', 'system/notice/index', 'C', '0', '0', 'system:notice:list', 'message'),
(11, '操作日志', 2, 1, 'operlog', 'monitor/operlog/index', 'C', '0', '0', 'monitor:operlog:list', 'form'),
(12, '登录日志', 2, 2, 'logininfor', 'monitor/logininfor/index', 'C', '0', '0', 'monitor:logininfor:list', 'logininfor'),
(13, '在线用户', 2, 3, 'online', 'monitor/online/index', 'C', '0', '0', 'monitor:online:list', 'online'),
(14, '定时任务', 2, 4, 'job', 'monitor/job/index', 'C', '0', '0', 'monitor:job:list', 'job'),
(15, '系统接口', 2, 5, 'swagger', 'tool/swagger/index', 'C', '0', '0', 'tool:swagger:list', 'swagger'),
(17, '服务监控', 2, 7, 'server', 'monitor/server/index', 'C', '0', '0', 'monitor:server:list', 'server'),
(18, '缓存监控', 2, 8, 'cache', 'monitor/cache/index', 'C', '0', '0', 'monitor:cache:list', 'cache'),
(19, '数据监控', 2, 9, 'datasource', 'monitor/datasource/index', 'C', '0', '0', 'monitor:datasource:list', 'druid');
--;;
-- 用户查询
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, visible, status, perms, icon) VALUES
(100, '用户查询', 3, 1, 'F', '0', '0', 'system:user:query', '#'),
(101, '用户新增', 3, 2, 'F', '0', '0', 'system:user:add', '#'),
(102, '用户修改', 3, 3, 'F', '0', '0', 'system:user:edit', '#'),
(103, '用户删除', 3, 4, 'F', '0', '0', 'system:user:remove', '#'),
(104, '用户导出', 3, 5, 'F', '0', '0', 'system:user:export', '#'),
(105, '角色查询', 4, 1, 'F', '0', '0', 'system:role:query', '#'),
(106, '角色新增', 4, 2, 'F', '0', '0', 'system:role:add', '#'),
(107, '角色修改', 4, 3, 'F', '0', '0', 'system:role:edit', '#'),
(108, '角色删除', 4, 4, 'F', '0', '0', 'system:role:remove', '#'),
(109, '角色导出', 4, 5, 'F', '0', '0', 'system:role:export', '#'),
(110, '菜单查询', 5, 1, 'F', '0', '0', 'system:menu:query', '#'),
(111, '菜单新增', 5, 2, 'F', '0', '0', 'system:menu:add', '#'),
(112, '菜单修改', 5, 3, 'F', '0', '0', 'system:menu:edit', '#'),
(113, '菜单删除', 5, 4, 'F', '0', '0', 'system:menu:remove', '#');
--;;
-- 初始化超级管理员账号 (密码: admin123)
INSERT INTO sys_user (user_id, dept_id, user_name, nick_name, user_type, email, phonenumber, sex, password, status, del_flag) VALUES
(1, 4, 'admin', '若依管理员', '00', 'ry@163.com', '15888888888', '1', 'bcrypt+sha512$3a05c1c682b1773663a48da4097b498d$12$ba8763e71789612a4b13a2c1234c7c68ba73962c2da3522c', '0', '0');
--;;
-- 初始化用户角色关联
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);
--;;
-- 初始化角色菜单关联 (admin拥有所有菜单)
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9), (1, 10), (1, 11), (1, 12), (1, 13), (1, 14), (1, 15), (1, 16), (1, 17), (1, 18), (1, 19),
(1, 100), (1, 101), (1, 102), (1, 103), (1, 104), (1, 105), (1, 106), (1, 107), (1, 108), (1, 109), (1, 110), (1, 111), (1, 112), (1, 113);
--;;
-- 初始化字典类型
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status) VALUES
(1, '用户性别', 'sys_user_sex', '0'),
(2, '菜单状态', 'sys_show_hide', '0'),
(3, '系统是否', 'sys_yes_no', '0'),
(4, '任务状态', 'sys_job_status', '0'),
(5, '任务分组', 'sys_job_group', '0'),
(6, '通知类型', 'sys_notice_type', '0'),
(7, '通知状态', 'sys_notice_status', '0'),
(8, '操作类型', 'sys_oper_type', '0'),
(9, '系统状态', 'sys_common_status', '0');
--;;
-- 初始化字典数据
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, list_class, is_default, status) VALUES
(1, 1, '男', '0', 'sys_user_sex', '', 'Y', '0'),
(2, 2, '女', '1', 'sys_user_sex', '', 'N', '0'),
(3, 3, '未知', '2', 'sys_user_sex', '', 'N', '0'),
(4, 1, '显示', '0', 'sys_show_hide', 'primary', 'Y', '0'),
(5, 2, '隐藏', '1', 'sys_show_hide', 'danger', 'N', '0'),
(6, 1, '是', 'Y', 'sys_yes_no', 'primary', 'Y', '0'),
(7, 2, '否', 'N', 'sys_yes_no', 'danger', 'N', '0'),
(8, 1, '正常', '0', 'sys_common_status', 'primary', 'Y', '0'),
(9, 2, '停用', '1', 'sys_common_status', 'danger', 'N', '0'),
(10, 1, '新增', '1', 'sys_oper_type', 'info', 'N', '0'),
(11, 2, '修改', '2', 'sys_oper_type', 'info', 'N', '0'),
(12, 3, '删除', '3', 'sys_oper_type', 'danger', 'N', '0');
--;;
-- 初始化参数配置
INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type) VALUES
(1, '主框架页-默认皮肤样式名称', 'sys.index.skinName', 'skin-blue', 'Y'),
(2, '用户管理-账号初始密码', 'sys.user.initPassword', '123456', 'Y'),
(3, '主框架页-侧边栏主题', 'sys.index.sidebarTheme', 'theme-dark', 'Y');
