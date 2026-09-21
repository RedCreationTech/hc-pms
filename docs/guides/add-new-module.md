# 新增业务模块指南

> 目标读者:要在本框架上开发新功能的开发者.
> 本文以"请假申请"模块(`biz_oa_leave`)为贯穿示例,它是"业务记录 + BPM 审批流"集成的旗舰示例;
> 不带审批流的简单 CRUD 模块(如 OA 日程,CRM 客户)链路相同,只是领域服务不依赖 Flowable 引擎.

## 概述:五分钟上手路径

一个新模块需要动七处,按依赖顺序:

```
1. 迁移(双库 DDL)      resources/migrations-sqlite/ + resources/migrations/
2. HugSQL 查询           resources/sql/business.sql
3. 领域服务              src/clj/com/ruoyi/domain/business/<module>.clj
4. Integrant 声明        resources/system.edn
5. 控制器 + 路由         src/clj/com/ruoyi/web/controllers/business/<module>.clj
                         src/clj/com/ruoyi/web/routes/business.clj
6. 前端(API + 事件 + 页面 + 路由)
   src/cljs/com/ruoyi/frontend/{api,events,router}.cljs
   src/cljs/com/ruoyi/frontend/pages/business/<module>.cljs
   src/cljs/com/ruoyi/frontend/pages/layout.cljs
7. 菜单权限(sys_menu 种子迁移)+ 测试(test/clj/com/ruoyi/business/)
```

后端分层固定为:**route → controller → domain service → HugSQL**,控制器不直接写 SQL,
领域服务持有 `query-fn`(HugSQL 生成的查询函数表)并组装业务逻辑.

开发时优先用 nREPL 热重载,避免重启 JVM(见 AGENTS.md 第五节):

```bash
clj-nrepl-eval -p $NREPL_PORT '(user/rd)'        # 改控制器/服务/域逻辑后
clj-nrepl-eval -p $NREPL_PORT '(user/rr)'        # 改了 .sql 迁移 / system.edn 后
```

---

## 第 1 步:定义数据表(双库迁移)

项目同时支持 SQLite 和 MySQL,**每套迁移要各写一份**,目录规则:

| 目录 | 用途 |
|------|------|
| `resources/migrations-sqlite/` | SQLite DDL(开发/测试默认) |
| `resources/migrations/` | MySQL DDL(`MIGRATION_DIR=migrations` 切换) |

### 命名规则

`YYYYMMDDHHMM-<slug>.up.sql` / `.down.sql`,例如 `202608240005-create-biz-oa.up.sql`.
时间戳前缀决定执行顺序,必须比现有所有迁移新(用 `ls resources/migrations-sqlite | tail` 确认).

### `--;;` 分隔符(最重要的一条规则)

Migratus 的 JDBC 一次只执行一条语句,**每条语句之后都必须紧跟一行 `--;;`**,
不是只在文件末尾放一个.漏写不会报错--只静默执行第一条语句,迁移仍被标记为已完成,
之后查询报 `no such column` 时极难排查(踩坑实录见 AGENTS.md 第六节).

### SQLite 与 MySQL 的 DDL 差异

对比 `resources/migrations-sqlite/202608240005-create-biz-oa.up.sql` 与
`resources/migrations/202608240005-create-biz-oa.up.sql`:

| 场景 | SQLite | MySQL |
|------|--------|-------|
| 自增主键 | `INTEGER PRIMARY KEY AUTOINCREMENT` | `BIGINT AUTO_INCREMENT PRIMARY KEY` |
| 字符串 | `TEXT NOT NULL DEFAULT ''` | `VARCHAR(255) NOT NULL DEFAULT ''` |
| 时间字段 | `TEXT` / `CURRENT_TIMESTAMP` | `TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` |
| 布尔/状态 | `TEXT` / `INTEGER`(`'0'`/`'1'`) | `CHAR(1)` |
| 引擎/字符集 | 无 | `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` |
| 幂等插入 | `INSERT OR IGNORE` | `INSERT IGNORE` |

骨架示例(SQLite 版):

