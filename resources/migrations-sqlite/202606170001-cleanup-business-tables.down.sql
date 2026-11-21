-- 此迁移不可逆 (业务表与菜单数据已删除, 无原始数据可恢复).
-- down 里不能写 SELECT 1 (驱动会把结果集当成更新语句而报错), 用 0 行的 UPDATE 保持可执行.
UPDATE sys_config SET config_value = config_value WHERE 1 = 0;
--;;
