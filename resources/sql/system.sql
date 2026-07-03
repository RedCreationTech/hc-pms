-- :name list-users :? :*
-- :doc 查询用户列表，支持用户名、手机号、状态筛选
SELECT u.user_id, u.dept_id, u.user_name, u.nick_name, u.user_type, u.email,
       u.phonenumber, u.sex, u.avatar, u.password, u.status, u.del_flag,
       u.login_ip, u.login_date, u.create_by, u.create_time, u.update_by,
       u.update_time, u.remark, d.dept_name
FROM sys_user u
LEFT JOIN sys_dept d ON u.dept_id = d.dept_id
WHERE u.del_flag = '0'
  AND (:user_name IS NULL OR INSTR(u.user_name, :user_name) > 0)
  AND (:phonenumber IS NULL OR INSTR(u.phonenumber, :phonenumber) > 0)
  AND (:status IS NULL OR u.status = :status)
  AND (:dept_id IS NULL OR u.dept_id = :dept_id)
ORDER BY u.user_id
LIMIT :page_size OFFSET :offset

-- :name count-users :? :1
-- :doc 统计用户数量
SELECT COUNT(*) AS total
FROM sys_user u
WHERE u.del_flag = '0'
  AND (:user_name IS NULL OR INSTR(u.user_name, :user_name) > 0)
  AND (:phonenumber IS NULL OR INSTR(u.phonenumber, :phonenumber) > 0)
  AND (:status IS NULL OR u.status = :status)
  AND (:dept_id IS NULL OR u.dept_id = :dept_id)

-- :name find-user-by-id :? :1
-- :doc 根据ID查询用户
SELECT *
FROM sys_user
WHERE user_id = :user_id AND del_flag = '0'

-- :name find-user-by-name :? :1
-- :doc 根据用户名查询用户
SELECT * FROM sys_user WHERE user_name = :user_name AND del_flag = '0'

-- :name create-user! :! :n
-- :doc 新增用户
INSERT INTO sys_user (dept_id, user_name, nick_name, user_type, email, phonenumber, sex, avatar, password, status, del_flag, create_by, create_time, remark)
VALUES (:dept_id, :user_name, :nick_name, :user_type, :email, :phonenumber, :sex, :avatar, :password, :status, '0', :create_by, CURRENT_TIMESTAMP, :remark)

-- :name update-user! :! :n
-- :doc 更新用户信息
UPDATE sys_user
SET dept_id = COALESCE(:dept_id, dept_id),
    nick_name = COALESCE(:nick_name, nick_name),
    user_type = COALESCE(:user_type, user_type),
    email = COALESCE(:email, email),
    phonenumber = COALESCE(:phonenumber, phonenumber),
    sex = COALESCE(:sex, sex),
    avatar = COALESCE(:avatar, avatar),
    password = COALESCE(:password, password),
    status = COALESCE(:status, status),
    update_by = :update_by,
    update_time = CURRENT_TIMESTAMP,
    remark = COALESCE(:remark, remark)
WHERE user_id = :user_id

-- :name delete-user! :! :n
-- :doc 逻辑删除用户
UPDATE sys_user SET del_flag = '2', update_time = CURRENT_TIMESTAMP WHERE user_id = :user_id

-- :name list-roles-by-user-id :? :*
-- :doc 查询用户的角色列表
SELECT r.role_id, r.role_name, r.role_key, r.role_sort, r.data_scope,
       r.menu_check_strictly, r.dept_check_strictly, r.status, r.del_flag,
       r.create_by, r.create_time, r.update_by, r.update_time, r.remark
FROM sys_role r
INNER JOIN sys_user_role ur ON r.role_id = ur.role_id
WHERE ur.user_id = :user_id AND r.del_flag = '0'

-- :name list-posts-by-user-id :? :*
-- :doc 查询用户的岗位列表
SELECT p.post_id, p.post_code, p.post_name, p.post_sort, p.status,
       p.create_by, p.create_time, p.update_by, p.update_time, p.remark
FROM sys_post p
INNER JOIN sys_user_post up ON p.post_id = up.post_id
WHERE up.user_id = :user_id

-- :name insert-user-role! :! :n
-- :doc 插入用户角色关联
INSERT INTO sys_user_role (user_id, role_id) VALUES (:user_id, :role_id)

-- :name delete-user-roles! :! :n
-- :doc 删除用户角色关联
DELETE FROM sys_user_role WHERE user_id = :user_id

