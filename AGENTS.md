# Agent Instructions

This project uses **bd** (beads) for issue tracking. Run `bd prime` for full workflow context.

## Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work atomically
bd close <id>         # Complete work
bd dolt push          # Push beads data to remote
```

## Non-Interactive Shell Commands

**ALWAYS use non-interactive flags** with file operations to avoid hanging on confirmation prompts.

Shell commands like `cp`, `mv`, and `rm` may be aliased to include `-i` (interactive) mode on some systems, causing the agent to hang indefinitely waiting for y/n input.

**Use these forms instead:**
```bash
# Force overwrite without prompting
cp -f source dest           # NOT: cp source dest
mv -f source dest           # NOT: mv source dest
rm -f file                  # NOT: rm file

# For recursive operations
rm -rf directory            # NOT: rm -r directory
cp -rf source dest          # NOT: cp -r source dest
```

**Other commands that may prompt:**
- `scp` - use `-o BatchMode=yes` for non-interactive
- `ssh` - use `-o BatchMode=yes` to fail instead of prompting
- `apt-get` - use `-y` flag
- `brew` - use `HOMEBREW_NO_AUTO_UPDATE=1` env var

## 后端热重载 (nREPL)

后端运行时通过 nREPL 端口 7000 热重载代码，**无需重启进程**：

**修改任何 `.clj` 文件后，必须连接 nREPL 并执行相应 reload 命令让修改在运行中的后端生效。** 常规领域/控制器/服务逻辑优先用 `(user/rd)`，路由或系统结构相关变更按下方规则使用 `(user/rroutes)` / `(user/rr)`。

```bash
# 单模块重载（最快，推荐日常开发）
clj-nrepl-eval -p 7000 '(user/rd)'          # 重载域服务
clj-nrepl-eval -p 7000 '(user/rroutes)'     # 重载路由（需 rr 生效）
clj-nrepl-eval -p 7000 '(user/ra)'          # 重载所有命名空间

# 全局重载（较慢，结构变更时使用）
clj-nrepl-eval -p 7000 '(user/rr)'          # 完全重启系统 (halt → prep → go)

