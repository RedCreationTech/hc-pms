-- 办公一体化 · BPM 管理菜单(SQLite)--挂在 流程管理(31) 下
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (43, '流程表单', 31, 2, 'bpm/form', 'business/bpm/form/index', 'C', '0', '0', 'bpm:form:list', 'form'),
       (44, '流程分类', 31, 3, 'bpm/category', 'business/bpm/category/index', 'C', '0', '0', 'bpm:category:list', 'appstore'),
       (45, '用户分组', 31, 4, 'bpm/user-group', 'business/bpm/user-group/index', 'C', '0', '0', 'bpm:user-group:list', 'team'),
       (46, '流程监听器', 31, 5, 'bpm/listener', 'business/bpm/listener/index', 'C', '0', '0', 'bpm:listener:list', 'listen'),
       (47, '流程表达式', 31, 6, 'bpm/expression', 'business/bpm/expression/index', 'C', '0', '0', 'bpm:expression:list', 'code'),
       (48, '流程实例管理', 31, 7, 'bpm/instance-manager', 'business/bpm/instance-manager/index', 'C', '0', '0', 'bpm:instance:list', 'profile'),
       (49, '流程任务管理', 31, 8, 'bpm/task-manager', 'business/bpm/task-manager/index', 'C', '0', '0', 'bpm:task:list', 'audit'),
       (50, '流程实例运维', 31, 9, 'bpm/instance-ops', 'business/bpm/instance-ops/index', 'C', '0', '0', 'bpm:instance:ops', 'tool'),
       (51, '流程设置', 31, 10, 'bpm/settings', 'business/bpm/settings/index', 'C', '0', '0', 'bpm:settings:list', 'setting');
--;;
-- 按钮权限
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, visible, status, perms, icon)
VALUES (320, '表单新增', 43, 1, 'F', '0', '0', 'bpm:form:add', '#'),
       (321, '分组新增', 45, 1, 'F', '0', '0', 'bpm:user-group:add', '#'),
       (322, '监听器新增', 46, 1, 'F', '0', '0', 'bpm:listener:add', '#'),
       (323, '表达式新增', 47, 1, 'F', '0', '0', 'bpm:expression:add', '#'),
       (324, '设置新增', 51, 1, 'F', '0', '0', 'bpm:settings:add', '#'),
       (325, '运维挂起', 50, 1, 'F', '0', '0', 'bpm:instance:suspend', '#'),
       (326, '运维激活', 50, 2, 'F', '0', '0', 'bpm:instance:activate', '#'),
       (327, '运维终止', 50, 3, 'F', '0', '0', 'bpm:instance:delete', '#');
--;;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(1, 43),(1,44),(1,45),(1,46),(1,47),(1,48),(1,49),(1,50),(1,51),
(1,320),(1,321),(1,322),(1,323),(1,324),(1,325),(1,326),(1,327);