-- :name insert-user-post! :! :n
-- :doc 插入用户岗位关联
INSERT INTO sys_user_post (user_id, post_id) VALUES (:user_id, :post_id)

-- :name delete-user-posts! :! :n
-- :doc 删除用户岗位关联
DELETE FROM sys_user_post WHERE user_id = :user_id

-- :name list-depts :? :*
-- :doc 查询部门列表
SELECT * FROM sys_dept WHERE del_flag = '0'
  AND (:status IS NULL OR status = :status)
  AND (:dept_name IS NULL OR INSTR(dept_name, :dept_name) > 0)
ORDER BY parent_id, order_num

-- :name find-dept-by-id :? :1
SELECT * FROM sys_dept WHERE dept_id = :dept_id AND del_flag = '0'

-- :name create-dept! :! :n
INSERT INTO sys_dept (parent_id, ancestors, dept_name, order_num, leader, phone, email, status, create_by, create_time)
VALUES (:parent_id, :ancestors, :dept_name, :order_num, :leader, :phone, :email, :status, :create_by, CURRENT_TIMESTAMP)

-- :name update-dept! :! :n
UPDATE sys_dept
SET parent_id = COALESCE(:parent_id, parent_id),
    ancestors = COALESCE(:ancestors, ancestors),
    dept_name = COALESCE(:dept_name, dept_name),
    order_num = COALESCE(:order_num, order_num),
    leader = COALESCE(:leader, leader),
    phone = COALESCE(:phone, phone),
    email = COALESCE(:email, email),
    status = COALESCE(:status, status),
    update_by = :update_by,
    update_time = CURRENT_TIMESTAMP
WHERE dept_id = :dept_id

-- :name list-depts-by-parent :? :*
SELECT * FROM sys_dept WHERE parent_id = :parent_id AND del_flag = '0'

-- :name update-dept-ancestors! :! :n
UPDATE sys_dept SET ancestors = :ancestors WHERE dept_id = :dept_id

-- :name delete-dept! :! :n
UPDATE sys_dept SET del_flag = '2', update_time = CURRENT_TIMESTAMP WHERE dept_id = :dept_id

-- :name list-roles :? :*
SELECT * FROM sys_role WHERE del_flag = '0'
  AND (:role_name IS NULL OR INSTR(role_name, :role_name) > 0)
  AND (:role_key IS NULL OR role_key = :role_key)
  AND (:status IS NULL OR status = :status)
ORDER BY role_sort

-- :name find-role-by-id :? :1
SELECT * FROM sys_role WHERE role_id = :role_id AND del_flag = '0'

-- :name create-role! :! :n
INSERT INTO sys_role (role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, create_by, create_time, remark)
VALUES (:role_name, :role_key, :role_sort, :data_scope, :menu_check_strictly, :dept_check_strictly, :status, :create_by, CURRENT_TIMESTAMP, :remark)

-- :name update-role! :! :n
-- :doc 更新角色（所有字段可选）
UPDATE sys_role
SET role_name = COALESCE(:role_name, role_name),
    role_key = COALESCE(:role_key, role_key),
    role_sort = COALESCE(:role_sort, role_sort),
    data_scope = COALESCE(:data_scope, data_scope),
    menu_check_strictly = COALESCE(:menu_check_strictly, menu_check_strictly),
    dept_check_strictly = COALESCE(:dept_check_strictly, dept_check_strictly),
    status = COALESCE(:status, status),
    update_time = CURRENT_TIMESTAMP,
    remark = COALESCE(:remark, remark)
WHERE role_id = :role_id

-- :name delete-role! :! :n
UPDATE sys_role SET del_flag = '2', update_time = CURRENT_TIMESTAMP WHERE role_id = :role_id

-- :name list-menus :? :*
SELECT * FROM sys_menu
WHERE (:menu_name IS NULL OR INSTR(menu_name, :menu_name) > 0)
  AND (:status IS NULL OR status = :status)
  AND (:menu_type IS NULL OR menu_type = :menu_type)
ORDER BY parent_id, order_num

-- :name find-menu-by-id :? :1
SELECT * FROM sys_menu WHERE menu_id = :menu_id

-- :name create-menu! :! :n
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
VALUES (:menu_name, :parent_id, :order_num, :path, :component, :query, :route_name, :is_frame, :is_cache, :menu_type, :visible, :status, :perms, :icon, :create_by, CURRENT_TIMESTAMP)

