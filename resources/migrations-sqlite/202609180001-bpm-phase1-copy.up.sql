-- BPM Phase 1 审批闭环：抄送表 + 抄送我的菜单（SQLite）

CREATE TABLE IF NOT EXISTS biz_bpm_copy (
  copy_id             INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id             TEXT NOT NULL DEFAULT '',
  process_instance_id TEXT NOT NULL DEFAULT '',
  activity_id         TEXT NOT NULL DEFAULT '',
  activity_name       TEXT NOT NULL DEFAULT '',
  reason              TEXT NOT NULL DEFAULT '',
  create_by           TEXT NOT NULL DEFAULT '',
  create_time         TEXT
);
--;;

CREATE INDEX IF NOT EXISTS idx_bpm_copy_user ON biz_bpm_copy(user_id);
--;;

CREATE INDEX IF NOT EXISTS idx_bpm_copy_pid ON biz_bpm_copy(process_instance_id);
--;;

-- 页面: 抄送我的（挂在 办公(30) 下）
INSERT OR IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (52, '抄送我的', 30, 4, 'bpm/copy', 'business/bpm/copy/index', 'C', '0', '0', 'bpm:copy:list', 'mail');
--;;

-- 按钮权限（挂在我的待办 34 下）
INSERT OR IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, visible, status, perms, icon)
VALUES (328, '任务加签', 34, 4, 'F', '0', '0', 'bpm:task:sign', '#'),
       (329, '任务减签', 34, 5, 'F', '0', '0', 'bpm:task:delete-sign', '#'),
       (330, '任务抄送', 34, 6, 'F', '0', '0', 'bpm:task:copy', '#');
--;;

-- 授权给 admin 角色(1)
INSERT OR IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 52), (1, 328), (1, 329), (1, 330);
--;;
