-- F08 税率口径: 平台配置 kind 白名单新增 tax-rate.
ALTER TABLE pms_config_record DROP CHECK pms_config_record_kind_chk;
--;;
ALTER TABLE pms_config_record ADD CONSTRAINT pms_config_record_kind_chk
  CHECK (kind IN ('project-template','coding-rule','quarterly-target','rd-pool','period-lock','approval-policy','rate-table','exchange-rate','tax-rate'));
--;;
