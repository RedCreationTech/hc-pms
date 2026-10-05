-- 员工自助: 将请假/报销申请菜单授予普通角色 (common, role_id=2), 使员工可自助发起请假与报销流程.
-- 此前 202609250001-security-baseline 只把办公容器与"我的流程/待办/已办/抄送"授予 common,
-- 遗漏了 请假申请(40)/请假发起(314) 与 报销申请(41)/报销发起(315), 导致员工进入请假页 403.
INSERT OR IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (2, 40), (2, 314), (2, 41), (2, 315);
--;;
