CREATE TABLE IF NOT EXISTS biz_engineering (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineering_name VARCHAR(200) NOT NULL,
  engineering_code VARCHAR(100) DEFAULT '',
  description LONGTEXT,
  status CHAR(1) DEFAULT '0',
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX idx_biz_engineering_name ON biz_engineering(engineering_name);
--;;
CREATE INDEX idx_biz_engineering_del ON biz_engineering(del_flag);
--;;
CREATE TABLE IF NOT EXISTS biz_project (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineering_id BIGINT,
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
  start_date VARCHAR(50),
  end_date VARCHAR(50),
  building_area VARCHAR(100) DEFAULT '',
  project_cost VARCHAR(100) DEFAULT '',
  structure_type VARCHAR(100) DEFAULT '',
  building_floors VARCHAR(100) DEFAULT '',
  project_overview LONGTEXT,
  extra_json LONGTEXT,
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX idx_biz_project_engineering ON biz_project(engineering_id);
--;;
CREATE INDEX idx_biz_project_name ON biz_project(project_name);
--;;
CREATE INDEX idx_biz_project_del ON biz_project(del_flag);
--;;
CREATE TABLE IF NOT EXISTS biz_subcontract_team (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT,
  team_name VARCHAR(200) NOT NULL,
  leader_name VARCHAR(100) DEFAULT '',
  contact_phone VARCHAR(50) DEFAULT '',
  work_scope LONGTEXT,
  extra_json LONGTEXT,
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX idx_biz_subcontract_project ON biz_subcontract_team(project_id);
--;;
CREATE INDEX idx_biz_subcontract_del ON biz_subcontract_team(del_flag);
--;;
CREATE TABLE IF NOT EXISTS biz_solution (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineering_id BIGINT,
  project_id BIGINT,
  engineering_name VARCHAR(200) DEFAULT '',
  project_name VARCHAR(200) DEFAULT '',
  solution_name VARCHAR(200) NOT NULL,
  solution_type VARCHAR(100) DEFAULT '',
  solution_level VARCHAR(100) DEFAULT '',
  current_version INT DEFAULT 1,
  recommend_scene LONGTEXT,
  status VARCHAR(20) DEFAULT 'draft',
  progress INT DEFAULT 0,
  start_time TIMESTAMP NULL,
  end_time TIMESTAMP NULL,
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX idx_biz_solution_project ON biz_solution(project_id);
--;;
CREATE INDEX idx_biz_solution_del ON biz_solution(del_flag);
--;;
CREATE TABLE IF NOT EXISTS biz_solution_section (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  solution_id BIGINT NOT NULL,
  section_key VARCHAR(50) NOT NULL,
  section_title VARCHAR(100) NOT NULL,
  content_json LONGTEXT,
  sort_order INT DEFAULT 0,
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0',
  UNIQUE KEY uk_biz_solution_section (solution_id, section_key)
);
--;;
CREATE INDEX idx_biz_solution_section_solution ON biz_solution_section(solution_id);
--;;
CREATE TABLE IF NOT EXISTS biz_resource (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  resource_type VARCHAR(50) NOT NULL,
  name VARCHAR(200) NOT NULL,
  code VARCHAR(100) DEFAULT '',
  category VARCHAR(100) DEFAULT '',
  publish_unit VARCHAR(200) DEFAULT '',
  publish_date VARCHAR(50),
  effective_date VARCHAR(50),
  file_name VARCHAR(255) DEFAULT '',
  file_type VARCHAR(50) DEFAULT '',
  vector_status VARCHAR(50) DEFAULT 'pending',
  tags VARCHAR(500) DEFAULT '',
  related_project_name VARCHAR(200) DEFAULT '',
  structured_fields LONGTEXT,
  summary LONGTEXT,
  content LONGTEXT,
  status CHAR(1) DEFAULT '0',
  sort_order INT DEFAULT 0,
  extra_json LONGTEXT,
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX idx_biz_resource_type ON biz_resource(resource_type);
--;;
CREATE INDEX idx_biz_resource_name ON biz_resource(name);
--;;
CREATE INDEX idx_biz_resource_del ON biz_resource(del_flag);
--;;
CREATE TABLE IF NOT EXISTS biz_attachment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  biz_type VARCHAR(50) NOT NULL,
  biz_id BIGINT,
  section_key VARCHAR(50) DEFAULT '',
  file_purpose VARCHAR(50) DEFAULT 'attachment',
  original_name VARCHAR(255) NOT NULL,
  stored_name VARCHAR(255) NOT NULL,
  storage_type VARCHAR(20) DEFAULT 'local',
  storage_path VARCHAR(500) NOT NULL,
  file_url VARCHAR(500) DEFAULT '',
  mime_type VARCHAR(100) DEFAULT '',
  extension VARCHAR(50) DEFAULT '',
  file_size BIGINT DEFAULT 0,
  sort_order INT DEFAULT 0,
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX idx_biz_attachment_biz ON biz_attachment(biz_type, biz_id);
--;;
CREATE INDEX idx_biz_attachment_del ON biz_attachment(del_flag);
--;;
CREATE TABLE IF NOT EXISTS biz_gallery_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  atlas_id BIGINT NOT NULL,
  attachment_id BIGINT NOT NULL,
  image_title VARCHAR(200) DEFAULT '',
  image_desc LONGTEXT,
  is_cover CHAR(1) DEFAULT 'N',
  sort_order INT DEFAULT 0,
  create_by VARCHAR(64) DEFAULT '',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT '',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT '',
  del_flag CHAR(1) DEFAULT '0'
);
--;;
CREATE INDEX idx_biz_gallery_atlas ON biz_gallery_item(atlas_id);
--;;
CREATE INDEX idx_biz_gallery_del ON biz_gallery_item(del_flag);
