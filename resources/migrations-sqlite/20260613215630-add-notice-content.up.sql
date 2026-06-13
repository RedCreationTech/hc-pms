-- 为通知公告表增加内容字段
ALTER TABLE sys_notice ADD COLUMN notice_content TEXT NOT NULL DEFAULT '';
