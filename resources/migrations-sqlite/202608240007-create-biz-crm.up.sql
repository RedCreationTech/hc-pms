-- 办公一体化 · CRM 客户管理(SQLite)
CREATE TABLE IF NOT EXISTS biz_crm_customer (
  customer_id   INTEGER PRIMARY KEY AUTOINCREMENT,
  name          TEXT NOT NULL DEFAULT '',
  phone         TEXT NOT NULL DEFAULT '',
  email         TEXT NOT NULL DEFAULT '',
  company       TEXT NOT NULL DEFAULT '',
  level         TEXT NOT NULL DEFAULT '1',
  source        TEXT NOT NULL DEFAULT '',
  owner_id      INTEGER DEFAULT 0,
  status        TEXT NOT NULL DEFAULT '1',
  remark        TEXT NOT NULL DEFAULT '',
  create_by     TEXT NOT NULL DEFAULT '',
  create_time   TEXT,
  update_by     TEXT NOT NULL DEFAULT '',
  update_time   TEXT
);
--;;
CREATE INDEX IF NOT EXISTS idx_crm_customer_name ON biz_crm_customer(name);
--;;
CREATE INDEX IF NOT EXISTS idx_crm_customer_owner ON biz_crm_customer(owner_id);
