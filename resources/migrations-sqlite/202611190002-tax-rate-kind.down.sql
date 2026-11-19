-- F08 回退: 删除税率配置行 (CHECK 保持较宽的 kind 白名单, 与既有回退惯例一致).
DELETE FROM pms_config_record WHERE kind = 'tax-rate';
--;;