```sql
-- 业务模块 · 示例表(SQLite)
CREATE TABLE IF NOT EXISTS biz_demo_item (
  item_id      INTEGER PRIMARY KEY AUTOINCREMENT,
  name         TEXT NOT NULL DEFAULT '',
  status       TEXT NOT NULL DEFAULT '0',
  create_by    TEXT NOT NULL DEFAULT '',
  create_time  TEXT,
  update_by    TEXT NOT NULL DEFAULT '',
  update_time  TEXT
);
--;;

CREATE INDEX IF NOT EXISTS idx_demo_item_status ON biz_demo_item(status);
--;;
```

> 完整示例见 `resources/migrations-sqlite/202608240005-create-biz-oa.up.sql`.
> 写完两个目录的迁移后,删除本地 `rouyi.db` 重启或用 `(user/migrate)` 验证 schema 完整.

---

## 第 2 步:HugSQL 查询

所有业务查询集中在 `resources/sql/business.sql`(系统模块在 `sql/system.sql` 等,
新文件需在 `resources/system.edn` 的 `:db.sql/query-fn :filenames` 中注册).

### 写法约定(对照 `resources/sql/business.sql`)

```sql
-- :name oa/leave-list :? :*
SELECT leave_id, user_id, user_name, days, reason, process_instance_id, status, create_time
FROM biz_oa_leave
WHERE (:user_id IS NULL OR user_id = :user_id)
  AND (:status IS NULL OR status = :status)
ORDER BY leave_id DESC
LIMIT :page_size OFFSET :offset
--;;

-- :name oa/leave-count :? :1
SELECT COUNT(*) AS total FROM biz_oa_leave
WHERE (:user_id IS NULL OR user_id = :user_id)
  AND (:status IS NULL OR status = :status)
--;;

-- :name oa/insert-leave :! :n
INSERT INTO biz_oa_leave (user_id, user_name, days, reason, process_instance_id, status, create_time)
VALUES (:user_id, :user_name, :days, :reason, :process_instance_id, :status, CURRENT_TIMESTAMP)
--;;
```

约定要点:

- **命名空间前缀**:`oa/leave-list`,在 Clojure 里以 keyword `:oa/leave-list` 调用.
- **命令后缀**:`:? :*`(查询多行),`:? :1`(查询单行),`:! :n`(写操作).
- **分页**:列表查询固定 `LIMIT :page_size OFFSET :offset`,配套一个 `xxx-count` 查询,
  领域服务负责把前端的 `page`/`size` 换算成 `offset`.
- **可选过滤**:`(:name IS NULL OR INSTR(name, :name) > 0)` 模式--参数为 nil 时条件自动失效.
  模糊匹配用 `INSTR`(双库通用),不要用 SQLite 特有的 `||`.
- **时间戳**:`CURRENT_TIMESTAMP`(双库通用),不要用 `datetime('now')`(SQLite 独有).
- **更新部分字段**:`COALESCE(:col, col)` 保护未传参的列不被覆盖(见 `bpm/update-model`).

### 双库函数差异怎么办

绝大多数查询用共用语法即可.遇到方言差异(如取自增 ID)时,在 HugSQL 文件里提供
`-mysql` 命名变体,Clojure 层用 `com.ruoyi.infra.db/detect-db-type` 选择,
参考 `src/clj/com/ruoyi/infra/db.clj` 的 `last-insert-id` / `insert-and-get-id!`.

> ⚠️ 改完 `.sql` 文件后 HugSQL 查询缓存不会自动刷新,必须
> `clj-nrepl-eval -p $NREPL_PORT '(user/rr)'` 重启 Integrant 系统.

---

## 第 3 步:领域服务

领域服务是普通 Clojure 命名空间 + Integrant 组件,负责业务规则,分页换算,
多查询编排.完整示例见 `src/clj/com/ruoyi/domain/business/leave.clj`,骨架:

