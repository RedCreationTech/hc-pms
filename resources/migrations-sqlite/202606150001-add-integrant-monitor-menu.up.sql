UPDATE sys_menu
SET order_num = 11,
    update_time = CURRENT_TIMESTAMP
WHERE menu_id = 20;
--;;
INSERT OR IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (21, 'Integrant 依赖', 2, 10, 'integrant', 'monitor/integrant/index', 'C', '0', '0', 'monitor:integrant:list', 'FunctionOutlined');
--;;
INSERT OR IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 21);