# 数据库操作
clj-nrepl-eval -p 7000 '(user/reset-db)'    # 重置数据库（清空重建）
clj-nrepl-eval -p 7000 '(user/migrate)'     # 运行迁移
```

**何时需要重启（`(user/rr)`）：**
- HugSQL `.sql` 文件变更（查询缓存在启动时加载）
- `resources/system.edn` 配置变更
- Integrant 组件结构变更

**何时只需重载（`(user/rd)`）：**
- 控制器、服务、域逻辑变更
- 路由定义变更（需 `(user/rr)` 才能生效）

## Frontend 组件规范

### 使用 React Hooks，不用 reagent/atom

项目统一使用 Reagent 2 的函数组件 + React Hooks 管理局部状态，**禁止使用 `reagent/atom`**。

```clojure
;; ❌ 错误 — 用 reagent/atom
(let [expanded? (r/atom false)]
  [:div {:on-click #(reset! expanded? true)} ...])

;; ✅ 正确 — 用 hooks/use-state
(let [[expanded? set-expanded!] (hooks/use-state false)]
  [:div {:on-click #(set-expanded! true)} ...])
```

常用 hooks：
| Hook | 用途 |
|------|------|
| `hooks/use-state` | 局部状态（替代 r/atom） |
| `hooks/use-effect` | 副作用（替代 Form-2 的 `:component-did-mount`） |
| `hooks/use-callback` | 缓存回调函数 |
| `hooks/use-memo` | 缓存计算结果 |

**原则**：能用 re-frame subscription 的全局状态用 re-frame，组件内部局部状态用 hooks，不要用 r/atom。

## Frontend Ant Design 常见错误

### 1. Button 的 `:icon` 属性必须是 React 元素，不能传字符串

```clojure
;; ❌ 错误（antd 6 不接受字符串 icon）
[antd/button {:type "primary" :icon "search"} "搜索"]

;; ✅ 正确
[antd/button {:type "primary"
              :icon (r/as-element [:> SearchOutlined])}
 "搜索"]
```

### 2. Dropdown menu 的 `:label` 必须用 `r/as-element` 包裹

```clojure
;; ❌ 错误（Objects are not valid as a React child）
{:key "user_id" :label [:div {:onClick ...} [:span "用户编号"]]}

;; ✅ 正确
{:key "user_id"
 :label (r/as-element [:div {:onClick (fn [e] ...)}
                        [:span "用户编号"]])}
```

### 3. 列显隐必须检查 `:visible?` 字段，不能直接取 key

```clojure
;; ❌ 错误（取 key 返回 map，永远是 truthy）
(when (:user_id columns-config) ...)

;; ✅ 正确
(when (get-in columns-config [:user_id :visible?]) ...)
```

### 4. Drawer 的宽高用 `:size` 而不是 `:width`

```clojure
;; ❌ 错误（antd 6 告警 width deprecated）
[antd/drawer {:width 500 ...}]

;; ✅ 正确
[antd/drawer {:size "default" ...}]
;; 或 {:size "large"}
;; 需要精确宽度时用 :style
[antd/drawer {:style {:width 500} ...}]
```

### 5. 外部组件必须导入后定义，不能直接引用

```clojure
;; ❌ 错误（x.reagent_component undefined）
[antd/switch]  ;; 未在 antd.cljs 中定义

;; ✅ antd.cljs 中先定义
(def switch (r/adapt-react-class Switch))
;; 然后才能使用 [antd/switch]
```

### 6. `TextArea` 在 antd 6 中通过 `Input.TextArea` 访问

```clojure
;; ❌ 错误
["antd" :refer [TextArea]]  ;; TextArea is undefined

;; ✅ 正确
(def text-area (r/adapt-react-class (.-TextArea Input)))
```

### 7. 路由初始化必须在 app 渲染之后，且 navigate! 需检查初始化状态

```clojure
;; navigate! 必须等 configure-navigation! 调用后方可执行
;; 使用 initialized? 标志保护
(defonce initialized? (volatile! false))

(defn navigate! [page]
  (when @initialized?
    (accountant/navigate! (page-path page))))

(defn init-routes! []
  (accountant/configure-navigation! ...)
  (vreset! initialized? true)
  (accountant/dispatch-current!))
```

### 8. antd `message` 必须用 `App` 组件上下文，不能直接用静态方法

```clojure
;; ❌ 错误（antd 6 告警 Static function can not consume context）
(.success js/antd.message "创建成功")

;; ✅ 正确：在 antd.cljs 中通过 App.useApp 获取 message 实例
(def app (r/adapt-react-class App))
(defonce message-api (atom nil))

(defn use-app-message []
  (let [api (.useApp App)]
    (reset! message-api (.-message api))))

(defn success! [text]
  (if-let [api @message-api]
    (.success api text)
    (.success message text)))

;; app.cljs 中包裹应用
[:> ConfigProvider {...}
 [antd/app
  [message-init]   ;; 调用 use-app-message 的组件
  [layout/main-layout]]]
```

### 9. Card 的 `bodyStyle` 已废弃，改用 `styles.body`

```clojure
;; ❌ 错误
[antd/card {:title "xxx" :bodyStyle {:padding 12}} ...]

;; ✅ 正确
[antd/card {:title "xxx" :styles {:body {:padding 12}}} ...]
```

### 10. 自定义表单控件不要依赖 Form.Item 自动注入 value/onChange

Reagent 函数组件作为 `Form.Item` 子元素时，antd 无法像对原生 Input/Select 那样自动注入
`value` 和 `onChange`。需要手动通过 `Form.useForm` 实例读写字段。

```clojure
;; ❌ 错误（选中后表单无反应）
[antd/form-item {:label "归属部门" :name "dept_id"}
 [dept-tree-select {:placeholder "请选择"}]]

;; ✅ 正确
(let [[form] (antd/form-use-form)]
  [antd/form-item {:label "归属部门"}
   [dept-tree-select {:placeholder "请选择"
                      :value (.getFieldValue form "dept_id")
                      :on-change (fn [v]
                                   (.setFieldsValue form #js {"dept_id" v}))}]])
```

### 11. 后端分页参数使用 `page` / `size`

前端传给后端列表接口的分页参数必须是 `page` 和 `size`，而不是 `pageNum`/`pageSize`/`page-num`/`page-size`。

```clojure
;; ✅ 正确
{:api/list-users (merge params {:page page :size size})}
```

### 12. 不要同时设置 `:border` 和 `:borderColor`

React 会警告 shorthand 与非 shorthand 属性冲突，应把颜色合并到 `:border` 中。

```clojure
;; ❌ 错误
{:border "1px solid" :borderColor "#1677ff"}

;; ✅ 正确
{:border "1px solid #1677ff"}
```

### 13. Modal / Drawer 的 `:width` 已废弃，改用 `:style {:width N}`

```clojure
;; ❌ 错误
[antd/modal {:width 700 ...}]
[antd/drawer {:width 560 ...}]

;; ✅ 正确
[antd/modal {:style {:width 700} ...}]
[antd/drawer {:style {:width 560} ...}]
```

### 14. Modal / Drawer 的 `:destroyOnClose` 已废弃，改用 `:destroyOnHidden`

```clojure
;; ❌ 错误
[antd/modal {:destroyOnClose true ...}]

;; ✅ 正确
[antd/modal {:destroyOnHidden true ...}]
```

### 15. Progress 的 `strokeWidth` / `trailColor` 已废弃，改用 `size` / `railColor`

```clojure
;; ❌ 错误
[:> Progress {:percent percent :strokeWidth 10 :trailColor "#f0f0f0"}]

;; ✅ 正确
[:> Progress {:percent percent :size 10 :railColor "#f0f0f0"}]
```

## RuoYi-Vue 对照参考

参考项目：https://gitee.com/y_project/RuoYi-Vue (master 分支, Spring Boot 4.x + Vue 3)

UI 样式权威参考：https://gitee.com/y_project/RuoYi-Vue/tree/master/ruoyi-ui

### 核心要求

- **功能 1:1** — 每个功能模块必须完整实现 RuoYi-Vue 的所有交互细节
- **颜色一致** — 按钮、标签、状态颜色严格匹配 Element UI 默认色系
  - 新增: Primary 蓝 `#409eff` / 修改: Success 绿 `#67c23a` / 删除: Danger 红 `#f56c6c` / 导入: Info 灰 `#909399` / 导出: Warning 橙 `#e6a23c`
- **布局一致** — 搜索栏/工具栏/表格/分页的位置和间距与 RuoYi 一致
  - 左侧部门树 (200px) — 右侧内容区 (flex 1)
  - 搜索栏用 `:ghost true` 风格卡片
- **视觉 1:1 复刻** — 任何 UI 修改任务都必须同时验证功能与视觉，不得只确认功能可用
  - UI 样式必须优先参考 RuoYi-Vue 的 `ruoyi-ui` 源码目录，按对应页面的 `.vue` 组件、`scss/css` 样式、Element UI 组件配置、图标和 class 命名逐项追踪
  - 必须逐项对比 RuoYi 原版页面的组件边距、页面边距、组件宽高比例、字体、字号、颜色、图标、边框、圆角、阴影、对齐方式、行高、表格密度、按钮尺寸、弹窗/抽屉尺寸、分页位置等所有影响视觉观感的 UI 元素
  - 交付前需要说明已对照的 RuoYi 页面、截图或 `ruoyi-ui` 源码文件，并列出仍存在的视觉差异或确认无明显差异
- **菜单权限逻辑不可写死** — 即使 UI 任务提供了 RuoYi 系统截图作为参考，左侧菜单也只是视觉参考，不能把截图中的菜单项写成静态数据
  - 左侧菜单必须保持按当前登录用户角色/权限从接口动态获取的逻辑，代码修改不得绕过、替换或破坏权限菜单加载流程
  - 需要复刻截图中的菜单外观时，只能调整菜单容器、缩进、图标、颜色、字号、hover/active 状态等视觉样式，菜单数据来源仍必须来自后端接口
- **操作流程** — 严格按 RuoYi 的交互顺序：确认对话框 → API 调用 → 成功提示 → 刷新列表

### 功能模块对照清单

| # | 模块 | 对照要求 | 状态 |
|---|------|---------|------|
| 1 | 用户管理 | 左侧部门树 + 搜索栏(名称/手机/状态/日期) + 工具栏(新增/修改/删除/导入/导出/搜索/刷新/显隐列) + 表格(用户名可点/状态开关/更多菜单) + 新增/编辑弹窗(双列表单+部门树选+岗位角色多选) + 详情抽屉 + 重置密码 | 🟢 基本完成 |
| 2 | 角色管理 | 列表 + 新增/编辑弹窗 + 权限分配树 + 数据权限 + 用户分配 | 🟢 基本完成 |
| 3 | 菜单管理 | 树形表格 + 新增/编辑弹窗 + 图标选择器 | 🟢 基本完成 |
| 4 | 部门管理 | 树形表格 + 新增/编辑弹窗 | 🟢 基本完成 |
| 5 | 岗位管理 | 列表 + 新增/编辑弹窗 | 🟢 基本完成 |
| 6 | 字典管理 | 字典类型列表(左) + 字典数据列表(右) + 新增/编辑弹窗 | 🟢 基本完成 |
| 7 | 参数管理 | 列表 + 新增/编辑弹窗 | 🟢 基本完成 |
| 8 | 通知公告 | 列表 + 新增/编辑弹窗 | 🟢 基本完成 |
| 9 | 操作日志 | 列表 + 详情弹窗 + 清空/导出 | 🟢 基本完成 |
| 10 | 登录日志 | 列表 + 详情弹窗 + 清空/导出 | 🟢 基本完成 |
| 11 | 在线用户 | 列表 + 强退确认 | 🟢 基本完成 |
| 12 | 定时任务 | 列表 + 新增/编辑/执行一次/暂停恢复/日志 | 🟢 基本完成 |
| 13 | 代码生成 | 配置 + 预览 + 部署 + ZIP 下载 | 🟢 基本完成 |
| 14 | 系统接口 | Swagger 文档 | 🟢 基本完成 |
| 15 | 服务监控 | CPU/内存/JVM/磁盘可视化 | 🟢 基本完成 |
| 16 | 缓存监控 | 内存缓存键值浏览/清除 | 🟢 基本完成 |
| 17 | 表单构建 | 拖拽设计器 | 🟢 基本完成 |
| 18 | 连接池监视 | HikariCP 状态监控 | 🟢 基本完成 |

> 🟢 = 基本对齐  🟡 = 部分实现  🔴 = 未实现


<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd dolt push
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->

## Development Workflow

### Architecture

```
Backend  (Clojure, port 3000)    — API + serves static frontend
Frontend (ClojureScript)          — SPA via shadow-cljs, compiled to resources/public/js/
Database (SQLite)                 — rouyi.db, auto-migrated on startup
```

Both frontend and backend share port **3000**. The backend serves both API and static files.

### Database Compatibility (SQLite / MySQL)

项目同时支持 SQLite 和 MySQL，切换靠两个环境变量：

| 环境变量 | 默认值 | MySQL 用法 |
|----------|--------|------------|
| `JDBC_URL` | `jdbc:sqlite:rouyi.db` | `jdbc:mysql://user:pass@host:port/db?useSSL=false&allowPublicKeyRetrieval=true` |
| `MIGRATION_DIR` | `migrations-sqlite` | `migrations` |

#### Migration 必须完全分开

DDL 差异无法兼容，因此有两套目录：

- `resources/migrations-sqlite/` —— SQLite 专用
- `resources/migrations/` —— MySQL 专用

新增/修改表时，**两个目录必须同步更新**。常见差异：

| 场景 | SQLite | MySQL |
|------|--------|-------|
| 自增主键 | `INTEGER PRIMARY KEY` | `BIGINT AUTO_INCREMENT PRIMARY KEY` |
| 时间字段 | `TEXT DEFAULT CURRENT_TIMESTAMP` | `TIMESTAMP DEFAULT CURRENT_TIMESTAMP` |
| 布尔/状态 | `CHAR(1)` / `INTEGER` | `CHAR(1)` / `TINYINT` |

#### 查询 SQL 优先共用，必要时分支

业务查询统一放在 `resources/sql/*.sql`，由 `conman` 加载。原则：

1. **优先用两库都支持的语法**

   ```sql
   -- ✅ 推荐：两库都支持
   AND (:job_name IS NULL OR INSTR(job_name, :job_name) > 0)
   LIMIT :page_size OFFSET :offset
   ```

2. **禁止在共用 SQL 里写 SQLite-only 语法**

   ```sql
   -- ❌ 错误：|| 在 MySQL 默认 sql_mode 下是逻辑 OR
   AND (:user_name IS NULL OR user_name LIKE '%' || :user_name || '%')

   -- ✅ 正确
   AND (:user_name IS NULL OR INSTR(user_name, :user_name) > 0)
   ```

3. **函数名不同就提供命名变体，在 Clojure 层选择**

   例如 `resources/sql/system.sql`：

   ```sql
   -- :name last-insert-rowid :? :1
   SELECT last_insert_rowid() AS last_insert_rowid

   -- :name last-insert-rowid-mysql :? :1
   SELECT LAST_INSERT_ID() AS last_insert_rowid
   ```

   由 `com.ruoyi.infra.db/detect-db-type` 判断后调用对应名字。

4. **元数据/动态查询在 Clojure 层分支**

   `com.ruoyi.infra.db` 里对 `get-tables`、`get-table-columns`、`paginate-query` 等按 `:sqlite` / `:mysql` 分情况处理，不要把 `PRAGMA`、`sqlite_master`、`information_schema` 混进共用 `.sql`。

#### 修改后必须双库跑测试

任何 `resources/migrations*` 或 `resources/sql/*.sql` 改动，都要验证两套数据库：

```bash
# SQLite
rm -f rouyi.db && bb test

# MySQL（先清空数据库）
docker exec ruoyi-mysql mysql -uroot -ppassword -e \
  "DROP DATABASE IF EXISTS ruoyi; CREATE DATABASE ruoyi CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
JDBC_URL="jdbc:mysql://root:password@127.0.0.1:3308/ruoyi?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
MIGRATION_DIR=migrations bb test
```

### Starting Dev Environment

```bash
# 1. Start backend (port 3000, nREPL port 7000)
clojure -M:dev -m com.ruoyi.core &

# 2. Start frontend watch (auto-recompiles on .cljs changes)
npx shadow-cljs watch app &
# First compilation takes ~2min, subsequent changes compile in seconds

# 3. Access
open http://localhost:3000
```

### Hot-Reload Workflow

#### Backend (Clojure) — nREPL hot-reload, no restart needed

After editing any `.clj` file, connect to the running nREPL and run the matching reload command so the live backend uses the new code. Prefer `(user/rd)` for normal domain/controller/service changes; use `(user/rroutes)` / `(user/rr)` when routes or system wiring require it.

```bash
# After editing .clj files:
clj-nrepl-eval -p 7000 '(user/rd)'          # Reload domain services (fastest)
clj-nrepl-eval -p 7000 '(user/rroutes)'     # Reload routes (needs system reset to apply)
clj-nrepl-eval -p 7000 '(user/ra)'          # Reload all namespaces
clj-nrepl-eval -p 7000 '(user/rr)'          # Full Integrant reset (halt + go)
```

Available helpers (defined in `env/dev/clj/user.clj`):

| Helper | Short | What it reloads |
|--------|-------|-----------------|
| `reload-domain` | `rd` | Domain services (user, role, menu, dept, dict, config, log, gen) |
| `reload-middleware` | `rm` | Ring middleware (auth, exception, operlog, core) |
| `reload-routes` | `rroutes` | Route definitions (needs `rr` to apply) |
| `reload-controllers` | — | Web controllers |
| `reload-infra` | — | Security, online, data-perm |
| `reload-all` | `ra` | All of the above |
| `reload-system` | `rr` | Full system reset (halt → prep → go) |

**Note**: Route changes require a full system reset (`rr`) because routes are compiled once at startup. If `(user/rr)` fails with `BindException: Address already in use` (Undertow can't rebind), kill the process and restart with `clojure -M:dev -m com.ruoyi.core`.

#### Frontend (ClojureScript) — shadow-cljs auto-compiles

```bash
# shadow-cljs watch is running in background
# Edit .cljs files → watch auto-detects → incremental compile (~5s)
# Just refresh browser to see changes
```

### When to Restart (not just reload)

- HugSQL `.sql` file changes (queries are cached at startup)
- `resources/system.edn` config changes
- Integrant component structure changes
- After these, run `clj-nrepl-eval -p 7000 '(user/rr)'` or restart the process

### Build Uberjar

```bash
npx shadow-cljs release app    # Compile frontend for production
clojure -T:build all            # Build standalone jar (includes frontend)
java -jar target/rouyi-standalone.jar  # Run (port 3000, SQLite)
```

### E2E 测试 (Playwright)

已接入 Playwright 对主要功能做端到端验证，默认跑在 `http://localhost:3000`。

```bash
# 安装浏览器（首次）
npx playwright install chromium

# 运行全部 E2E 用例并生成 HTML/JSON 报告
npm run test:e2e

# 查看 HTML 报告
npm run test:e2e:report
```

测试目录：`tests/e2e/`

- `auth.spec.js` — 管理员登录/登出
- `navigation.spec.js` — 系统管理、系统监控、系统工具等核心菜单可访问性
- `post-crud.spec.js` — 岗位管理新增/修改/删除示例
- `auth-helper.js` — 登录/登出公共辅助

报告输出：`playwright-report/`

### API Access

- Swagger UI: `http://localhost:3000/api`
- Health: `GET /api/health`
- Login: `POST /api/auth/login` with `{"username":"admin","password":"admin123"}`
- Most endpoints require `Authorization: Bearer <token>` header