```clojure
(ns com.ruoyi.domain.business.demo
  "示例领域服务."
  (:require [integrant.core :as ig]))

;; 1. Integrant 入口:key 与 system.edn 中的声明对应
(defmethod ig/init-key :app.business/demo-service
  [_ {:keys [query-fn db]}]
  {:query-fn query-fn :db db})

;; 2. 分页参数换算:前端传 page/size,SQL 用 offset/page_size
(defn- page-params
  [params]
  (let [page (or (some-> (get params :page) Integer/parseInt) 1)
        size (or (some-> (get params :size) Integer/parseInt) 10)]
    {:page page :size size :offset (* (dec page) size)}))

;; 3. 列表:两次查询(rows + count),返回 {:rows ... :total ...}
(defn demo-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (page-params params)
        p {:name (get params :name) :page_size size :offset offset}]
    {:rows  (query-fn :demo/item-list p)
     :total (:total (query-fn :demo/item-count p))}))

;; 4. 单条/写入
(defn demo-get    [{:keys [query-fn]} id]  (query-fn :demo/find-item-by-id {:item_id id}))
(defn demo-create [{:keys [query-fn]} m]   (query-fn :demo/insert-item m))
(defn demo-delete [{:keys [query-fn]} id]  (query-fn :demo/delete-item {:item_id id}))
```

要点:

- `query-fn` 是一个"查询名 keyword → 函数"的映射,直接 `(query-fn :oa/leave-list params)` 调用.
- 入参 `params` 的 key 是 keyword;注意 HTTP 层的 `:query-params` 是 **string key**,
  已在控制器层用 `bu/kquery` 转换(见第 4 步).
- 需要审批流的模块额外注入 `:engine`(Flowable),见
  `src/clj/com/ruoyi/domain/business/leave.clj` 的 `leave-start!` /
  `sync-status!`(惰性同步:列表时对运行中的单据按流程状态刷新 `status`).

---

## 第 4 步:控制器与路由

### 控制器

控制器只做四件事:解包请求,调用服务,组装响应,捕获异常.
完整示例见 `src/clj/com/ruoyi/web/controllers/business/leave.clj`,骨架:

```clojure
(ns com.ruoyi.web.controllers.business.demo
  (:require [com.ruoyi.domain.business.demo :as demo]
            [com.ruoyi.web.controllers.business.util :as bu]
            [ring.util.response :as response]))

(defn- ok   [data] (-> (response/response {:code 200 :msg "操作成功" :data data})
                       (response/content-type "application/json")))
(defn- fail [msg]  (-> (response/response {:code 500 :msg msg})
                       (response/content-type "application/json")))
(defn- wrap-err [f] (try (f) (catch Exception e (fail (.getMessage e)))))

;; 第一个参数是路由 partial 注入的服务 map,第二个是 Ring request
(defn list-items
  [{:keys [demo-service]} request]
  (wrap-err #(ok (demo/demo-list demo-service (bu/kquery request)))))

(defn get-item
  [{:keys [demo-service]} request]
  (let [id (some-> (get-in request [:path-params :id]) Integer/parseInt)]
    (wrap-err #(if-let [d (demo/demo-get demo-service id)]
                 (ok d) (fail 404 "记录不存在")))))
```

- **`bu/kquery`**(`src/clj/com/ruoyi/web/controllers/business/util.clj`)把
  `:query-params` 的 string key 转 keyword key--漏掉它分页/过滤会静默失效(真实踩坑,
  见 2026-08-25 提交 "业务控制器 query-params keywordize").
- 统一响应格式 `{:code 200 :msg "..." :data ...}`,与前端 `(:code r)` 判断对齐.
- 当前用户身份从 `(:identity request)` 取:`(:user-id)` / `(:user-name)` / `(:perms)`
  由 JWT 中间件注入.

### 路由

在 `src/clj/com/ruoyi/web/routes/business.clj` 注册(Reitit 数据驱动路由):

```clojure
;; 1. 顶部 :require 控制器命名空间

;; 2. business-routes 形参里解构新服务(key 与 system.edn 注入名一致)
(defn business-routes
  [{:keys [demo-service leave-service ...]}]
  ["/business"
   {:middleware [(auth-mw/auth-middleware {:required? true})]   ;; 整组要求登录
    :swagger {:tags ["办公" "BPM"]}}

   ;; ── 示例模块 ──
   ["/demo/item"
    ["" {:get    {:summary "示例列表" :handler (partial demo/list-items {:demo-service demo-service})}
         :post   {:summary "新增示例" :handler (partial demo/create-item {:demo-service demo-service})}}]
    ["/:id" {:get    {:summary "示例详情" :handler (partial demo/get-item {:demo-service demo-service})}
             :delete {:summary "删除示例" :handler (partial demo/delete-item {:demo-service demo-service})}}]]

   ;; ── OA 请假(完整示例)──
   ["/oa/leave" ...]])
```

