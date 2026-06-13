-- 通知公告表
CREATE TABLE IF NOT EXISTS sys_notice (
  notice_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
  notice_name VARCHAR(500) NOT NULL DEFAULT '',
  notice_type CHAR(1)      NOT NULL DEFAULT '1',
  status      CHAR(1)      NOT NULL DEFAULT '0',
  create_by   VARCHAR(64)  NOT NULL DEFAULT '',
  create_time TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
  update_by   VARCHAR(64)  NOT NULL DEFAULT '',
  update_time TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  remark      VARCHAR(500) NOT NULL DEFAULT ''
);
--;;

CREATE INDEX idx_sys_notice_type ON sys_notice(notice_type);
--;;
CREATE INDEX idx_sys_notice_status ON sys_notice(status);
