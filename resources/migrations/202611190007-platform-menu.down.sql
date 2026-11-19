-- G18 回退: 删除平台能力菜单.
DELETE FROM sys_role_menu WHERE menu_id IN (5009);
--;;
DELETE FROM sys_menu WHERE menu_id IN (5009);
--;;
