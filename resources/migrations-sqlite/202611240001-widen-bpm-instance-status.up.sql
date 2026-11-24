-- biz_bpm_instance.status 在 SQLite 侧本来就是 TEXT, 没有长度限制, 不需要改变列.
-- 本迁移只为让两个数据库的版本序列保持同步 (MySQL 侧有真实的 ALTER), 避免
-- 后续新增迁移在两边版本号错位.
-- 注意: 不能写 SELECT 1; Migratus 走 executeUpdate, 任何返回结果集的语句都会
-- 抛 "query returns results". 用不改数据的 UPDATE 作为占位.
UPDATE biz_bpm_instance SET status = status WHERE 0;
--;;