- 服务通过 `(partial handler {:service-key service})` 注入,handler 签名如上.
- 路由挂在 `/api` 下(`system.edn` 的 `:reitit.routes/api :base-path`),
  即实际路径 `/api/business/oa/leave`.
- `/business` 组已挂 `auth-middleware {:required? true}`(JWT 认证,未登录 401).
  如需按钮级权限保护,可在路由数据里挂 `auth-mw/require-perms`(已有实现,
  匹配 `(:identity :perms)` 任意一个权限标识,否则 403);当前业务路由未使用,
  权限主要由前端按 perms 控制按钮显隐(见第 6 步).

### Integrant 接线(resources/system.edn)

三处都要加,缺一不可:

```edn
;; 1. 声明服务组件(注入链:query-fn / db 来自基础设施,需要审批流时加 engine)
:app.business/demo-service
{:query-fn #ig/ref :db.sql/query-fn
 :db       #ig/ref :db.sql/connection}

;; 2. 挂到路由组件的参数里
:reitit.routes/api
{...
 :demo-service #ig/ref :app.business/demo-service}

;; 3. 若新建了 .sql 文件,注册到 query-fn 的文件列表
:db.sql/query-fn
{:filenames ["queries.sql" "sql/system.sql" "sql/log.sql" "sql/job.sql" "sql/business.sql"]}
```

改完 `system.edn` 或 `.sql` 后必须 `(user/rr)`;只改控制器/服务/路由定义则 `rd` / `rroutes` 即可.

---

## 第 5 步:前端 API 与页面

### 5.1 API 函数(src/cljs/com/ruoyi/frontend/api.cljs)

按模块分区追加,函数签名统一 `[params on-success on-error]`:

```clojure
;; ─── 示例模块 ──────────────────────────────────────────────────────
(defn demo-list-items
  [params on-success on-error]
  (request {:method :get :uri "/business/demo/item" :params params
            :on-success on-success :on-error on-error}))

(defn demo-create-item
  [params on-success on-error]
  (request {:method :post :uri "/business/demo/item" :params params
            :on-success on-success :on-error on-error}))

(defn demo-delete-item
  [id on-success on-error]
  (request {:method :delete :uri (str "/business/demo/item/" id)
            :on-success on-success :on-error on-error}))
```

`request` 私有函数自动从 re-frame app-db 取 token 加 `Authorization: Bearer` 头.

### 5.2 re-frame 事件与订阅(src/cljs/com/ruoyi/frontend/events.cljs)

模式:**event-fx 触发 reg-fx → reg-fx 调 api 函数 → 回调里 dispatch set-list**.
完整示例见 `events.cljs` 的 `:leave/*` 段(约 2891 行),骨架:

```clojure
(rf/reg-event-fx :demo/fetch
  (fn [{:keys [db]} [_ params]]
    {:db (assoc-in db [:demo :loading?] true)
     :api/demo-list (or params {})}))

(rf/reg-fx :api/demo-list
  (fn [params]
    (api/demo-list-items params
      (fn [r] (when (= 200 (:code r)) (rf/dispatch [:demo/set-list (:data r)])))
      (fn [_] (antd/error! "加载失败")))))

(rf/reg-event-db :demo/set-list
  (fn [db [_ data]]
    (assoc db :demo {:items (:rows data []) :total (:total data 0) :loading? false})))
```

订阅(subs)注册在同模块,页面用 `@(rf/subscribe [:demo/items])` 读取.

### 5.3 页面组件(src/cljs/com/ruoyi/frontend/pages/business/<module>.cljs)

完整示例见 `src/cljs/com/ruoyi/frontend/pages/business/leave.cljs`,骨架:

