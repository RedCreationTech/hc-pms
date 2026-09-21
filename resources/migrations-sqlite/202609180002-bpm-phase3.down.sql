-- BPM Phase 3 down(SQLite)

ALTER TABLE biz_bpm_model DROP COLUMN process_id_rule;
--;;
ALTER TABLE biz_bpm_model DROP COLUMN auto_approval_type;
--;;
ALTER TABLE biz_bpm_model DROP COLUMN name_rule;
--;;
ALTER TABLE biz_bpm_model DROP COLUMN summary_fields;
--;;
ALTER TABLE biz_bpm_model DROP COLUMN print_template_enable;
--;;
ALTER TABLE biz_bpm_model DROP COLUMN print_template_html;
--;;
ALTER TABLE biz_bpm_instance DROP COLUMN name;
--;;
ALTER TABLE biz_bpm_instance DROP COLUMN bill_code;
--;;