-- :name update-menu! :! :n
UPDATE sys_menu
SET menu_name = COALESCE(:menu_name, menu_name),
    parent_id = COALESCE(:parent_id, parent_id),
    order_num = COALESCE(:order_num, order_num),
    path = COALESCE(:path, path),
    component = COALESCE(:component, component),
    query = COALESCE(:query, query),
    route_name = COALESCE(:route_name, route_name),
    is_frame = COALESCE(:is_frame, is_frame),
    is_cache = COALESCE(:is_cache, is_cache),
    menu_type = COALESCE(:menu_type, menu_type),
    visible = COALESCE(:visible, visible),
    status = COALESCE(:status, status),
    perms = COALESCE(:perms, perms),
    icon = COALESCE(:icon, icon),
    update_by = :update_by,
    update_time = CURRENT_TIMESTAMP
WHERE menu_id = :menu_id

-- :name update-menu-order! :! :n
UPDATE sys_menu SET order_num = :order_num, update_time = CURRENT_TIMESTAMP WHERE menu_id = :menu_id

-- :name delete-menu! :! :n
DELETE FROM sys_menu WHERE menu_id = :menu_id

-- :name list-menus-by-role-id :? :*
SELECT m.menu_id, m.menu_name, m.parent_id, m.order_num, m.path,
       m.component, m.query, m.route_name, m.is_frame, m.is_cache,
       m.menu_type, m.visible, m.status, m.perms, m.icon,
       m.create_by, m.create_time, m.update_by, m.update_time, m.remark
FROM sys_menu m
INNER JOIN sys_role_menu rm ON m.menu_id = rm.menu_id
WHERE rm.role_id = :role_id

-- :name delete-role-menus! :! :n
DELETE FROM sys_role_menu WHERE role_id = :role_id

-- :name insert-role-menu! :! :n
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (:role_id, :menu_id)

-- :name list-posts :? :*
SELECT * FROM sys_post WHERE 1=1
  AND (:post_code IS NULL OR INSTR(post_code, :post_code) > 0)
  AND (:post_name IS NULL OR INSTR(post_name, :post_name) > 0)
  AND (:status IS NULL OR status = :status)
ORDER BY post_sort

-- :name find-post-by-id :? :1
SELECT * FROM sys_post WHERE post_id = :post_id

-- :name create-post! :! :n
INSERT INTO sys_post (post_code, post_name, post_sort, status, create_by, create_time, remark)
VALUES (:post_code, :post_name, :post_sort, :status, :create_by, CURRENT_TIMESTAMP, :remark)

-- :name update-post! :! :n
UPDATE sys_post
SET post_code = COALESCE(:post_code, post_code),
    post_name = COALESCE(:post_name, post_name),
    post_sort = COALESCE(:post_sort, post_sort),
    status = COALESCE(:status, status),
    update_by = :update_by,
    update_time = CURRENT_TIMESTAMP,
    remark = COALESCE(:remark, remark)
WHERE post_id = :post_id

-- :name delete-post! :! :n
DELETE FROM sys_post WHERE post_id = :post_id

-- :name list-dict-types :? :*
SELECT * FROM sys_dict_type WHERE 1=1
  AND (:dict_name IS NULL OR INSTR(dict_name, :dict_name) > 0)
  AND (:dict_type IS NULL OR INSTR(dict_type, :dict_type) > 0)
  AND (:status IS NULL OR status = :status)
ORDER BY dict_id

-- :name find-dict-type-by-id :? :1
SELECT * FROM sys_dict_type WHERE dict_id = :dict_id

-- :name create-dict-type! :! :n
INSERT INTO sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
VALUES (:dict_name, :dict_type, :status, :create_by, CURRENT_TIMESTAMP, :remark)

-- :name update-dict-type! :! :n
UPDATE sys_dict_type
SET dict_name = COALESCE(:dict_name, dict_name),
    dict_type = COALESCE(:dict_type, dict_type),
    status = COALESCE(:status, status),
    update_by = :update_by,
    update_time = CURRENT_TIMESTAMP,
    remark = COALESCE(:remark, remark)
WHERE dict_id = :dict_id

-- :name delete-dict-type! :! :n
DELETE FROM sys_dict_type WHERE dict_id = :dict_id

-- :name list-dict-data :? :*
SELECT * FROM sys_dict_data WHERE 1=1
  AND (:dict_type IS NULL OR dict_type = :dict_type)
  AND (:dict_label IS NULL OR INSTR(dict_label, :dict_label) > 0)
  AND (:status IS NULL OR status = :status)
ORDER BY dict_sort

