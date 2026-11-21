-- 数据修复不可逆: 回滚不改动已写入的时间戳.
-- down 里不能写 SELECT 1 (驱动会把结果集当成更新语句而报错), 用 0 行的 UPDATE 保持可执行.
UPDATE pms_lesson SET created_at = created_at WHERE 1 = 0;
--;;