```clojure
(ns com.ruoyi.frontend.pages.business.demo
  (:require ["@ant-design/icons" :refer [PlusOutlined ReloadOutlined]]
            [com.ruoyi.frontend.antd :as antd]
            [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(defn demo-page
  []
  (let [items    @(rf/subscribe [:demo/items])
        total    @(rf/subscribe [:demo/total])
        loading? @(rf/subscribe [:demo/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [page-toolbar/toolbar-button {:kind :add
                                            :icon (r/as-element [:> PlusOutlined])
                                            :on-click #(rf/dispatch [:demo/open-modal])
                                            :label "新增"}]]}]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "item_id"
                  :columns (demo-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [t] (str "共 " t " 条"))}}]
     [demo-modal]]))
```

前端规范要点(详见 AGENTS.md 第七节):

- **用 React Hooks / re-frame,禁用 `r/atom`** 存局部 UI 状态.
- **antd 6 API**:Modal 宽度用 `:style {:width 700}` 而不是 `:width`;
  `:destroyOnHidden` 替代 `destroyOnClose`;Card 用 `:styles {:body {...}}`.
- **Button icon 必须是 React 元素**:`(r/as-element [:> PlusOutlined])`.
- **分页参数固定 `page`/`size`** 传给后端(不是 pageNum/pageSize).
- Table 的 `:columns` 是 js 数组,`:render` 里返回 hiccup 要包 `r/as-element`.

### 5.4 路由注册(两处)

`src/cljs/com/ruoyi/frontend/router.cljs` 的 bidi 路由表加一项
(路径对应后端菜单种子的 `path`):

```clojure
"office/demo/item" :demo-item
```

`src/cljs/com/ruoyi/frontend/pages/layout.cljs` 两个地方:

```clojure
;; 顶部 :require
[com.ruoyi.frontend.pages.business.demo :as demo-page]

;; 页面分发映射(约 784 行的 case 里)
:demo-item [demo-page/demo-page]
```

左侧菜单本身从后端动态拉取(见第 6 步),不在前端写死.

---

## 第 6 步:菜单权限

权限模型(RBAC):`用户 ── N:N ── 角色 ── N:N ── 菜单(含 perms 按钮权限标识)`.

新模块通过**菜单种子迁移**接入:在 `sys_menu` 插目录/菜单/按钮三级记录,
并给角色授权(完整示例见 `resources/migrations-sqlite/202608240006-add-oa-menus.up.sql`):

```sql
-- 菜单(menu_type 'C' = 页面;path 对应前端 bidi 路由)
INSERT OR IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon)
VALUES (37, '日程管理', 30, 5, 'oa/calendar', 'business/oa/calendar/index', 'C', '0', '0', 'oa:calendar:list', 'calendar');
--;;

-- 按钮权限(menu_type 'F';perms 标识由前端控制按钮显隐)
INSERT OR IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, visible, status, perms, icon)
VALUES (309, '日程新增', 37, 1, 'F', '0', '0', 'oa:calendar:add', '#');
--;;

-- 授权给角色(1 = 超级管理员)
INSERT OR IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 37), (1, 309);
--;;
```

- `path` 去掉前导 `/` 后与前端 `router.cljs` 的路由项对应;
  `parent_id` 指向父目录菜单(30 = 办公目录).
- 登录后 `GET /api/auth/getInfo` 返回当前用户的 `perms` 集合,
  前端据此渲染左侧菜单和按钮.菜单/按钮权限不可绕过,替换或破坏动态加载流程(AGENTS.md 7.4).
- ⚠️ 种子迁移同样遵守 `--;;` 规则;注意上面示例文件的最后一条语句漏了 `--;;`
  (属历史遗留),新写迁移不要效仿--每条语句后都加.

---

## 第 7 步:测试

### 组织方式

- 后端测试在 `test/clj/com/ruoyi/`,业务模块放 `test/clj/com/ruoyi/business/`.
- 测试通过 **peridot 走完整 Ring handler** 做 REST 级集成测试
  (比单测控制器更贴近真实链路),辅助函数在
  `test/clj/com/ruoyi/test_utils.clj`:`system-fixture`(启停系统),
  `GET` / `PUT`(还有 `POST` / `DELETE`,部分测试文件内自定义),`get-response`.
