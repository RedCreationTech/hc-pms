-- :name list-tables :? :*
-- :doc 查询数据库中的所有表
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name

-- :name table-columns :? :*
-- :doc 查询指定表的列信息
SELECT column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = :table_name
ORDER BY ordinal_position
