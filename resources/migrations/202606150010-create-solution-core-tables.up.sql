CREATE TABLE IF NOT EXISTS biz_solution_status (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  solution_id BIGINT NOT NULL,
  chapter_name VARCHAR(255) NOT NULL,
  chapter_key VARCHAR(100),
  sort_order INT DEFAULT 0,
  status INT DEFAULT -1,
  start_time TIMESTAMP NULL,
  end_time TIMESTAMP NULL,
  error_msg LONGTEXT,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX idx_solution_status_solution_id ON biz_solution_status(solution_id);
--;;
CREATE TABLE IF NOT EXISTS biz_resource_relation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  resource_id BIGINT NOT NULL,
  resource_type VARCHAR(50) NOT NULL,
  route_type VARCHAR(50) NOT NULL,
  route_value VARCHAR(200) NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX idx_resource_relation_res ON biz_resource_relation(resource_id, resource_type);
--;;
CREATE INDEX idx_resource_relation_route ON biz_resource_relation(route_type, route_value);
--;;
CREATE TABLE IF NOT EXISTS biz_chat_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  solution_id BIGINT NOT NULL,
  user_id BIGINT,
  user_name VARCHAR(100),
  title VARCHAR(255),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX idx_chat_session_solution ON biz_chat_session(solution_id, user_id);
--;;
CREATE TABLE IF NOT EXISTS biz_chat_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  role VARCHAR(50) NOT NULL,
  content LONGTEXT,
  reasoning_content LONGTEXT,
  msg_order INT DEFAULT 0,
  model_name VARCHAR(100),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX idx_chat_message_session ON biz_chat_message(session_id, msg_order);
--;;
CREATE TABLE IF NOT EXISTS biz_solution_file (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  solution_id BIGINT NOT NULL,
  file_name VARCHAR(255),
  file_path VARCHAR(500),
  file_type VARCHAR(50) DEFAULT 'docx',
  version INT DEFAULT 1,
  file_size BIGINT DEFAULT 0,
  create_by VARCHAR(64),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX idx_solution_file_solution ON biz_solution_file(solution_id);
