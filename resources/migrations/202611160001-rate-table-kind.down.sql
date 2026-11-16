-- H07 回退: 删除费率表配置行并恢复 kind 白名单.
DELETE FROM pms_config_record WHERE kind = 'rate-table';
--;;
ALTER TABLE pms_config_record DROP CHECK pms_config_record_kind_chk;
--;;
ALTER TABLE pms_config_record ADD CONSTRAINT pms_config_record_kind_chk
  CHECK (kind IN ('project-template','coding-rule','quarterly-target','rd-pool','period-lock','approval-policy'));
--;;
