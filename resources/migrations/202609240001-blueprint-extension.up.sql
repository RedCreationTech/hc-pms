-- MySQL 蓝图对齐扩展: 平台级版本化配置表, 治理/交付记录新增类型, 计划任务结构节点与阶段映射, 工时更正链, 新增菜单与权限.
CREATE TABLE pms_config_record (
  config_id VARCHAR(36) PRIMARY KEY,
  kind VARCHAR(30) NOT NULL,
  code VARCHAR(100) NOT NULL,
  revision INTEGER NOT NULL DEFAULT 1,
  status VARCHAR(30) NOT NULL,
  created_by BIGINT NOT NULL,
  payload LONGTEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(kind,code,revision),
  CHECK(kind IN ('project-template','coding-rule','quarterly-target','rd-pool','period-lock')),
  CHECK(revision > 0),
  CHECK(status IN ('draft','published','retired','frozen','locked'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_pms_config_kind ON pms_config_record(kind,code,revision);
--;;
CREATE TABLE pms_gov_record_new (
  record_id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  kind VARCHAR(30) NOT NULL,
  code VARCHAR(100) NOT NULL,
  revision INTEGER NOT NULL DEFAULT 1,
  status VARCHAR(30) NOT NULL,
  created_by BIGINT NOT NULL,
  owner_id BIGINT,
  payload LONGTEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(project_id,kind,code,revision),
  FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
  CHECK(kind IN ('charter','requirement','document','trace','risk','issue','meeting','action','change','gate-template','gate','stakeholder','raci','comm-plan','template-instance','dq','node-pause')),
  CHECK(revision > 0),
  CHECK(status IN ('draft','in_review','approved','rejected','registered','open','mitigated','materialized','resolved','closed','recorded','planned','converted','ready','waived','active','assigned','discarded'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
INSERT INTO pms_gov_record_new (record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at)
  SELECT record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at FROM pms_gov_record;
--;;
DROP TABLE pms_gov_record;
--;;
ALTER TABLE pms_gov_record_new RENAME TO pms_gov_record;
--;;
CREATE INDEX idx_pms_gov_project_kind ON pms_gov_record(project_id,kind,status);
--;;
CREATE TABLE pms_delivery_record_new (
  record_id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL,
  kind VARCHAR(30) NOT NULL, code VARCHAR(100) NOT NULL, revision INTEGER NOT NULL DEFAULT 1,
  status VARCHAR(30) NOT NULL, created_by BIGINT NOT NULL, owner_id BIGINT,
  payload LONGTEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(project_id,kind,code,revision),
  FOREIGN KEY(project_id) REFERENCES pms_project(project_id),
  CHECK(kind IN ('configuration','material','bom','assembly','test','shipment','service','survey','handover','site-task')),
  CHECK(status IN ('registered','draft','in_review','approved','rejected','frozen','partial','ready','in_progress','released','shipped','received','conditional','returned','open','closed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
INSERT INTO pms_delivery_record_new (record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at)
  SELECT record_id,project_id,kind,code,revision,status,created_by,owner_id,payload,created_at,updated_at FROM pms_delivery_record;
--;;
DROP TABLE pms_delivery_record;
--;;
ALTER TABLE pms_delivery_record_new RENAME TO pms_delivery_record;
--;;
CREATE INDEX idx_delivery_project_kind ON pms_delivery_record(project_id,kind,status);
--;;
ALTER TABLE pms_plan_task ADD COLUMN node_id VARCHAR(36);
--;;
ALTER TABLE pms_plan_task ADD COLUMN stage_code VARCHAR(50);
--;;
ALTER TABLE pms_time_entry ADD COLUMN corrects_entry_id VARCHAR(36);
--;;
ALTER TABLE pms_time_entry ADD COLUMN correction_reason VARCHAR(1000) NOT NULL DEFAULT '';
--;;
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon) VALUES (5003,'模板与规则',5000,3,'config','pms/config/index','C','0','0','pms:config:list','setting');
--;;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5003);
--;;
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon) VALUES (5004,'项目组合看板',5000,4,'portfolio','pms/portfolio/index','C','0','0','pms:dashboard:query','appstore');
--;;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5004);
--;;
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon) VALUES (5005,'我的待办',5000,5,'todo','pms/todo/index','C','0','0','pms:project:list','bell');
--;;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5005);
--;;
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon) VALUES (5006,'全局检索',5000,6,'search','pms/search/index','C','0','0','pms:project:list','search');
--;;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5006);
--;;
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon) VALUES (5007,'经营目标看板',5000,7,'targets','pms/targets/index','C','0','0','pms:finance:query','fund');
--;;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5007);
--;;
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon) VALUES (5042,'模板与规则维护',5003,1,'','','F','0','0','pms:config:edit','#');
--;;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5042);
--;;
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon) VALUES (5043,'机密文档访问',5001,22,'','','F','0','0','pms:document:confidential','#');
--;;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5043);
--;;
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon) VALUES (5044,'经营目标维护',5007,1,'','','F','0','0','pms:target:edit','#');
--;;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,5044);
--;;
