-- BPM P0：模型提交人/审批人权限开关（SQLite）
-- allow_cancel    提交人权限：允许撤销审批中的申请（'1' 允许 / '0' 禁止）
-- allow_withdraw  审批人权限：允许审批人撤回（'1' 允许 / '0' 禁止）

ALTER TABLE biz_bpm_model ADD COLUMN allow_cancel CHAR(1) NOT NULL DEFAULT '1';
--;;
ALTER TABLE biz_bpm_model ADD COLUMN allow_withdraw CHAR(1) NOT NULL DEFAULT '1';
--;;
