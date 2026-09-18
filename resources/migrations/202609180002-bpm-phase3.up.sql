-- BPM Phase 3 治理能力：模型扩展字段 + 实例单号/名称（MySQL）
-- process_id_rule 流程编号规则 JSON / auto_approval_type 自动去重
-- name_rule 自定义标题模板 / summary_fields 摘要字段 JSON
-- print_template_enable / print_template_html 打印模板
-- biz_bpm_instance.name 渲染后的实例名 / bill_code 流程单号

ALTER TABLE biz_bpm_model ADD COLUMN process_id_rule TEXT;
--;;
ALTER TABLE biz_bpm_model ADD COLUMN auto_approval_type VARCHAR(32) NOT NULL DEFAULT 'NONE';
--;;
ALTER TABLE biz_bpm_model ADD COLUMN name_rule VARCHAR(500);
--;;
ALTER TABLE biz_bpm_model ADD COLUMN summary_fields TEXT;
--;;
ALTER TABLE biz_bpm_model ADD COLUMN print_template_enable CHAR(1) NOT NULL DEFAULT '0';
--;;
ALTER TABLE biz_bpm_model ADD COLUMN print_template_html TEXT;
--;;
ALTER TABLE biz_bpm_instance ADD COLUMN name VARCHAR(255) NOT NULL DEFAULT '';
--;;
ALTER TABLE biz_bpm_instance ADD COLUMN bill_code VARCHAR(64);
--;;
