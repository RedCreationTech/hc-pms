-- 办公一体化 · 报销菜单（SQLite）
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (41, '报销申请', 30, 5, 'oa/reimburse', 'business/oa/reimburse/index', 'C', '0', '0', 'oa:reimburse:list', 'money');
--;;
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, visible, status, perms, icon)
VALUES (315, '报销发起', 41, 1, 'F', '0', '0', 'oa:reimburse:add', '#');
--;;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 41), (1, 315);
