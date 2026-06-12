-- :name list-tables :? :*
-- :doc 查询数据库中的所有表（含注释）
SELECT
  name AS table_name,
  '' AS table_comment
FROM sqlite_master
WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '__migratus_%'
ORDER BY name

-- :name search-tables :? :*
-- :doc 按名称模糊搜索表
SELECT
  name AS table_name,
  '' AS table_comment
FROM sqlite_master
WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '__migratus_%'
  AND name LIKE :pattern
ORDER BY name
LIMIT :limit OFFSET :offset

-- :name table-columns :? :*
-- :doc 查询指定表的列信息（含注释）
SELECT
  name AS column_name,
  type AS data_type,
  CASE WHEN "notnull" = 1 THEN 'NO' ELSE 'YES' END AS is_nullable,
  dflt_value AS column_default,
  '' AS column_comment,
  NULL AS character_maximum_length,
  NULL AS numeric_precision,
  NULL AS numeric_scale
FROM pragma_table_info(:table_name)
ORDER BY cid

-- :name table-primary-keys :? :*
-- :doc 查询指定表的主键列
SELECT name AS column_name
FROM pragma_table_info(:table_name)
WHERE pk = 1
ORDER BY cid
