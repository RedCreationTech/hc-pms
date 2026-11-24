-- A04 售前资料交接: 项目管理菜单新增"售前资料交接"页面与按钮权限 (维护/提交/退回/审批).
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon)
VALUES (5047,'售前资料交接',5000,11,'presales','pms/presales/index','C','0','0','pms:project:list','solution');
--;;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5047);
--;;
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon)
VALUES (5048,'交接维护',5047,1,'','','F','0','0','pms:project:edit','#');
--;;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5048);
--;;
