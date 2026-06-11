CREATE TABLE sys_job (
  job_id INTEGER PRIMARY KEY,
  job_name VARCHAR(100) NOT NULL,
  job_group VARCHAR(64) NOT NULL DEFAULT 'DEFAULT',
  invoke_target VARCHAR(500) NOT NULL,
  cron_expression VARCHAR(255) DEFAULT '',
  misfire_policy VARCHAR(20) DEFAULT '3',
  concurrent CHAR(1) DEFAULT '1',
  status CHAR(1) DEFAULT '0',
  create_by VARCHAR(64) DEFAULT '',
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TEXT DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT ''
);
--;;
CREATE TABLE sys_job_log (
  job_log_id INTEGER PRIMARY KEY,
  job_name VARCHAR(64) NOT NULL,
  job_group VARCHAR(64) NOT NULL,
  invoke_target VARCHAR(500) NOT NULL,
  job_message VARCHAR(500) DEFAULT '',
  status CHAR(1) DEFAULT '0',
  exception_info VARCHAR(4000) DEFAULT '',
  create_time TEXT DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX idx_sys_job_status ON sys_job(status);
--;;
CREATE INDEX idx_sys_job_log_create_time ON sys_job_log(create_time);
--;;
--;;
