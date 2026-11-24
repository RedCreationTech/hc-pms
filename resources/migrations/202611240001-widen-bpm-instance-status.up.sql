-- biz_bpm_instance.status 从 CHAR(1) 放宽到 VARCHAR(16).
--
-- 为什么: 流程实例结束回写时 cancel/terminate 路径写的是 'CANCELED'(8 字符),
-- 而列是 CHAR(1). SQLite 的 TEXT 不校验长度所以静默接受, MySQL 8 在
-- STRICT_TRANS_TABLES 下直接报 1406 Data too long for column 'status',
-- 导致"取消流程实例"在 MySQL 上一个实例都取消不掉, 业务状态停留在 '1'.
-- 既有编码 1=审批中 / 2=通过 / 3=驳回 都是单字符, 放宽后仍然兼容.
ALTER TABLE biz_bpm_instance MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT '1';
--;;