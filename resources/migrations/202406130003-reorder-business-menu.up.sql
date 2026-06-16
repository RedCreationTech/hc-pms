INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (1999, '方案智能编制系统', 0, 0, '', NULL, 'M', '0', '0', '', 'dashboard');
--;;
UPDATE sys_menu
SET menu_name = '方案管理',
    parent_id = 1999,
    order_num = 1,
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
SET parent_id = 1999,
    order_num = 2,
    path = 'project/info',
    component = 'business/project/index',
    menu_type = 'C',
    visible = '0',
    status = '0',
    perms = 'business:project:list',
    icon = 'project',
    update_time = CURRENT_TIMESTAMP
WHERE menu_id = 2100;
--;;
DELETE FROM sys_role_menu WHERE menu_id = 2101;
--;;
DELETE FROM sys_menu WHERE menu_id = 2101;
--;;
UPDATE sys_menu
SET parent_id = 1999,
    order_num = 3,
    path = 'resource',
    menu_type = 'M',
    visible = '0',
    status = '0',
    icon = 'resource',
    update_time = CURRENT_TIMESTAMP
WHERE menu_id = 2200;
--;;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 1999);
