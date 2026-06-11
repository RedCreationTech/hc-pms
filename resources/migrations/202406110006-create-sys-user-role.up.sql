CREATE TABLE sys_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id)
);

COMMENT ON TABLE sys_user_role IS '用户和角色关联表';
