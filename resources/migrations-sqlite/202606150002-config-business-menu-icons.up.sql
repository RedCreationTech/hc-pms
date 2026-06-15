UPDATE sys_menu
SET icon = CASE menu_id
  WHEN 1999 THEN 'DashboardOutlined'
  WHEN 2000 THEN 'ProfileOutlined'
  WHEN 2100 THEN 'ProjectOutlined'
  WHEN 2200 THEN 'FolderOpenOutlined'
  WHEN 2201 THEN 'FileTextOutlined'
  WHEN 2202 THEN 'DatabaseOutlined'
  WHEN 2203 THEN 'ApartmentOutlined'
  WHEN 2204 THEN 'StarOutlined'
  WHEN 2205 THEN 'PictureOutlined'
  ELSE icon
END,
update_time = CURRENT_TIMESTAMP
WHERE menu_id IN (1999, 2000, 2100, 2200, 2201, 2202, 2203, 2204, 2205);
