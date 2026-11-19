-- A04 回退: 删除售前资料交接记录与清单配置行 (CHECK 保持较宽的 kind 白名单, 与既有回退惯例一致).
DELETE FROM pms_gov_record WHERE kind = 'presales-handover';
--;;
DELETE FROM pms_config_record WHERE kind = 'presales-checklist';
--;;
