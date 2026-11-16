-- H07 有效期费率表: 平台配置 kind 白名单新增 rate-table.
ALTER TABLE pms_config_record DROP CHECK pms_config_record_kind_chk;
--;;
ALTER TABLE pms_config_record ADD CONSTRAINT pms_config_record_kind_chk
  CHECK (kind IN ('project-template','coding-rule','quarterly-target','rd-pool','period-lock','approval-policy','rate-table'));
--;;
