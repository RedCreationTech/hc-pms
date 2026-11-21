-- no-op: 被删除的菜单对应的功能已不存在, 回滚无需恢复.
-- 必须有可执行语句: 只有注释的 down 会被 migratus 解析成空语段而报 "prepared statement has been finalized".
UPDATE sys_menu SET menu_name = menu_name WHERE 1 = 0;
--;;
