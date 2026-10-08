-- 回滚外部接口配置页菜单 (MySQL)
DELETE FROM sys_role_menu WHERE menu_id = 5045;
--;;
DELETE FROM sys_menu WHERE menu_id = 5045;
--;;
