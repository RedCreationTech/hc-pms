-- P0: 方案生成核心业务表
-- 方案章节生成进度
CREATE TABLE IF NOT EXISTS biz_solution_status (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  solution_id INTEGER NOT NULL,
  chapter_name TEXT NOT NULL,
  chapter_key TEXT,
  sort_order INTEGER DEFAULT 0,
  status INTEGER DEFAULT -1,
  start_time TEXT,
  end_time TEXT,
  error_msg TEXT,
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  update_time TEXT DEFAULT CURRENT_TIMESTAMP,
  del_flag CHAR(1) DEFAULT '0'
);
CREATE INDEX IF NOT EXISTS idx_solution_status_solution_id ON biz_solution_status(solution_id);

-- 资源关联表（方案类型 + 省份匹配）
CREATE TABLE IF NOT EXISTS biz_resource_relation (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  resource_id INTEGER NOT NULL,
  resource_type TEXT NOT NULL,
  route_type TEXT NOT NULL,
  route_value TEXT NOT NULL,
  create_time TEXT DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_resource_relation_res ON biz_resource_relation(resource_id, resource_type);
CREATE INDEX IF NOT EXISTS idx_resource_relation_route ON biz_resource_relation(route_type, route_value);

-- AI对话会话
CREATE TABLE IF NOT EXISTS biz_chat_session (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  solution_id INTEGER NOT NULL,
  user_id INTEGER,
  user_name TEXT,
  title TEXT,
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  update_time TEXT DEFAULT CURRENT_TIMESTAMP,
  del_flag CHAR(1) DEFAULT '0'
);
CREATE INDEX IF NOT EXISTS idx_chat_session_solution ON biz_chat_session(solution_id, user_id);

-- AI对话消息
CREATE TABLE IF NOT EXISTS biz_chat_message (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id INTEGER NOT NULL,
  role TEXT NOT NULL,
  content TEXT,
  reasoning_content TEXT,
  msg_order INTEGER DEFAULT 0,
  model_name TEXT,
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  del_flag CHAR(1) DEFAULT '0'
);
CREATE INDEX IF NOT EXISTS idx_chat_message_session ON biz_chat_message(session_id, msg_order);

-- 方案文件版本管理
CREATE TABLE IF NOT EXISTS biz_solution_file (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  solution_id INTEGER NOT NULL,
  file_name TEXT,
  file_path TEXT,
  file_type TEXT DEFAULT 'docx',
  version INTEGER DEFAULT 1,
  file_size INTEGER DEFAULT 0,
  create_by TEXT,
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  del_flag CHAR(1) DEFAULT '0'
);
CREATE INDEX IF NOT EXISTS idx_solution_file_solution ON biz_solution_file(solution_id);
