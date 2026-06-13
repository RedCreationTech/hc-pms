DELETE FROM sys_role_menu WHERE menu_id = 1999;
--;;
UPDATE sys_menu
SET menu_name = '方案智能编制系统',
    parent_id = 0,
    order_num = 0,
    path = 'solution',
    component = 'business/solution/index',
    menu_type = 'C',
    visible = '0',
    status = '0',
    perms = 'business:solution:list',
    icon = 'dashboard',
    update_time = CURRENT_TIMESTAMP
WHERE menu_id = 2000;
--;;
UPDATE sys_menu
SET parent_id = 0,
    order_num = 1,
    path = 'project',
    component = NULL,
    menu_type = 'M',
    visible = '0',
    status = '0',
    perms = '',
    icon = 'project',
    update_time = CURRENT_TIMESTAMP
WHERE menu_id = 2100;
--;;
INSERT OR IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (2101, '项目信息管理', 2100, 1, 'info', 'business/project/index', 'C', '0', '0', 'business:project:list', 'project');
--;;
INSERT OR IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 2101);
--;;
UPDATE sys_menu
SET parent_id = 0,
    order_num = 2,
    path = 'resource',
    menu_type = 'M',
    visible = '0',
    status = '0',
    icon = 'resource',
    update_time = CURRENT_TIMESTAMP
WHERE menu_id = 2200;
--;;
DELETE FROM sys_menu WHERE menu_id = 1999;
