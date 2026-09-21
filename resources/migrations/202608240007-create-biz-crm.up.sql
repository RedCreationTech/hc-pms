-- 办公一体化 · CRM 客户管理(MySQL)
CREATE TABLE IF NOT EXISTS biz_crm_customer (
  customer_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
  name         VARCHAR(255) NOT NULL DEFAULT '',
  phone        VARCHAR(32) NOT NULL DEFAULT '',
  email        VARCHAR(128) NOT NULL DEFAULT '',
  company      VARCHAR(255) NOT NULL DEFAULT '',
  level        CHAR(1) NOT NULL DEFAULT '1',
  source       VARCHAR(64) NOT NULL DEFAULT '',
  owner_id     BIGINT DEFAULT 0,
  status       CHAR(1) NOT NULL DEFAULT '1',
  remark       VARCHAR(500) NOT NULL DEFAULT '',
  create_by    VARCHAR(64) NOT NULL DEFAULT '',
  create_time  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  update_by    VARCHAR(64) NOT NULL DEFAULT '',
  update_time  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_crm_customer_name ON biz_crm_customer(name);
--;;
CREATE INDEX idx_crm_customer_owner ON biz_crm_customer(owner_id);
