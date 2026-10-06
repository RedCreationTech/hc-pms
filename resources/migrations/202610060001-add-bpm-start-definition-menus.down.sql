-- 回退: 删除发起流程/流程定义菜单 (MySQL)

DELETE FROM sys_role_menu WHERE menu_id IN (53, 54);
--;;

DELETE FROM sys_menu WHERE menu_id IN (53, 54);
--;;
