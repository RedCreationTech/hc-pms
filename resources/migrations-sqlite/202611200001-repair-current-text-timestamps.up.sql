-- H15 修复: 早期 SQLite 迁移把默认值写成 CURRENT_TEXT (非法函数名), 结果插入行时
-- created_at/reviewed_at 落库为字面量 'CURRENT_TEXT' 而不是时间戳, 经验/成本/关账台账
-- 因此无法按时间排序与展示. 本迁移只读修复既有数据并纠正列默认值, 不改任何业务规则.
UPDATE pms_lesson SET created_at = COALESCE(datetime('now'), '') WHERE created_at = 'CURRENT_TEXT';
--;;
UPDATE pms_time_entry SET created_at = COALESCE(datetime('now'), '') WHERE created_at = 'CURRENT_TEXT';
--;;
UPDATE pms_cost_version SET created_at = COALESCE(datetime('now'), '') WHERE created_at = 'CURRENT_TEXT';
--;;
UPDATE pms_cost_entry SET created_at = COALESCE(datetime('now'), '') WHERE created_at = 'CURRENT_TEXT';
--;;
UPDATE pms_cost_allocation SET created_at = COALESCE(datetime('now'), '') WHERE created_at = 'CURRENT_TEXT';
--;;
UPDATE pms_closure_item SET created_at = COALESCE(datetime('now'), '') WHERE created_at = 'CURRENT_TEXT';
--;;
UPDATE pms_closure_approval SET created_at = COALESCE(datetime('now'), '') WHERE created_at = 'CURRENT_TEXT';
--;;
UPDATE pms_closure_approval SET reviewed_at = COALESCE(datetime('now'), '') WHERE reviewed_at = 'CURRENT_TEXT';
--;;