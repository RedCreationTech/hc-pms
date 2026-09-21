-- 办公一体化 · 请假菜单(SQLite)
INSERT OR IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (40, '请假申请', 30, 4, 'oa/leave', 'business/oa/leave/index', 'C', '0', '0', 'oa:leave:list', 'form');
--;;
INSERT OR IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, visible, status, perms, icon)
VALUES (314, '请假发起', 40, 1, 'F', '0', '0', 'oa:leave:add', '#');
--;;
INSERT OR IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 40), (1, 314);
