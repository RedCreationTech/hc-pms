CREATE TABLE IF NOT EXISTS biz_engineering (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  engineering_name VARCHAR(200) NOT NULL,
  engineering_code VARCHAR(100) DEFAULT '',
  description TEXT DEFAULT '',
  status CHAR(1) DEFAULT '0',
  create_by VARCHAR(64) DEFAULT '',
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TEXT DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_engineering_name ON biz_engineering(engineering_name);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_engineering_del ON biz_engineering(del_flag);
--;;
CREATE TABLE IF NOT EXISTS biz_project (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  engineering_id INTEGER,
  engineering_name VARCHAR(200) DEFAULT '',
  project_name VARCHAR(200) NOT NULL,
  project_code VARCHAR(100) DEFAULT '',
  project_address VARCHAR(500) DEFAULT '',
  construction_unit VARCHAR(200) DEFAULT '',
  contractor_unit VARCHAR(200) DEFAULT '',
  supervision_unit VARCHAR(200) DEFAULT '',
  design_unit VARCHAR(200) DEFAULT '',
  survey_unit VARCHAR(200) DEFAULT '',
  project_manager VARCHAR(100) DEFAULT '',
  project_leader VARCHAR(100) DEFAULT '',
  contact_phone VARCHAR(50) DEFAULT '',
  contract_period VARCHAR(100) DEFAULT '',
  start_date TEXT,
  end_date TEXT,
  building_area VARCHAR(100) DEFAULT '',
  project_cost VARCHAR(100) DEFAULT '',
  structure_type VARCHAR(100) DEFAULT '',
  building_floors VARCHAR(100) DEFAULT '',
  project_overview TEXT DEFAULT '',
  extra_json TEXT DEFAULT '{}',
  create_by VARCHAR(64) DEFAULT '',
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TEXT DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_project_engineering ON biz_project(engineering_id);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_project_name ON biz_project(project_name);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_project_del ON biz_project(del_flag);
--;;
CREATE TABLE IF NOT EXISTS biz_subcontract_team (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id INTEGER,
  team_name VARCHAR(200) NOT NULL,
  leader_name VARCHAR(100) DEFAULT '',
  contact_phone VARCHAR(50) DEFAULT '',
  work_scope TEXT DEFAULT '',
  extra_json TEXT DEFAULT '{}',
  create_by VARCHAR(64) DEFAULT '',
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TEXT DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_subcontract_project ON biz_subcontract_team(project_id);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_subcontract_del ON biz_subcontract_team(del_flag);
--;;
CREATE TABLE IF NOT EXISTS biz_solution (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  engineering_id INTEGER,
  project_id INTEGER,
  engineering_name VARCHAR(200) DEFAULT '',
  project_name VARCHAR(200) DEFAULT '',
  solution_name VARCHAR(200) NOT NULL,
  solution_type VARCHAR(100) DEFAULT '',
  solution_level VARCHAR(100) DEFAULT '',
  current_version INTEGER DEFAULT 1,
  recommend_scene TEXT DEFAULT '{}',
  status VARCHAR(20) DEFAULT 'draft',
  progress INTEGER DEFAULT 0,
  start_time TEXT,
  end_time TEXT,
  create_by VARCHAR(64) DEFAULT '',
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TEXT DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_solution_project ON biz_solution(project_id);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_solution_del ON biz_solution(del_flag);
--;;
CREATE TABLE IF NOT EXISTS biz_solution_section (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  solution_id INTEGER NOT NULL,
  section_key VARCHAR(50) NOT NULL,
  section_title VARCHAR(100) NOT NULL,
  content_json TEXT DEFAULT '{}',
  sort_order INTEGER DEFAULT 0,
  create_by VARCHAR(64) DEFAULT '',
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TEXT DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0',
  UNIQUE(solution_id, section_key)
);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_solution_section_solution ON biz_solution_section(solution_id);
--;;
CREATE TABLE IF NOT EXISTS biz_resource (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  resource_type VARCHAR(50) NOT NULL,
  name VARCHAR(200) NOT NULL,
  code VARCHAR(100) DEFAULT '',
  category VARCHAR(100) DEFAULT '',
  publish_unit VARCHAR(200) DEFAULT '',
  publish_date TEXT,
  effective_date TEXT,
  file_name VARCHAR(255) DEFAULT '',
  file_type VARCHAR(50) DEFAULT '',
  vector_status VARCHAR(50) DEFAULT 'pending',
  tags VARCHAR(500) DEFAULT '',
  related_project_name VARCHAR(200) DEFAULT '',
  structured_fields TEXT DEFAULT '',
  summary TEXT DEFAULT '',
  content TEXT DEFAULT '',
  status CHAR(1) DEFAULT '0',
  sort_order INTEGER DEFAULT 0,
  extra_json TEXT DEFAULT '{}',
  create_by VARCHAR(64) DEFAULT '',
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TEXT DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_resource_type ON biz_resource(resource_type);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_resource_name ON biz_resource(name);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_resource_del ON biz_resource(del_flag);
--;;
CREATE TABLE IF NOT EXISTS biz_attachment (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  biz_type VARCHAR(50) NOT NULL,
  biz_id INTEGER,
  section_key VARCHAR(50) DEFAULT '',
  file_purpose VARCHAR(50) DEFAULT 'attachment',
  original_name VARCHAR(255) NOT NULL,
  stored_name VARCHAR(255) NOT NULL,
  storage_type VARCHAR(20) DEFAULT 'local',
  storage_path VARCHAR(500) NOT NULL,
  file_url VARCHAR(500) DEFAULT '',
  mime_type VARCHAR(100) DEFAULT '',
  extension VARCHAR(50) DEFAULT '',
  file_size INTEGER DEFAULT 0,
  sort_order INTEGER DEFAULT 0,
  create_by VARCHAR(64) DEFAULT '',
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TEXT DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_attachment_biz ON biz_attachment(biz_type, biz_id);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_attachment_del ON biz_attachment(del_flag);
--;;
CREATE TABLE IF NOT EXISTS biz_gallery_item (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  atlas_id INTEGER NOT NULL,
  attachment_id INTEGER NOT NULL,
  image_title VARCHAR(200) DEFAULT '',
  image_desc TEXT DEFAULT '',
  is_cover CHAR(1) DEFAULT 'N',
  sort_order INTEGER DEFAULT 0,
  create_by VARCHAR(64) DEFAULT '',
  create_time TEXT DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TEXT DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_gallery_atlas ON biz_gallery_item(atlas_id);
--;;
CREATE INDEX IF NOT EXISTS idx_biz_gallery_del ON biz_gallery_item(del_flag);