-- :name find-dict-data-by-id :? :1
SELECT * FROM sys_dict_data WHERE dict_code = :dict_code

-- :name create-dict-data! :! :n
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
VALUES (:dict_sort, :dict_label, :dict_value, :dict_type, :css_class, :list_class, :is_default, :status, :create_by, CURRENT_TIMESTAMP, :remark)

-- :name update-dict-data! :! :n
UPDATE sys_dict_data
SET dict_sort = COALESCE(:dict_sort, dict_sort),
    dict_label = COALESCE(:dict_label, dict_label),
    dict_value = COALESCE(:dict_value, dict_value),
    dict_type = COALESCE(:dict_type, dict_type),
    css_class = COALESCE(:css_class, css_class),
    list_class = COALESCE(:list_class, list_class),
    is_default = COALESCE(:is_default, is_default),
    status = COALESCE(:status, status),
    update_by = :update_by,
    update_time = CURRENT_TIMESTAMP,
    remark = COALESCE(:remark, remark)
WHERE dict_code = :dict_code

-- :name delete-dict-data! :! :n
DELETE FROM sys_dict_data WHERE dict_code = :dict_code

-- :name list-configs :? :*
SELECT * FROM sys_config WHERE 1=1
  AND (:config_name IS NULL OR INSTR(config_name, :config_name) > 0)
  AND (:config_key IS NULL OR INSTR(config_key, :config_key) > 0)
  AND (:config_type IS NULL OR config_type = :config_type)
ORDER BY config_id

-- :name find-config-by-id :? :1
SELECT * FROM sys_config WHERE config_id = :config_id

-- :name find-config-by-key :? :1
SELECT * FROM sys_config WHERE config_key = :config_key

-- :name create-config! :! :n
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
VALUES (:config_name, :config_key, :config_value, :config_type, :create_by, CURRENT_TIMESTAMP, :remark)

-- :name update-config! :! :n
UPDATE sys_config
SET config_name = COALESCE(:config_name, config_name),
    config_key = COALESCE(:config_key, config_key),
    config_value = COALESCE(:config_value, config_value),
    config_type = COALESCE(:config_type, config_type),
    update_by = :update_by,
    update_time = CURRENT_TIMESTAMP,
    remark = COALESCE(:remark, remark)
WHERE config_id = :config_id

-- :name delete-config! :! :n
DELETE FROM sys_config WHERE config_id = :config_id

-- :name datasource-info :? :1
-- :doc 获取数据库连接信息
SELECT current_database() AS db_name, version() AS db_version,
       (SELECT count(*) FROM pg_stat_activity) AS active_connections

-- ════════════════════════════════════════════════════════════════
-- 通知公告
-- ════════════════════════════════════════════════════════════════

-- :name list-notices :? :*
-- :doc 查询通知公告列表
SELECT * FROM sys_notice
WHERE (:notice_name IS NULL OR INSTR(notice_name, :notice_name) > 0)
  AND (:notice_type IS NULL OR notice_type = :notice_type)
  AND (:create_by IS NULL OR INSTR(create_by, :create_by) > 0)
ORDER BY notice_id DESC
LIMIT :page_size OFFSET :offset

-- :name count-notices :? :1
-- :doc 统计通知公告数量
SELECT COUNT(*) AS total FROM sys_notice
WHERE (:notice_name IS NULL OR INSTR(notice_name, :notice_name) > 0)
  AND (:notice_type IS NULL OR notice_type = :notice_type)
  AND (:create_by IS NULL OR INSTR(create_by, :create_by) > 0)

-- :name find-notice-by-id :? :1
-- :doc 根据ID查询通知公告
SELECT * FROM sys_notice WHERE notice_id = :notice_id

-- :name create-notice! :! :n
-- :doc 新增通知公告
INSERT INTO sys_notice (notice_name, notice_type, status, create_by, create_time, notice_content, remark)
VALUES (:notice_name, :notice_type, :status, :create_by, CURRENT_TIMESTAMP, :notice_content, :remark)

-- :name update-notice! :! :n
-- :doc 更新通知公告
UPDATE sys_notice
SET notice_name = COALESCE(:notice_name, notice_name),
    notice_type = COALESCE(:notice_type, notice_type),
    status = COALESCE(:status, status),
    notice_content = COALESCE(:notice_content, notice_content),
    update_by = :update_by,
    update_time = CURRENT_TIMESTAMP,
    remark = COALESCE(:remark, remark)
WHERE notice_id = :notice_id

