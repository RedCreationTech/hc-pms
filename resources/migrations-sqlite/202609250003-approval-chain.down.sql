DROP TABLE pms_approval_step;
--;;
DROP TABLE pms_approval_flow;
--;;
DELETE FROM pms_config_record WHERE kind = 'approval-policy';
--;;
