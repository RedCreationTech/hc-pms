-- 办公一体化 · OA 菜单（SQLite）
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (37, '日程管理', 30, 5, 'oa/calendar', 'business/oa/calendar/index', 'C', '0', '0', 'oa:calendar:list', 'calendar'),
       (38, '会议管理', 30, 6, 'oa/meeting', 'business/oa/meeting/index', 'C', '0', '0', 'oa:meeting:list', 'project');
--;;
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, visible, status, perms, icon)
VALUES (309, '日程新增', 37, 1, 'F', '0', '0', 'oa:calendar:add', '#'),
       (310, '会议新增', 38, 1, 'F', '0', '0', 'oa:meeting:add', '#');
--;;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(1, 37), (1, 38), (1, 309), (1, 310);
