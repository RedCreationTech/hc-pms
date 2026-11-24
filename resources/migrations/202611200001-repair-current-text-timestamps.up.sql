-- SQLite 侧 202611200001 的历史数据修复在 MySQL 侧无需执行: 该缺陷来自早期 SQLite 迁移
-- 把列默认值写成 CURRENT_TEXT (SQLite 非法函数名), MySQL 迁移集从未产生过这个字面量.
-- 本文件只用于**对齐两侧版本号**, 保证同一迁移 id 在两个目录都存在, 迁移集合可比对.
-- 用 0 行的 UPDATE 占位: Migratus 走 executeUpdate, 不能用 SELECT (会抛 query returns results).
UPDATE pms_lesson SET created_at = created_at WHERE 1 = 0;