- 参考完整样例:`test/clj/com/ruoyi/business/bpm_p0_test.clj`
  (登录拿 token → 造数据 → 调接口断言 `:code` 与响应体).

### 骨架

```clojure
(ns com.ruoyi.business.demo-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [com.ruoyi.test-utils :refer [system-state system-fixture GET]]
            [peridot.core :as p]))

(use-fixtures :once (system-fixture))

(defn- handler [] (:handler/ring (system-state)))

(defn- parse-json [resp]
  (json/read-str (:body resp) :key-fn keyword))

(defn- login-token [username]
  (let [ctx (-> (p/session (handler))
                (p/request "/api/auth/login"
                           :request-method :post
                           :content-type "application/json"
                           :body (json/write-str {:username username :password "admin123"})))]
    (get-in (parse-json (:response ctx)) [:data :token])))

(deftest demo-crud-test
  (let [h {"authorization" (str "Bearer " (login-token "admin"))}]
    (testing "列表分页"
      (let [r (parse-json (GET (handler) "/api/business/demo/item?page=1&size=10" {} h))]
        (is (= 200 (:code r)))
        (is (vector? (get-in r [:data :rows])))))
    (testing "未登录返回 401"
      (let [resp (GET (handler) "/api/business/demo/item" {} {})]
        (is (= 401 (:status resp)))))))
```

要点:

- `(use-fixtures :once (system-fixture))`:整个命名空间共享一次系统启停.
- 默认账号 `admin / admin123`;自建测试用户记得用随机后缀避免冲突
  (参考 `bpm_p0_test.clj` 的 `ensure-user!`).
- 分页参数用 `page`/`size`;断言统一响应体的 `:code`.

### 双库验证

新表/新查询必须在两个库上各跑一遍(AGENTS.md 第六节):

```bash
rm -f rouyi.db && bb test                       # SQLite
JDBC_URL="jdbc:mysql://root:password@127.0.0.1:3308/ruoyi?useSSL=false&..." \
MIGRATION_DIR=migrations bb test                # MySQL
```

---

## 常见坑速查

| 坑 | 规则 | 出处 |
|----|------|------|
| 迁移只执行了第一条语句 | **每条** SQL 后都要有 `--;;`;改完用新库验证 schema | AGENTS.md 六 |
| 列表分页/搜索静默失效 | 控制器必须 `(bu/kquery request)` 转 keyword key | `controllers/business/util.clj` |
| 前端传 `pageNum`/`pageSize` | 统一用 `page`/`size`;SQL 层换算 `offset`/`page_size` | AGENTS.md 7.3 |
| 改 `.sql` / `system.edn` 后行为未变 | HugSQL 查询与组件配置启动时缓存,必须 `(user/rr)` | AGENTS.md 五 |
| 模糊查询用了 `LIKE '||'` 等方言 | 用 `INSTR(col, :x) > 0`,`CURRENT_TIMESTAMP`,`LIMIT/OFFSET` 双库通用写法 | `resources/sql/business.sql` |
| 自增 ID 获取方言不同 | HugSQL 提供 `-mysql` 变体 + `detect-db-type` 分支 | `infra/db.clj` |
| 页面用了 `r/atom` 存状态 | 用 `reagent.hooks/use-state` 或 re-frame | AGENTS.md 7.1 |
| antd 6 旧 API | `:destroyOnHidden`,`:styles {:body {...}}`,icon 用 `(r/as-element [:> XxxOutlined])` | AGENTS.md 7.2 |
| 菜单/按钮权限写死在前端 | 菜单必须走 `getInfo` 动态加载,按钮按 `perms` 显隐 | AGENTS.md 7.4 |
| 自定义表单项值不同步 | antd 无法自动注入 Reagent 函数组件,用 `Form.useForm` 手动读写 | AGENTS.md 7.5 |
| `IN ()` 语法错误 | HugSQL `:v*` 展开空数组会生成 `IN ()`,空集合传 `[0]` 哨兵 | BPM 08-26 changelog (42) |
