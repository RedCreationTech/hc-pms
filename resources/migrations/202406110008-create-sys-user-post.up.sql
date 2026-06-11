CREATE TABLE sys_user_post (
  user_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, post_id)
);
--;;
COMMENT ON TABLE sys_user_post IS '用户与岗位关联表';
