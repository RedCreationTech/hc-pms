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

## RuoYi-Vue 对照参考

参考项目：https://gitee.com/y_project/RuoYi-Vue (master 分支, Spring Boot 4.x + Vue 3)

### 核心要求

- **功能 1:1** — 每个功能模块必须完整实现 RuoYi-Vue 的所有交互细节
- **颜色一致** — 按钮、标签、状态颜色严格匹配 Element UI 默认色系
  - 新增: Primary 蓝 `#409eff` / 修改: Success 绿 `#67c23a` / 删除: Danger 红 `#f56c6c` / 导入: Info 灰 `#909399` / 导出: Warning 橙 `#e6a23c`
- **布局一致** — 搜索栏/工具栏/表格/分页的位置和间距与 RuoYi 一致
  - 左侧部门树 (200px) — 右侧内容区 (flex 1)
  - 搜索栏用 `:ghost true` 风格卡片
- **操作流程** — 严格按 RuoYi 的交互顺序：确认对话框 → API 调用 → 成功提示 → 刷新列表

### 功能模块对照清单

| # | 模块 | 对照要求 | 状态 |
|---|------|---------|------|
| 1 | 用户管理 | 左侧部门树 + 搜索栏(名称/手机/状态/日期) + 工具栏(新增/修改/删除/导入/导出/搜索/刷新/显隐列) + 表格(用户名可点/状态开关/更多菜单) + 新增/编辑弹窗(双列表单+部门树选+岗位角色多选) + 详情抽屉 + 重置密码 | 🟢 基本完成 |
| 2 | 角色管理 | 列表 + 新增/编辑弹窗 + 权限分配树 + 数据权限 | 🔴 未做 |
| 3 | 菜单管理 | 树形表格 + 新增/编辑弹窗 + 图标选择器 | 🔴 未做 |
| 4 | 部门管理 | 树形表格 + 新增/编辑弹窗 | 🔴 未做 |
| 5 | 岗位管理 | 列表 + 新增/编辑弹窗 | 🔴 未做 |
| 6 | 字典管理 | 字典类型列表(左) + 字典数据列表(右) + 新增/编辑弹窗 | 🟡 部分完成 |
| 7 | 参数管理 | 列表 + 新增/编辑弹窗 | 🟡 部分完成 |
| 8 | 通知公告 | 列表 + 新增/编辑弹窗 | 🟡 部分完成 |
| 9 | 操作日志 | 列表 + 详情弹窗 + 清空/导出 | 🟢 基本完成 |
| 10 | 登录日志 | 列表 + 详情弹窗 + 清空/导出 | 🟢 基本完成 |
| 11 | 在线用户 | 列表 + 强退确认 | 🟡 部分完成 |
| 12 | 定时任务 | 列表 + 新增/编辑/执行一次 | 🟡 部分完成 |
| 13 | 代码生成 | 配置 + 预览 + 下载代码 | 🔴 未做 |
| 14 | 系统接口 | Swagger 文档 | 🔴 未做 |
| 15 | 服务监控 | CPU/内存/JVM/磁盘可视化 | 🟡 部分完成 |
| 16 | 缓存监控 | 内存缓存键值浏览/清除 | 🟡 部分完成 |
| 17 | 表单构建 | 拖拽设计器 | 🔴 未做 |
| 18 | 连接池监视 | HikariCP 状态监控 | 🔴 未做 |

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

### Starting Dev Environment

```bash
# 1. Start backend (port 3000, nREPL port 7000)
cd /home/kevin/gt/rouyi_clojure/mayor/rig
rm -f rouyi.db && clojure -M:dev -m com.ruoyi.rouyi.core &

# 2. Start frontend watch (auto-recompiles on .cljs changes)
setsid bash -c 'cd /home/kevin/gt/rouyi_clojure/mayor/rig && npx shadow-cljs watch app' &
# First compilation takes ~2min, subsequent changes compile in seconds

# 3. Access
open http://localhost:3000
```

### Hot-Reload Workflow

#### Backend (Clojure) — nREPL hot-reload, no restart needed

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

**Note**: Route changes require a full system reset (`rr`) because routes are compiled once at startup.

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

### API Access

- Swagger UI: `http://localhost:3000/api`
- Health: `GET /api/health`
- Login: `POST /api/auth/login` with `{"username":"admin","password":"admin123"}`
- Most endpoints require `Authorization: Bearer <token>` header
