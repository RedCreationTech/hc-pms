-- 办公一体化 · BPM 动态菜单（SQLite）

-- 一级目录: 办公
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (30, '办公', 0, 3, 'office', NULL, 'M', '0', '0', NULL, 'appstore');
--;;
-- 二级目录: 流程管理
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (31, '流程管理', 30, 1, 'bpm', NULL, 'M', '0', '0', NULL, 'deployment-unit');
--;;
-- 页面: 流程模型 / 我的流程 / 我的待办 / 我的已办
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (32, '流程模型', 31, 1, 'model', 'business/bpm/model/index', 'C', '0', '0', 'bpm:model:list', 'cluster'),
       (33, '我的流程', 31, 2, 'instance', 'business/bpm/instance/index', 'C', '0', '0', 'bpm:instance:list', 'profile'),
       (34, '我的待办', 30, 2, 'bpm/todo', 'business/bpm/todo/index', 'C', '0', '0', 'bpm:task:todo', 'audit'),
       (35, '我的已办', 30, 3, 'bpm/done', 'business/bpm/done/index', 'C', '0', '0', 'bpm:task:done', 'history');
--;;
-- 按钮权限
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, visible, status, perms, icon)
VALUES (300, '分类新增', 30, 1, 'F', '0', '0', 'bpm:category:add', '#'),
       (301, '模型部署', 32, 1, 'F', '0', '0', 'bpm:model:deploy', '#'),
       (302, '发起流程', 33, 1, 'F', '0', '0', 'bpm:instance:start', '#'),
       (303, '审批通过', 34, 1, 'F', '0', '0', 'bpm:task:approve', '#'),
       (304, '审批驳回', 34, 2, 'F', '0', '0', 'bpm:task:reject', '#'),
       (305, '任务转办', 34, 3, 'F', '0', '0', 'bpm:task:transfer', '#');
--;;
-- 授权给 admin 角色(1)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(1, 30), (1, 31), (1, 32), (1, 33), (1, 34), (1, 35),
(1, 300), (1, 301), (1, 302), (1, 303), (1, 304), (1, 305);
