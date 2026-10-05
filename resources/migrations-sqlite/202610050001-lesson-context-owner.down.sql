-- H15a down: 移除经验适用场景与跟进责任人列.
ALTER TABLE pms_lesson DROP COLUMN owner_id;
--;;
ALTER TABLE pms_lesson DROP COLUMN applicable_stage;
--;;
