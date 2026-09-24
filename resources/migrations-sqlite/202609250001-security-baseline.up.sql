-- S1 安全与权限底线: 令牌撤销表, 登录相关参数, 补齐若依标准按钮权限与办公模块增删改权限 (授予超级管理员角色).
CREATE TABLE sys_token_revoke (
  revoke_key VARCHAR(80) NOT NULL PRIMARY KEY,
  revoked_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
--;;
INSERT INTO sys_config (config_name, config_key, config_value, config_type)
SELECT '账号自助-验证码开关', 'sys.account.captchaEnabled', 'false', 'Y' FROM sys_config WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'sys.account.captchaEnabled') LIMIT 1;
--;;
INSERT INTO sys_config (config_name, config_key, config_value, config_type)
SELECT '账号自助-是否开启用户注册功能', 'sys.account.registerUser', 'false', 'Y' FROM sys_config WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'sys.account.registerUser') LIMIT 1;
--;;
INSERT OR IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, visible, status, perms, icon)
VALUES (1000, '用户导入', 3, 1, 'F', '0', '0', 'system:user:import', '#'),
       (1001, '重置密码', 3, 2, 'F', '0', '0', 'system:user:resetPwd', '#'),
       (1002, '部门查询', 6, 1, 'F', '0', '0', 'system:dept:query', '#'),
       (1003, '部门新增', 6, 2, 'F', '0', '0', 'system:dept:add', '#'),
       (1004, '部门修改', 6, 3, 'F', '0', '0', 'system:dept:edit', '#'),
       (1005, '部门删除', 6, 4, 'F', '0', '0', 'system:dept:remove', '#'),
       (1006, '岗位查询', 7, 1, 'F', '0', '0', 'system:post:query', '#'),
       (1007, '岗位新增', 7, 2, 'F', '0', '0', 'system:post:add', '#'),
       (1008, '岗位修改', 7, 3, 'F', '0', '0', 'system:post:edit', '#'),
       (1009, '岗位删除', 7, 4, 'F', '0', '0', 'system:post:remove', '#'),
       (1010, '岗位导出', 7, 5, 'F', '0', '0', 'system:post:export', '#'),
       (1011, '字典查询', 8, 1, 'F', '0', '0', 'system:dict:query', '#'),
       (1012, '字典新增', 8, 2, 'F', '0', '0', 'system:dict:add', '#'),
       (1013, '字典修改', 8, 3, 'F', '0', '0', 'system:dict:edit', '#'),
       (1014, '字典删除', 8, 4, 'F', '0', '0', 'system:dict:remove', '#'),
       (1015, '字典导出', 8, 5, 'F', '0', '0', 'system:dict:export', '#'),
       (1016, '参数查询', 9, 1, 'F', '0', '0', 'system:config:query', '#'),
       (1017, '参数新增', 9, 2, 'F', '0', '0', 'system:config:add', '#'),
       (1018, '参数修改', 9, 3, 'F', '0', '0', 'system:config:edit', '#'),
       (1019, '参数删除', 9, 4, 'F', '0', '0', 'system:config:remove', '#'),
       (1020, '参数导出', 9, 5, 'F', '0', '0', 'system:config:export', '#'),
       (1021, '公告查询', 10, 1, 'F', '0', '0', 'system:notice:query', '#'),
       (1022, '公告新增', 10, 2, 'F', '0', '0', 'system:notice:add', '#'),
       (1023, '公告修改', 10, 3, 'F', '0', '0', 'system:notice:edit', '#'),
       (1024, '公告删除', 10, 4, 'F', '0', '0', 'system:notice:remove', '#'),
       (1025, '操作查询', 11, 1, 'F', '0', '0', 'monitor:operlog:query', '#'),
       (1026, '操作删除', 11, 2, 'F', '0', '0', 'monitor:operlog:remove', '#'),
       (1027, '日志导出', 11, 3, 'F', '0', '0', 'monitor:operlog:export', '#'),
       (1028, '登录查询', 12, 1, 'F', '0', '0', 'monitor:logininfor:query', '#'),
       (1029, '登录删除', 12, 2, 'F', '0', '0', 'monitor:logininfor:remove', '#'),
       (1030, '日志导出', 12, 3, 'F', '0', '0', 'monitor:logininfor:export', '#'),
       (1031, '账户解锁', 12, 4, 'F', '0', '0', 'monitor:logininfor:unlock', '#'),
       (1032, '在线查询', 13, 1, 'F', '0', '0', 'monitor:online:query', '#'),
       (1033, '单条强退', 13, 2, 'F', '0', '0', 'monitor:online:forceLogout', '#'),
       (1034, '任务查询', 14, 1, 'F', '0', '0', 'monitor:job:query', '#'),
       (1035, '任务新增', 14, 2, 'F', '0', '0', 'monitor:job:add', '#'),
       (1036, '任务修改', 14, 3, 'F', '0', '0', 'monitor:job:edit', '#'),
       (1037, '任务删除', 14, 4, 'F', '0', '0', 'monitor:job:remove', '#'),
       (1038, '状态修改', 14, 5, 'F', '0', '0', 'monitor:job:changeStatus', '#'),
       (1039, '任务导出', 14, 6, 'F', '0', '0', 'monitor:job:export', '#'),
       (1040, '模型查询', 32, 1, 'F', '0', '0', 'bpm:model:query', '#'),
       (1041, '模型新增', 32, 2, 'F', '0', '0', 'bpm:model:add', '#'),
       (1042, '模型修改', 32, 3, 'F', '0', '0', 'bpm:model:edit', '#'),
       (1043, '模型删除', 32, 4, 'F', '0', '0', 'bpm:model:remove', '#'),
       (1044, '分类修改', 44, 1, 'F', '0', '0', 'bpm:category:edit', '#'),
       (1045, '分类删除', 44, 2, 'F', '0', '0', 'bpm:category:remove', '#'),
       (1046, '表单修改', 43, 1, 'F', '0', '0', 'bpm:form:edit', '#'),
       (1047, '表单删除', 43, 2, 'F', '0', '0', 'bpm:form:remove', '#'),
       (1048, '分组修改', 45, 1, 'F', '0', '0', 'bpm:user-group:edit', '#'),
       (1049, '分组删除', 45, 2, 'F', '0', '0', 'bpm:user-group:remove', '#'),
       (1050, '监听器修改', 46, 1, 'F', '0', '0', 'bpm:listener:edit', '#'),
       (1051, '监听器删除', 46, 2, 'F', '0', '0', 'bpm:listener:remove', '#'),
       (1052, '表达式修改', 47, 1, 'F', '0', '0', 'bpm:expression:edit', '#'),
       (1053, '表达式删除', 47, 2, 'F', '0', '0', 'bpm:expression:remove', '#'),
       (1054, '设置修改', 51, 1, 'F', '0', '0', 'bpm:settings:edit', '#'),
       (1055, '设置删除', 51, 2, 'F', '0', '0', 'bpm:settings:remove', '#'),
       (1056, '取消任意流程', 48, 1, 'F', '0', '0', 'bpm:instance:cancel', '#'),
       (1057, '日程修改', 37, 1, 'F', '0', '0', 'oa:calendar:edit', '#'),
       (1058, '日程删除', 37, 2, 'F', '0', '0', 'oa:calendar:remove', '#'),
       (1059, '会议修改', 38, 1, 'F', '0', '0', 'oa:meeting:edit', '#'),
       (1060, '会议删除', 38, 2, 'F', '0', '0', 'oa:meeting:remove', '#'),
       (1061, '请假删除', 40, 1, 'F', '0', '0', 'oa:leave:remove', '#'),
       (1062, '报销删除', 41, 1, 'F', '0', '0', 'oa:reimburse:remove', '#');
