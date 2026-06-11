CREATE TABLE sys_post (
  post_id BIGSERIAL PRIMARY KEY,
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
COMMENT ON TABLE sys_post IS '岗位信息表';
--;;
COMMENT ON COLUMN sys_post.post_id IS '岗位ID';
--;;
COMMENT ON COLUMN sys_post.post_code IS '岗位编码';
--;;
COMMENT ON COLUMN sys_post.post_name IS '岗位名称';
--;;
COMMENT ON COLUMN sys_post.post_sort IS '显示顺序';
--;;
COMMENT ON COLUMN sys_post.status IS '状态（0正常 1停用）';
