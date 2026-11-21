-- 回退公告正文列. 列已被早期列表查询引用, 回滚后旧代码仍需读该列,
-- 因此 down 不做 DROP COLUMN, 只做无副作用的空写以保持迁移可执行.
UPDATE sys_notice SET notice_content = notice_content WHERE 1 = 0;
--;;