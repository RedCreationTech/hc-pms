-- A04 回退: 删除售前资料交接菜单与按钮.
DELETE FROM sys_role_menu WHERE menu_id IN (5047,5048);
--;;
DELETE FROM sys_menu WHERE menu_id IN (5047,5048);
--;;
