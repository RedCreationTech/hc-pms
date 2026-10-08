-- 外部接口配置页菜单:依赖外部合同/接口的能力只读目录入口 (MySQL)

INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (5045, '外部接口配置', 5000, 8, 'external-interfaces', 'pms/external-interfaces/index', 'C', '0', '0', 'pms:config:list', 'api');
--;;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 5045);
--;;
