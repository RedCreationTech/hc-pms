-- 回退品牌更名: 仅在仍为红创默认值时恢复模板名称.
UPDATE sys_user SET nick_name = '若依管理员' WHERE user_id = 1 AND nick_name = '红创管理员';
--;;
UPDATE sys_dept SET dept_name = '若依科技' WHERE dept_id = 1 AND dept_name = '红创科技';
--;;
