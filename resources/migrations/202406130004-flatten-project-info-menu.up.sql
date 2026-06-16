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
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 2100);
