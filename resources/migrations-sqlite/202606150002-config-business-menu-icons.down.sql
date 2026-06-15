UPDATE sys_menu
SET icon = CASE menu_id
  WHEN 1999 THEN 'dashboard'
  WHEN 2000 THEN 'dashboard'
  WHEN 2100 THEN 'project'
  WHEN 2200 THEN 'resource'
  WHEN 2201 THEN 'file-text'
  WHEN 2202 THEN 'database'
  WHEN 2203 THEN 'tree'
  WHEN 2204 THEN 'star'
  WHEN 2205 THEN 'picture'
  ELSE icon
END,
update_time = CURRENT_TIMESTAMP
WHERE menu_id IN (1999, 2000, 2100, 2200, 2201, 2202, 2203, 2204, 2205);
