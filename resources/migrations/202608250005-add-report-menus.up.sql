-- 办公一体化 · 报表菜单(SQLite)
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (42, '办公报表', 30, 8, 'report', 'business/report/index', 'C', '0', '0', 'report:office:list', 'pie-chart');
--;;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 42);
