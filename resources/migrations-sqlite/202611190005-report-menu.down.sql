-- G16 回退: 删除报表菜单与指标维护按钮.
DELETE FROM sys_role_menu WHERE menu_id IN (5008,5045);
--;;
DELETE FROM sys_menu WHERE menu_id IN (5008,5045);
--;;