--;;
INSERT OR IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 1000), (1, 1001), (1, 1002), (1, 1003), (1, 1004), (1, 1005), (1, 1006), (1, 1007), (1, 1008), (1, 1009), (1, 1010), (1, 1011), (1, 1012), (1, 1013), (1, 1014), (1, 1015), (1, 1016), (1, 1017), (1, 1018), (1, 1019), (1, 1020), (1, 1021), (1, 1022), (1, 1023), (1, 1024), (1, 1025), (1, 1026), (1, 1027), (1, 1028), (1, 1029), (1, 1030), (1, 1031), (1, 1032), (1, 1033), (1, 1034), (1, 1035), (1, 1036), (1, 1037), (1, 1038), (1, 1039), (1, 1040), (1, 1041), (1, 1042), (1, 1043), (1, 1044), (1, 1045), (1, 1046), (1, 1047), (1, 1048), (1, 1049), (1, 1050), (1, 1051), (1, 1052), (1, 1053), (1, 1054), (1, 1055), (1, 1056), (1, 1057), (1, 1058), (1, 1059), (1, 1060), (1, 1061), (1, 1062);
--;;
UPDATE sys_menu SET perms = 'bpm:instance:manage' WHERE menu_id = 48;
--;;
INSERT OR IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (2, 30), (2, 31), (2, 33), (2, 34), (2, 35), (2, 52), (2, 302), (2, 303), (2, 304), (2, 305), (2, 328), (2, 329), (2, 330);
--;;
