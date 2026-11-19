-- G16 报表指标: 新增报表中心菜单 (查看) 与指标维护按钮权限.
INSERT OR IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon)
VALUES (5008,'报表中心',5000,8,'reports','pms/reports/index','C','0','0','pms:dashboard:query','bar-chart');
--;;
INSERT OR IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5008);
--;;
INSERT OR IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon)
VALUES (5045,'报表指标维护',5042,2,'','','F','0','0','pms:config:edit','#');
--;;
INSERT OR IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5045);
--;;
