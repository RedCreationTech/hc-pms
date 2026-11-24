-- SQLite 侧对应 up 迁移无实际改动, 回退同样为空操作.
UPDATE biz_bpm_instance SET status = status WHERE 0;
--;;
