-- 办公一体化 · HRM 人力资源（SQLite）
CREATE TABLE IF NOT EXISTS biz_hrm_employee (
  employee_id  INTEGER PRIMARY KEY AUTOINCREMENT,
  emp_no       TEXT NOT NULL DEFAULT '',
  name         TEXT NOT NULL DEFAULT '',
  dept_id      INTEGER DEFAULT 0,
  post_id      INTEGER DEFAULT 0,
  gender       TEXT NOT NULL DEFAULT '0',
  phone        TEXT NOT NULL DEFAULT '',
  email        TEXT NOT NULL DEFAULT '',
  id_card      TEXT NOT NULL DEFAULT '',
  hire_date    TEXT,
  status       TEXT NOT NULL DEFAULT '1',
  salary_base  INTEGER DEFAULT 0,
  remark       TEXT NOT NULL DEFAULT '',
  create_by    TEXT NOT NULL DEFAULT '',
  create_time  TEXT,
  update_by    TEXT NOT NULL DEFAULT '',
  update_time  TEXT
);
--;;
CREATE INDEX IF NOT EXISTS idx_hrm_emp_no ON biz_hrm_employee(emp_no);
--;;
CREATE INDEX IF NOT EXISTS idx_hrm_emp_dept ON biz_hrm_employee(dept_id);
