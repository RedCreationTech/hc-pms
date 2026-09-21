-- BPM Phase 1 审批闭环:抄送表 + 抄送我的菜单(MySQL)

CREATE TABLE IF NOT EXISTS biz_bpm_copy (
  copy_id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id             VARCHAR(64)  NOT NULL DEFAULT '',
  process_instance_id VARCHAR(64)  NOT NULL DEFAULT '',
  activity_id         VARCHAR(64)  NOT NULL DEFAULT '',
  activity_name       VARCHAR(255) NOT NULL DEFAULT '',
  reason              VARCHAR(500) NOT NULL DEFAULT '',
  create_by           VARCHAR(64)  NOT NULL DEFAULT '',
  create_time         TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_bpm_copy_user ON biz_bpm_copy(user_id);
--;;
CREATE INDEX idx_bpm_copy_pid ON biz_bpm_copy(process_instance_id);
--;;

-- 页面: 抄送我的(挂在 办公(30) 下)
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (52, '抄送我的', 30, 4, 'bpm/copy', 'business/bpm/copy/index', 'C', '0', '0', 'bpm:copy:list', 'mail');
--;;

-- 按钮权限(挂在我的待办 34 下)
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, visible, status, perms, icon)
VALUES (328, '任务加签', 34, 4, 'F', '0', '0', 'bpm:task:sign', '#'),
       (329, '任务减签', 34, 5, 'F', '0', '0', 'bpm:task:delete-sign', '#'),
       (330, '任务抄送', 34, 6, 'F', '0', '0', 'bpm:task:copy', '#');
--;;

-- 授权给 admin 角色(1)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 52), (1, 328), (1, 329), (1, 330);
--;;
