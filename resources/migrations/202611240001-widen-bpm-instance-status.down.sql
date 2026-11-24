-- 回退: 放宽回 CHAR(1). 执行前必须先把超长状态归一到单字符, 否则 MySQL 会因
-- Data too long 拒绝 ALTER, 而 SQLite 会静默截断丢数据.
UPDATE biz_bpm_instance SET status = '4' WHERE status = 'CANCELED';
--;;
ALTER TABLE biz_bpm_instance MODIFY COLUMN status CHAR(1) NOT NULL DEFAULT '1';
--;;
