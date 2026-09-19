-- 种子化默认流程分类（id=1）。
-- 内置请假/报销模型(202608250001/202608250003)的 category_id=1 自始引用此分类，但分类行从未被插入。
INSERT OR IGNORE INTO biz_bpm_category (category_id, name, code, status, sort, order_num, create_by, create_time, remark)
VALUES (1, '通用', 'common', '0', 0, 0, 'system', CURRENT_TIMESTAMP, '默认流程分类');
--;;
