-- 办公一体化 · OA 协同办公(SQLite)
-- 日程
CREATE TABLE IF NOT EXISTS biz_oa_calendar (
  calendar_id  INTEGER PRIMARY KEY AUTOINCREMENT,
  title        TEXT NOT NULL DEFAULT '',
  content      TEXT NOT NULL DEFAULT '',
  start_time   TEXT,
  end_time     TEXT,
  all_day      TEXT NOT NULL DEFAULT '0',
  color        TEXT NOT NULL DEFAULT '',
  user_id      INTEGER DEFAULT 0,
  create_by    TEXT NOT NULL DEFAULT '',
  create_time  TEXT,
  update_by    TEXT NOT NULL DEFAULT '',
  update_time  TEXT
);
--;;
CREATE INDEX IF NOT EXISTS idx_oa_calendar_user ON biz_oa_calendar(user_id);
--;;

-- 会议
CREATE TABLE IF NOT EXISTS biz_oa_meeting (
  meeting_id   INTEGER PRIMARY KEY AUTOINCREMENT,
  subject      TEXT NOT NULL DEFAULT '',
  location     TEXT NOT NULL DEFAULT '',
  start_time   TEXT,
  end_time     TEXT,
  participants TEXT NOT NULL DEFAULT '',
  content      TEXT NOT NULL DEFAULT '',
  status       TEXT NOT NULL DEFAULT '1',
  create_by    TEXT NOT NULL DEFAULT '',
  create_time  TEXT,
  update_by    TEXT NOT NULL DEFAULT '',
  update_time  TEXT
);
--;;
CREATE INDEX IF NOT EXISTS idx_oa_meeting_time ON biz_oa_meeting(start_time);
