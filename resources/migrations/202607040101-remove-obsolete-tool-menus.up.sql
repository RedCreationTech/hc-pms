-- 移除已废弃的代码生成和表单构建菜单（功能已删除）
DELETE FROM sys_role_menu WHERE menu_id IN (
  SELECT menu_id FROM sys_menu WHERE menu_name IN ('代码生成', '表单构建')
);
--;;
DELETE FROM sys_menu WHERE menu_name IN ('代码生成', '表单构建');
