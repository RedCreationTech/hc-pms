INSERT OR IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon) VALUES
(1999, '方案智能编制系统', 0, 0, '', NULL, 'M', '0', '0', '', 'dashboard'),
(2000, '方案管理', 1999, 1, 'solution', 'business/solution/index', 'C', '0', '0', 'business:solution:list', 'dashboard'),
(2100, '项目信息管理', 1999, 2, 'project/info', 'business/project/index', 'C', '0', '0', 'business:project:list', 'project'),
(2200, '资源管理', 1999, 3, 'resource', NULL, 'M', '0', '0', '', 'resource'),
(2201, '标准规范', 2200, 1, 'standard', 'business/resource/standard', 'C', '0', '0', 'business:resource:standard:list', 'file-text'),
(2202, '向量知识库', 2200, 2, 'vector-kb', 'business/resource/vector-kb', 'C', '0', '0', 'business:resource:vector:list', 'database'),
(2203, '结构化知识库', 2200, 3, 'structured-kb', 'business/resource/structured-kb', 'C', '0', '0', 'business:resource:structured:list', 'tree'),
(2204, '优秀案例库', 2200, 4, 'case', 'business/resource/case', 'C', '0', '0', 'business:resource:case:list', 'star'),
(2205, '通用图集库', 2200, 5, 'atlas', 'business/resource/atlas', 'C', '0', '0', 'business:resource:atlas:list', 'picture');
--;;
INSERT OR IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1999), (1, 2000), (1, 2100), (1, 2200), (1, 2201), (1, 2202), (1, 2203), (1, 2204), (1, 2205);
--;;
INSERT OR IGNORE INTO biz_engineering (id, engineering_name, engineering_code, description, status) VALUES
(1, '示例工程', 'GC-001', '用于方案编制演示的工程', '0');
--;;
INSERT OR IGNORE INTO biz_project (id, engineering_id, engineering_name, project_name, project_code, project_address, construction_unit, contractor_unit, supervision_unit, design_unit, survey_unit, project_manager, project_leader, contact_phone, contract_period, start_date, end_date, building_area, project_cost, structure_type, building_floors, project_overview) VALUES
(1, 1, '示例工程', '示例项目', 'XM-001', '示例地址', '建设单位', '施工单位', '监理单位', '设计单位', '勘察单位', '张三', '李四', '13800000000', '180天', '2026-01-01', '2026-06-30', '10000㎡', '1000万', '框架结构', '地上10层', '这是一个示例项目概况。');
--;;
INSERT OR IGNORE INTO biz_solution (id, engineering_id, project_id, engineering_name, project_name, solution_name, status, progress) VALUES
(1, 1, 1, '示例工程', '示例项目', '示例项目施工方案', 'draft', 20);