-- :name delete-notice! :! :n
-- :doc 删除通知公告
DELETE FROM sys_notice WHERE notice_id = :notice_id

-- :name last-insert-rowid :? :1
-- :doc 获取最后插入的行ID (SQLite)
SELECT last_insert_rowid() AS last_insert_rowid

-- :name last-insert-rowid-mysql :? :1
-- :doc 获取最后插入的行ID (MySQL)
SELECT LAST_INSERT_ID() AS last_insert_rowid

-- :name list-users-by-role :? :*
-- :doc 查询已分配某角色的用户
SELECT u.* FROM sys_user u
INNER JOIN sys_user_role ur ON u.user_id = ur.user_id
WHERE ur.role_id = :role_id
  AND u.del_flag = '0'
  AND (:user_name IS NULL OR INSTR(u.user_name, :user_name) > 0)
  AND (:phonenumber IS NULL OR INSTR(u.phonenumber, :phonenumber) > 0)
ORDER BY u.create_time DESC

-- :name list-users-not-in-role :? :*
-- :doc 查询未分配某角色的用户
SELECT u.* FROM sys_user u
WHERE u.del_flag = '0'
  AND u.user_id NOT IN (SELECT user_id FROM sys_user_role WHERE role_id = :role_id)
  AND (:user_name IS NULL OR INSTR(u.user_name, :user_name) > 0)
  AND (:phonenumber IS NULL OR INSTR(u.phonenumber, :phonenumber) > 0)
ORDER BY u.create_time DESC

-- :name delete-user-role! :! :n
-- :doc 删除用户角色关联
DELETE FROM sys_user_role WHERE role_id = :role_id AND user_id = :user_id

-- :name insert-user-role! :! :n
-- :doc 插入用户角色关联
INSERT INTO sys_user_role (user_id, role_id) VALUES (:user_id, :role_id)

-- :name delete-user-roles! :! :n
DELETE FROM sys_user_role WHERE user_id = :user_id

-- :name list-menus-by-role-ids :? :*
-- :doc 根据角色ID列表查询菜单（包含父菜单）
SELECT DISTINCT m.menu_id, m.menu_name, m.parent_id, m.order_num, m.path,
       m.component, m.query, m.route_name, m.is_frame, m.is_cache,
       m.menu_type, m.visible, m.status, m.perms, m.icon,
       m.create_by, m.create_time, m.update_by, m.update_time, m.remark
FROM sys_menu m
WHERE m.menu_id IN (
  -- 直接分配的菜单
  SELECT rm.menu_id FROM sys_role_menu rm WHERE rm.role_id IN (:v*:role-ids)
  UNION
  -- 父菜单
  SELECT DISTINCT m2.parent_id FROM sys_menu m2
  INNER JOIN sys_role_menu rm2 ON m2.menu_id = rm2.menu_id
  WHERE rm2.role_id IN (:v*:role-ids) AND m2.parent_id > 0
)
ORDER BY m.parent_id, m.order_num

-- ════════════════════════════════════════════════════════════════
-- 表单模板
-- ════════════════════════════════════════════════════════════════

-- :name list-form-templates :? :*
-- :doc 查询表单模板列表
SELECT * FROM sys_form_template WHERE 1=1
  AND (:form_name IS NULL OR INSTR(form_name, :form_name) > 0)
  AND (:form_key IS NULL OR INSTR(form_key, :form_key) > 0)
ORDER BY id DESC

-- :name find-form-template-by-id :? :1
-- :doc 根据ID查询表单模板
SELECT * FROM sys_form_template WHERE id = :id

-- :name find-form-template-by-key :? :1
-- :doc 根据form_key查询表单模板
SELECT * FROM sys_form_template WHERE form_key = :form_key

-- :name create-form-template! :! :n
-- :doc 新增表单模板
INSERT INTO sys_form_template (form_name, form_key, schema_json, remark, create_by, create_time)
VALUES (:form_name, :form_key, :schema_json, :remark, :create_by, CURRENT_TIMESTAMP)

-- :name update-form-template! :! :n
-- :doc 更新表单模板
UPDATE sys_form_template
SET form_name   = COALESCE(:form_name, form_name),
    form_key    = COALESCE(:form_key, form_key),
    schema_json = COALESCE(:schema_json, schema_json),
    remark      = COALESCE(:remark, remark),
    update_by   = :update_by,
    update_time = CURRENT_TIMESTAMP
WHERE id = :id

-- :name delete-form-template! :! :n
-- :doc 删除表单模板
DELETE FROM sys_form_template WHERE id = :id
