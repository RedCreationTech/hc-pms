-- down 与 up 同为版本号对齐占位, MySQL 侧无可回滚的数据修复.
UPDATE pms_lesson SET created_at = created_at WHERE 1 = 0;
