-- H16 成员委派到期: 为 pms_member 增加可空 ends_on(委派/成员到期日 ISO 日期串)列, 留空表示长期有效, 历史成员行不删除以保留署名.
ALTER TABLE pms_member ADD COLUMN ends_on VARCHAR(10);
--;;
