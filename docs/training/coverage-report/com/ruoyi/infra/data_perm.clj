✔ (ns com.ruoyi.infra.data-perm
?   "数据权限过滤。
?   
?   支持 RuoYi 的五种数据权限范围:
?   - 1: ALL          — 全部数据
?   - 2: CUSTOM       — 自定义 (角色关联的部门)
?   - 3: DEPT         — 本部门
?   - 4: DEPT_CHILD   — 本部门及以下
?   - 5: SELF         — 仅本人
  
?   用法: (data-perm-filter identity role-key table-alias)
?   返回一个 SQL 片段或 WHERE 条件 map，注入到 HugSQL 查询中。"
?   (:require
?    [clojure.string :as str]
?    [clojure.tools.logging :as log]))
  
? ;; ──────────── 数据权限 SQL 片段生成 ────────────
  
✔ (def scope-names
?   "数据权限范围名称映射"
✔   {1 "ALL"
?    2 "CUSTOM"
?    3 "DEPT"
?    4 "DEPT_CHILD"
?    5 "SELF"})
  
✔ (defn- build-dept-filter
?   "根据角色数据权限范围，生成部门过滤条件。
?   返回 {:dept_ids [...] :user_id ...} 形式的参数 map 和 SQL where 片段。"
?   [role dept-ids alias user-id]
✔   (let [scope (:data-scope role 5)
~         alias  (or alias "u")]
~     (case scope
?       ;; 1: ALL — 不追加过滤
✔       1 {:params {} :sql "1=1"}
  
?       ;; 2: CUSTOM — 自定义部门范围
~       2 (let [ids (or dept-ids [(:dept-id role)])]
✔           {:params {:data-perm-dept-ids ids}
✔            :sql    (str " " alias ".dept_id IN (:v*:data-perm-dept-ids)")})
  
?       ;; 3: DEPT — 本部门
✔       3 (if-let [dept-id (:dept-id role)]
✔           {:params {:data-perm-dept-id dept-id}
✔            :sql    (str " " alias ".dept_id = :data-perm-dept-id")}
✔           {:params {} :sql "1=0"})
  
?       ;; 4: DEPT_CHILD — 本部门及以下
✔       4 (if-let [dept-id (:dept-id role)]
✔           {:params {:data-perm-dept-id dept-id}
✔            :sql    (str " " alias ".dept_id IN (SELECT dept_id FROM sys_dept "
?                         "WHERE del_flag = '0' AND (dept_id = :data-perm-dept-id OR ancestors LIKE '%/' || :data-perm-dept-id || '/%' || '%'))")}
✔           {:params {} :sql "1=0"})
  
?       ;; 5: SELF — 仅本人
✔       5 (if-let [uid user-id]
✔           {:params {:data-perm-user-id uid}
✔            :sql    (str " " alias ".user_id = :data-perm-user-id")}
✔           {:params {} :sql "1=0"}))))
  
✔ (defn data-perm-filter
?   "为查询生成数据权限过滤条件。
?   
?   identity  — 当前用户 claims (含 :user-id :roles)
?   role-key  — 查询所用的角色标识 (如 \"admin\" 不过滤)
?   alias     — SQL 表别名 (默认 \"u\")
?   
?   返回 {:sql \"...\" :params {...}} 合并到查询参数中。"
?   [identity role-key & {:keys [dept-ids alias]
?                         :or   {alias "u"}}]
✔   (let [user-id  (:user-id identity)
✔         user-name (:user-name identity)
✔         roles    (:roles identity [])]
?     ;; 管理员不过滤
✔     (if (some #(= "admin" (:role-key %)) roles)
✔       {:params {} :sql "1=1"}
✔       (let [role (first (filter #(= role-key (:role-key %)) roles))]
✔         (if role
✔           (build-dept-filter role dept-ids alias user-id)
✔           {:params {} :sql "1=1"})))))
