-- S2 组织与数据权限: 角色自定义数据权限部门, 部门负责人 (用户), 修正部门祖级路径.
CREATE TABLE sys_role_dept (
  role_id INTEGER NOT NULL,
  dept_id INTEGER NOT NULL,
  PRIMARY KEY (role_id, dept_id)
);
--;;
ALTER TABLE sys_dept ADD COLUMN leader_id INTEGER;
--;;
WITH RECURSIVE t(dept_id, anc) AS (
  SELECT dept_id, '0' FROM sys_dept WHERE parent_id = 0
  UNION ALL
  SELECT d.dept_id, t.anc || ',' || d.parent_id FROM sys_dept d JOIN t ON d.parent_id = t.dept_id
)
UPDATE sys_dept SET ancestors = (SELECT anc FROM t WHERE t.dept_id = sys_dept.dept_id)
WHERE dept_id IN (SELECT dept_id FROM t);
--;;
