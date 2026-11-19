-- G18 平台能力: 新增平台与兼容基线菜单 (查看), 归属项目管理一级菜单.
INSERT OR IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon)
VALUES (5009,'平台能力',5000,9,'platform','pms/platform/index','C','0','0','pms:project:list','desktop');
--;;
INSERT OR IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5009);
--;;
