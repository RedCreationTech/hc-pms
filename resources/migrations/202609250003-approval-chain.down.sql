DROP TABLE pms_approval_step;
--;;
DROP TABLE pms_approval_flow;
--;;
DELETE FROM pms_config_record WHERE kind = 'approval-policy';
--;;
ALTER TABLE pms_config_record DROP CHECK pms_config_record_kind_chk;
--;;
ALTER TABLE pms_config_record ADD CONSTRAINT pms_config_record_chk_1
  CHECK (kind IN ('project-template','coding-rule','quarterly-target','rd-pool','period-lock'));
--;;
