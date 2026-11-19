-- C11 回退: 删除我的通知菜单与处理权限.
DELETE FROM sys_role_menu WHERE menu_id IN (5016,5046);
--;;
DELETE FROM sys_menu WHERE menu_id IN (5016,5046);
--;;
