-- C11 本地通知: 新增"我的通知"页面菜单 (项目管理下) 与其查看权限按钮.
INSERT OR IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon)
VALUES (5016,'我的通知',5000,10,'notifications','pms/notifications/index','C','0','0','pms:project:list','bell');
--;;
INSERT OR IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5016);
--;;
INSERT OR IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon)
VALUES (5046,'通知处理',5016,1,'','','F','0','0','pms:project:list','#');
--;;
INSERT OR IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5046);
--;;
