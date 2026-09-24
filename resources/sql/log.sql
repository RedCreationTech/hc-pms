-- :name list-oper-logs :? :*
SELECT * FROM sys_oper_log WHERE 1=1
  AND (:title IS NULL OR INSTR(title, :title) > 0)
  AND (:oper_name IS NULL OR INSTR(oper_name, :oper_name) > 0)
  AND (:oper_ip IS NULL OR INSTR(oper_ip, :oper_ip) > 0)
  AND (:business_type IS NULL OR business_type = :business_type)
  AND (:status IS NULL OR status = :status)
  AND (:begin_time IS NULL OR oper_time >= :begin_time)
  AND (:end_time IS NULL OR oper_time <= :end_time)
ORDER BY oper_time DESC
LIMIT :page_size OFFSET :offset

-- :name count-oper-logs :? :1
SELECT COUNT(*) AS total FROM sys_oper_log WHERE 1=1
  AND (:title IS NULL OR INSTR(title, :title) > 0)
  AND (:oper_name IS NULL OR INSTR(oper_name, :oper_name) > 0)
  AND (:oper_ip IS NULL OR INSTR(oper_ip, :oper_ip) > 0)
  AND (:business_type IS NULL OR business_type = :business_type)
  AND (:status IS NULL OR status = :status)
  AND (:begin_time IS NULL OR oper_time >= :begin_time)
  AND (:end_time IS NULL OR oper_time <= :end_time)

-- :name create-oper-log! :! :n
INSERT INTO sys_oper_log (title, business_type, method, request_method, operator_type, oper_name, dept_name, oper_url, oper_ip, oper_location, oper_param, json_result, status, error_msg, oper_time, cost_time)
VALUES (:title, :business_type, :method, :request_method, :operator_type, :oper_name, :dept_name, :oper_url, :oper_ip, :oper_location, :oper_param, :json_result, :status, :error_msg, CURRENT_TIMESTAMP, :cost_time)

-- :name clear-oper-logs! :! :n
DELETE FROM sys_oper_log WHERE 1=1
  AND (:begin_time IS NULL OR oper_time >= :begin_time)
  AND (:end_time IS NULL OR oper_time <= :end_time)

-- :name delete-oper-log! :! :n
DELETE FROM sys_oper_log WHERE oper_id = :oper_id

-- :name list-login-logs :? :*
SELECT * FROM sys_login_log WHERE 1=1
  AND (:user_name IS NULL OR INSTR(user_name, :user_name) > 0)
  AND (:ipaddr IS NULL OR INSTR(ipaddr, :ipaddr) > 0)
  AND (:status IS NULL OR status = :status)
  AND (:begin_time IS NULL OR login_time >= :begin_time)
  AND (:end_time IS NULL OR login_time <= :end_time)
ORDER BY login_time DESC
LIMIT :page_size OFFSET :offset

-- :name count-login-logs :? :1
SELECT COUNT(*) AS total FROM sys_login_log WHERE 1=1
  AND (:user_name IS NULL OR INSTR(user_name, :user_name) > 0)
  AND (:ipaddr IS NULL OR INSTR(ipaddr, :ipaddr) > 0)
  AND (:status IS NULL OR status = :status)
  AND (:begin_time IS NULL OR login_time >= :begin_time)
  AND (:end_time IS NULL OR login_time <= :end_time)

-- :name create-login-log! :! :n
INSERT INTO sys_login_log (user_name, ipaddr, login_location, browser, os, status, msg, login_time)
VALUES (:user_name, :ipaddr, :login_location, :browser, :os, :status, :msg, CURRENT_TIMESTAMP)

-- :name clear-login-logs! :! :n
DELETE FROM sys_login_log WHERE 1=1
  AND (:begin_time IS NULL OR login_time >= :begin_time)
  AND (:end_time IS NULL OR login_time <= :end_time)

-- :name delete-login-log! :! :n
DELETE FROM sys_login_log WHERE info_id = :info_id

-- :name list-online-users :? :*
SELECT * FROM sys_online WHERE 1=1
  AND (:ipaddr IS NULL OR INSTR(ipaddr, :ipaddr) > 0)
  AND (:login_name IS NULL OR INSTR(login_name, :login_name) > 0)
ORDER BY last_access_time DESC
LIMIT :page_size OFFSET :offset

-- :name count-online-users :? :1
SELECT COUNT(*) AS total FROM sys_online WHERE 1=1
  AND (:ipaddr IS NULL OR INSTR(ipaddr, :ipaddr) > 0)
  AND (:login_name IS NULL OR INSTR(login_name, :login_name) > 0)

-- :name create-online-user! :! :n
INSERT INTO sys_online (session_id, login_name, dept_name, ipaddr, login_location, browser, os, status, start_timestamp, last_access_time, expire_time)
VALUES (:session_id, :login_name, :dept_name, :ipaddr, :login_location, :browser, :os, :status, :start_timestamp, :last_access_time, :expire_time)

-- :name update-online-user! :! :n
UPDATE sys_online
SET last_access_time = :last_access_time,
    status = COALESCE(:status, status),
    expire_time = COALESCE(:expire_time, expire_time)
WHERE session_id = :session_id

-- :name delete-online-user! :! :n
DELETE FROM sys_online WHERE session_id = :session_id

-- :name find-online-user-by-session :? :1
SELECT * FROM sys_online WHERE session_id = :session_id

-- :name delete-online-users-by-name! :! :n
-- :doc 删除某用户的全部在线记录 (停用, 删除, 重置密码时)
DELETE FROM sys_online WHERE login_name = :login_name

-- :name list-token-revokes :? :*
-- :doc 未过期的令牌撤销记录
SELECT revoke_key, revoked_at, expires_at FROM sys_token_revoke

-- :name insert-token-revoke! :! :n
INSERT INTO sys_token_revoke (revoke_key, revoked_at, expires_at) VALUES (:revoke_key, :revoked_at, :expires_at)

-- :name delete-token-revoke! :! :n
DELETE FROM sys_token_revoke WHERE revoke_key = :revoke_key

-- :name delete-expired-token-revokes! :! :n
DELETE FROM sys_token_revoke WHERE expires_at < :now
