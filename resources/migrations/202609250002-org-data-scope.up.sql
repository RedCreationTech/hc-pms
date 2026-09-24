-- S2 组织与数据权限: 角色自定义数据权限部门, 部门负责人 (用户), 修正部门祖级路径.
CREATE TABLE sys_role_dept (
  role_id BIGINT NOT NULL,
  dept_id BIGINT NOT NULL,
  PRIMARY KEY (role_id, dept_id)
);
--;;
ALTER TABLE sys_dept ADD COLUMN leader_id BIGINT NULL;
--;;
UPDATE sys_dept d
JOIN (
  WITH RECURSIVE t (dept_id, anc) AS (
    SELECT dept_id, CAST('0' AS CHAR(255)) FROM sys_dept WHERE parent_id = 0
    UNION ALL
    SELECT c.dept_id, CONCAT(t.anc, ',', c.parent_id) FROM sys_dept c JOIN t ON c.parent_id = t.dept_id
  )
  SELECT dept_id, anc FROM t
) x ON x.dept_id = d.dept_id
SET d.ancestors = x.anc;
--;;
