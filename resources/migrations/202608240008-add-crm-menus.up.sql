-- 办公一体化 · CRM 菜单（SQLite）
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (39, '客户管理', 30, 7, 'crm/customer', 'business/crm/customer/index', 'C', '0', '0', 'crm:customer:list', 'customer-service');
--;;
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, visible, status, perms, icon)
VALUES (311, '客户新增', 39, 1, 'F', '0', '0', 'crm:customer:add', '#'),
       (312, '客户修改', 39, 2, 'F', '0', '0', 'crm:customer:edit', '#'),
       (313, '客户删除', 39, 3, 'F', '0', '0', 'crm:customer:remove', '#');
--;;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(1, 39), (1, 311), (1, 312), (1, 313);
