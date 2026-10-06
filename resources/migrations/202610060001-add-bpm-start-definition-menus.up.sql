-- 补齐 BPM 生命周期菜单:发起流程页 + 流程定义页 (MySQL)

INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (53, '发起流程', 31, 3, 'bpm/start', 'business/bpm/start/index', 'C', '0', '0', 'bpm:instance:start', 'play');
--;;

INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (54, '流程定义', 31, 5, 'bpm/definition', 'business/bpm/definition/index', 'C', '0', '0', 'bpm:model:list', 'profile');
--;;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 53), (1, 54);
--;;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (2, 53);
--;;
