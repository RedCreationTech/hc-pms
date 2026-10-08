-- 外部接口配置页菜单:依赖外部合同/接口的能力只读目录入口 (SQLite)

-- 5045 外部接口配置 (挂在 5000 项目管理 下, order_num 8)
INSERT OR IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (5045, '外部接口配置', 5000, 8, 'external-interfaces', 'pms/external-interfaces/index', 'C', '0', '0', 'pms:config:list', 'api');
--;;

-- 授权 admin (1)
INSERT OR IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 5045);
--;;
