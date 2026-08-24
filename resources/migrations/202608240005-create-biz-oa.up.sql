-- 办公一体化 · OA 协同办公（MySQL）
CREATE TABLE IF NOT EXISTS biz_oa_calendar (
  calendar_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
  title        VARCHAR(255) NOT NULL DEFAULT '',
  content      TEXT,
  start_time   DATETIME NULL,
  end_time     DATETIME NULL,
  all_day      CHAR(1) NOT NULL DEFAULT '0',
  color        VARCHAR(16) NOT NULL DEFAULT '',
  user_id      BIGINT DEFAULT 0,
  create_by    VARCHAR(64) NOT NULL DEFAULT '',
  create_time  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  update_by    VARCHAR(64) NOT NULL DEFAULT '',
  update_time  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_oa_calendar_user ON biz_oa_calendar(user_id);
--;;
CREATE TABLE IF NOT EXISTS biz_oa_meeting (
  meeting_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
  subject      VARCHAR(255) NOT NULL DEFAULT '',
  location     VARCHAR(255) NOT NULL DEFAULT '',
  start_time   DATETIME NULL,
  end_time     DATETIME NULL,
  participants TEXT,
  content      TEXT,
  status       CHAR(1) NOT NULL DEFAULT '1',
  create_by    VARCHAR(64) NOT NULL DEFAULT '',
  create_time  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  update_by    VARCHAR(64) NOT NULL DEFAULT '',
  update_time  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_oa_meeting_time ON biz_oa_meeting(start_time);
