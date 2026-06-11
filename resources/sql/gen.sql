-- :name list-tables :? :*
-- :doc 查询数据库中的所有表（含注释）
SELECT
  t.table_name,
  pg_catalog.obj_description(pgc.oid, 'pg_class') AS table_comment
FROM information_schema.tables t
JOIN pg_catalog.pg_class pgc ON pgc.relname = t.table_name
WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE'
ORDER BY t.table_name

-- :name search-tables :? :*
-- :doc 按名称模糊搜索表
SELECT
  t.table_name,
  pg_catalog.obj_description(pgc.oid, 'pg_class') AS table_comment
FROM information_schema.tables t
JOIN pg_catalog.pg_class pgc ON pgc.relname = t.table_name
WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE'
  AND t.table_name LIKE :pattern
ORDER BY t.table_name
LIMIT :limit OFFSET :offset

-- :name table-columns :? :*
-- :doc 查询指定表的列信息（含注释）
SELECT
  c.column_name,
  c.data_type,
  c.is_nullable,
  c.column_default,
  pgd.description AS column_comment,
  c.character_maximum_length,
  c.numeric_precision,
  c.numeric_scale
FROM information_schema.columns c
LEFT JOIN pg_catalog.pg_description pgd
  ON pgd.objsubid = c.ordinal_position
  AND pgd.objoid = (SELECT pgc.oid FROM pg_catalog.pg_class pgc
                    WHERE pgc.relname = c.table_name)
WHERE c.table_schema = 'public' AND c.table_name = :table_name
ORDER BY c.ordinal_position

-- :name table-primary-keys :? :*
-- :doc 查询指定表的主键列
SELECT kcu.column_name
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
  ON tc.constraint_name = kcu.constraint_name
  AND tc.table_schema = kcu.table_schema
WHERE tc.table_schema = 'public'
  AND tc.table_name = :table_name
  AND tc.constraint_type = 'PRIMARY KEY'
ORDER BY kcu.ordinal_position
