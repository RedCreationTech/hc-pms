-- 通知公告表
CREATE TABLE IF NOT EXISTS sys_notice (
  notice_id   INTEGER PRIMARY KEY AUTOINCREMENT,
  notice_name TEXT    NOT NULL DEFAULT '',
  notice_type TEXT    NOT NULL DEFAULT '1',   -- 1=通知 2=公告
  status      TEXT    NOT NULL DEFAULT '0',   -- 0=正常 1=关闭
  create_by   TEXT    NOT NULL DEFAULT '',
  create_time TEXT,
  update_by   TEXT    NOT NULL DEFAULT '',
  update_time TEXT,
  remark      TEXT    NOT NULL DEFAULT ''
);
--;;

-- 索引
CREATE INDEX IF NOT EXISTS idx_sys_notice_type ON sys_notice(notice_type);
--;;
CREATE INDEX IF NOT EXISTS idx_sys_notice_status ON sys_notice(status);
--;;
