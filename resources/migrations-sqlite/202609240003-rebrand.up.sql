-- 品牌更名为红创PMS: 模板默认的顶级部门与管理员昵称改为红创; 仅在仍为模板默认值时更新, 不覆盖已自定义的名称.
UPDATE sys_dept SET dept_name = '红创科技' WHERE dept_id = 1 AND dept_name = '若依科技';
--;;
UPDATE sys_user SET nick_name = '红创管理员' WHERE user_id = 1 AND nick_name = '若依管理员';
--;;
