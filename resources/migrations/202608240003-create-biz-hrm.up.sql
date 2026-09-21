-- 办公一体化 · HRM 人力资源(MySQL)
CREATE TABLE IF NOT EXISTS biz_hrm_employee (
  employee_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
  emp_no       VARCHAR(64) NOT NULL DEFAULT '',
  name         VARCHAR(64) NOT NULL DEFAULT '',
  dept_id      BIGINT DEFAULT 0,
  post_id      BIGINT DEFAULT 0,
  gender       CHAR(1) NOT NULL DEFAULT '0',
  phone        VARCHAR(32) NOT NULL DEFAULT '',
  email        VARCHAR(128) NOT NULL DEFAULT '',
  id_card      VARCHAR(32) NOT NULL DEFAULT '',
  hire_date    DATE NULL,
  status       CHAR(1) NOT NULL DEFAULT '1',
  salary_base  DECIMAL(12,2) DEFAULT 0,
  remark       VARCHAR(500) NOT NULL DEFAULT '',
  create_by    VARCHAR(64) NOT NULL DEFAULT '',
  create_time  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  update_by    VARCHAR(64) NOT NULL DEFAULT '',
  update_time  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--;;
CREATE INDEX idx_hrm_emp_no ON biz_hrm_employee(emp_no);
--;;
CREATE INDEX idx_hrm_emp_dept ON biz_hrm_employee(dept_id);
