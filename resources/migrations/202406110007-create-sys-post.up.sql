CREATE TABLE sys_post (
  post_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  post_code VARCHAR(64) NOT NULL,
  post_name VARCHAR(50) NOT NULL,
  post_sort INT DEFAULT 0,
  status CHAR(1) DEFAULT '0',
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT ''
);
--;;
CREATE INDEX idx_sys_post_status ON sys_post(status);
--;;
