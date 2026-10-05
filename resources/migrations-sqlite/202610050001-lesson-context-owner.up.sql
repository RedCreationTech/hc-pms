-- H15a 经验教训标记适用场景与跟进责任人: 为 pms_lesson 增加可空 applicable_stage(适用场景/阶段枚举)与 owner_id(跟进责任人)两列, 历史经验留空表示未标注.
ALTER TABLE pms_lesson ADD COLUMN applicable_stage VARCHAR(40);
--;;
ALTER TABLE pms_lesson ADD COLUMN owner_id BIGINT;
--;;
