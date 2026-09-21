-- 清理方案智能编制模块:删除所有业务表和菜单数据
-- Clean up smart-solution module: drop all biz tables and menu entries

DELETE FROM sys_role_menu WHERE menu_id IN (1999, 2000, 2100, 2101, 2200, 2201, 2202, 2203, 2204, 2205);
--;;
DELETE FROM sys_menu WHERE menu_id IN (1999, 2000, 2100, 2101, 2200, 2201, 2202, 2203, 2204, 2205);
--;;
DROP TABLE IF EXISTS biz_solution_file;
--;;
DROP TABLE IF EXISTS biz_chat_message;
--;;
DROP TABLE IF EXISTS biz_chat_session;
--;;
DROP TABLE IF EXISTS biz_resource_relation;
--;;
DROP TABLE IF EXISTS biz_solution_status;
--;;
DROP TABLE IF EXISTS biz_gallery_item;
--;;
DROP TABLE IF EXISTS biz_attachment;
--;;
DROP TABLE IF EXISTS biz_resource;
--;;
DROP TABLE IF EXISTS biz_solution_section;
--;;
DROP TABLE IF EXISTS biz_solution;
--;;
DROP TABLE IF EXISTS biz_subcontract_team;
--;;
DROP TABLE IF EXISTS biz_project;
--;;
DROP TABLE IF EXISTS biz_engineering;
