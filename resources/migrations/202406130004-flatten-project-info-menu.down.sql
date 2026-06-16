UPDATE sys_menu
SET parent_id = 1999,
    order_num = 2,
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
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (2101, '项目信息管理', 2100, 1, 'info', 'business/project/index', 'C', '0', '0', 'business:project:list', 'project');
--;;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 2101);
