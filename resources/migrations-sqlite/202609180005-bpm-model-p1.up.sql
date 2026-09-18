-- BPM P1：模型页体验对齐（SQLite）
-- icon              流程图标（上传 URL，空则显示名称前 2 字色块）
-- order_num         模型/分类排序号（拖拽排序保存）
-- start_user_ids    谁可发起-指定人员（JSON 用户 id 数组，空/null 表示不限制）
-- start_dept_ids    谁可发起-指定部门（JSON 部门 id 数组）
-- manager_user_ids  流程管理员（JSON 用户 id 数组，仅展示用）

ALTER TABLE biz_bpm_model ADD COLUMN icon TEXT NOT NULL DEFAULT '';
--;;
ALTER TABLE biz_bpm_model ADD COLUMN order_num INTEGER NOT NULL DEFAULT 0;
--;;
ALTER TABLE biz_bpm_model ADD COLUMN start_user_ids TEXT;
--;;
ALTER TABLE biz_bpm_model ADD COLUMN start_dept_ids TEXT;
--;;
ALTER TABLE biz_bpm_model ADD COLUMN manager_user_ids TEXT;
--;;
ALTER TABLE biz_bpm_category ADD COLUMN order_num INTEGER NOT NULL DEFAULT 0;
--;;
