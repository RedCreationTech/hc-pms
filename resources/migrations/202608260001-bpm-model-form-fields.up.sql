ALTER TABLE biz_bpm_model ADD COLUMN form_id BIGINT DEFAULT 0;
ALTER TABLE biz_bpm_model ADD COLUMN form_custom_create_path VARCHAR(255) DEFAULT '';
ALTER TABLE biz_bpm_model ADD COLUMN form_custom_view_path VARCHAR(255) DEFAULT '';
--;;
