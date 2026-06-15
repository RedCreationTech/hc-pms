DELETE FROM sys_role_menu WHERE menu_id = 21;
--;;
DELETE FROM sys_menu WHERE menu_id = 21;
--;;
UPDATE sys_menu
SET order_num = 10,
    update_time = CURRENT_TIMESTAMP
WHERE menu_id = 20;
