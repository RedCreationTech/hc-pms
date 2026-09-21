-- 办公一体化 · HRM 菜单(SQLite)
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (36, '员工管理', 30, 4, 'hrm/employee', 'business/hrm/employee/index', 'C', '0', '0', 'hrm:employee:list', 'team');
--;;
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, visible, status, perms, icon)
VALUES (306, '员工新增', 36, 1, 'F', '0', '0', 'hrm:employee:add', '#'),
       (307, '员工修改', 36, 2, 'F', '0', '0', 'hrm:employee:edit', '#'),
       (308, '员工删除', 36, 3, 'F', '0', '0', 'hrm:employee:remove', '#');
--;;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(1, 36), (1, 306), (1, 307), (1, 308);
