# RuoYi-Clojure Agent Instructions

> **开发分支**: `ruoyi-template`
> **仓库**: RedCreationTech/ruoyi_clojure (git@github.com:RedCreationTech/ruoyi_clojure.git)
> **当前 Git**: `ruoyi-template` 分支与 `origin/ruoyi-template` 同步 (HEAD `ee3b486` - "Format entire codebase with cljstyle")
> **参考原版**: https://gitee.com/y_project/RuoYi-Vue (v3.9.2)

---

## 一、项目概况

RuoYi-Clojure 是基于 **Kit 框架** + **Reagent 2** + **Ant Design 6** 构建的 RuoYi 风格全栈管理后台。完整实现了系统管理与日志监控共 18 个 RuoYi-Vue 功能模块，并集成 **BPM 工作流引擎（Flowable 8.0）与办公一体化**（请假、报销、OA 日历/会议、HRM、CRM、报表），核心功能齐平 RuoYi-Vue / ruoyi-office。

### 技术栈速查

| 层       | 技术                                                                   | 端口/工具                     |
|----------|------------------------------------------------------------------------|-------------------------------|
| 后端     | Clojure 1.12 + Kit (Integrant, Reitit)                                 | HTTP 3000, **nREPL 7000**     |
| 数据库   | SQLite (默认) / MySQL / PostgreSQL                                     | JDBC URL + MIGRATION_DIR 切换 |
| 前端     | ClojureScript + Reagent 2 + re-frame + Ant Design 6                    | shadow-cljs watch app         |
| CSS      | 无独立 CSS 框架 — 全部通过 antd ConfigProvider token 和内联 style 控制 | —                             |
| 构建     | shadow-cljs (前端) + tools.build uberjar (后端)                        | —                             |
| 任务调度 | cronut/scheduler (`:schedule []` 为空，任务存库由 job-scheduler 轮询) | —                             |
| 测试     | clojure.test + Playwright E2E + Cloverage                              | bb test / npm run test:e2e    |

### 物理路径

以下均为项目根目录（本文档所在目录）下的相对路径：

```
src/clj/com/ruoyi/                   ← 后端源码
src/cljs/com/ruoyi/frontend/         ← 前端源码
resources/                           ← 配置、SQL、迁移、静态资源
test/clj/com/ruoyi/                  ← 测试
test/coverage-report.md              ← 覆盖率报告
env/dev/clj/user.clj                 ← 开发环境 REPL 工具函数
```

---

## 二、架构分层

### 后端五层结构

```
route (web/routes/) → controller (web/controllers/) → domain service (domain/) → HugSQL SQL
                                                                         ↑
                                                                   infra/db.clj (数据库抽象)
```

### Integrant 组件链 (system.edn)

```
nrepl/server → server/http → handler/ring → router/core → routes/api
                                                              ├── auth-routes
                                                              ├── system-routes
                                                              ├── business-routes (含 BPM/办公)
                                                              ├── captcha-routes
                                                              └── common/utils-routes
db.sql/migrations → db.sql/connection → db.sql/query-fn → 15 个域服务
cronut/scheduler → app.system/job-scheduler                  (定时任务调度)
app.bpm/engine (Flowable 8.0, H2) → app.business/{bpm,leave,reimburse}-service
```

### 前端三层结构

```
bidi router → pages (reagent component + re-frame) → api.cljs (fetch) → HTTP
                                                          ↑
                                              re-frame events/subs (状态管理)
```

> 前端 API 层是**单个** `src/cljs/com/ruoyi/frontend/api.cljs`（约 177 个 `defn`），没有独立的 `api/` 目录。

### 权限模型 (RBAC)

```
用户 ── N:N ── 角色 ── N:N ── 菜单(含 perms 按钮权限标识)
                  └── 数据权限范围: 全部/自定义/本部门/本部门及以下
```

### 中间件链 (RING)

```
请求 → 参数解析(ring-defaults) → wrap-jwt-auth(鉴权) → wrap-exception → wrap-operlog → muuntaja → 路由 → 控制器
```

---

## 三、业务模块清单

所有模块后端 API + 前端页面均已完成。以下是关键模块及其入口文件：

### 系统管理 (都在 `web/controllers/system/` + `pages/`)

| 模块     | 后端控制器           | 前端页面                  | 关键路由路径           |
|----------|----------------------|---------------------------|------------------------|
| 用户管理 | `system/user.clj`    | `pages/user.cljs`         | `/system/user`         |
| 角色管理 | `system/role.clj`    | `pages/role.cljs`         | `/system/role`         |
| 菜单管理 | `system/menu.clj`    | `pages/menu.cljs`         | `/system/menu`         |
| 部门管理 | `system/dept.clj`    | `pages/dept.cljs`         | `/system/dept`         |
| 岗位管理 | `system/post.clj`    | `pages/post.cljs`         | `/system/post`         |
| 字典管理 | `system/dict.clj`    | `pages/dict.cljs`         | `/system/dict`         |
| 参数管理 | `system/config.clj`  | `pages/config.cljs`       | `/system/config`       |
| 通知公告 | `system/notice.clj`  | `pages/notice.cljs`       | `/system/notice`       |
| 个人中心 | `system/profile.clj` | `pages/profile.cljs`      | `/system/user/profile` |

### 日志监控 (都在 `controllers/monitor/` + `pages/`)

| 模块           | 后端控制器                | 前端页面                | 路由路径              |
|----------------|---------------------------|-------------------------|-----------------------|
| 操作日志       | `controllers/monitor.clj` | `pages/oper_log.cljs`   | `/monitor/operlog`    |
| 登录日志       | `controllers/monitor.clj` | `pages/login_log.cljs`  | `/monitor/logininfor` |
| 在线用户       | `system/online.clj`       | `pages/online.cljs`     | `/monitor/online`     |
| 定时任务       | `controllers/job.clj`     | `pages/job.cljs`        | `/monitor/job`        |
| 服务监控       | `controllers/monitor.clj` | `pages/server.cljs`     | `/monitor/server`     |
| 缓存监控       | `system/cache.clj`        | `pages/cache.cljs`      | `/monitor/cache`      |
| 数据源监控     | `controllers/monitor.clj` | `pages/datasource.cljs` | `/monitor/datasource` |
| Integrant 监控 | `controllers/monitor.clj` | `pages/integrant.cljs`  | `/monitor/integrant`  |

### 工具与扩展

| 模块         | 后端控制器                 | 前端页面                  | 路由路径           |
|--------------|----------------------------|---------------------------|--------------------|
| Swagger 接口 | `controllers/common.clj`   | `pages/swagger.cljs`      | `/monitor/swagger` |

### 业务模块 (`controllers/business/` + `pages/`)

旧的方案管理/项目信息/资源库等 7 个演示性业务模块已于 2026-07 整体下线，由下方 BPM 与办公一体化模块取代。

### BPM 与办公模块 (`bpm/` + `controllers/business/` + `pages/business/`)

工作流引擎为嵌入式 **Flowable 8.0**（`src/clj/com/ruoyi/bpm/`：`core.clj` 高层封装 + `engine.clj` 引擎组件），独立存储于 H2：`jdbc:h2:file:./flowable`，可用 `FLOWABLE_JDBC_URL` / `FLOWABLE_ASYNC` 环境变量覆盖。流程引擎状态在 Flowable(H2)，业务记录在应用库，通过 business-key 关联。

| 模块           | 后端控制器                  | 领域层                      | 前端页面 (`pages/business/`)                     | 路由路径                  |
|----------------|-----------------------------|-----------------------------|--------------------------------------------------|---------------------------|
| 流程模型       | `business/bpm_mgmt.clj`     | `domain/business/bpm_mgmt.clj` | `bpm_model.cljs` / `bpm_model_editor.cljs`    | `/office/bpm/model` (+ `/edit`) |
| 流程定义       | `business/bpm_mgmt.clj`     | `domain/business/bpm_mgmt.clj` | `bpm_definition.cljs`                          | `/office/bpm/definition`  |
| 流程实例       | `business/bpm.clj`          | `domain/business/bpm.clj` / `bpm_flow.clj` | `bpm_instance.cljs`          | `/office/bpm/instance`    |
| 发起流程       | `business/bpm.clj`          | `domain/business/bpm.clj`   | `bpm_start.cljs`                                 | `/office/bpm/start`       |
| 待办任务       | `business/bpm.clj`          | `domain/business/bpm.clj`   | `bpm_todo.cljs`                                  | `/office/bpm/todo`        |
| 已办任务       | `business/bpm.clj`          | `domain/business/bpm.clj`   | `bpm_done.cljs`                                  | `/office/bpm/done`        |
| 抄送我的       | `business/bpm.clj`          | `domain/business/bpm.clj`   | `bpm_copy.cljs`                                  | `/office/bpm/copy`        |
| 流程运维/管理  | `business/bpm_mgmt.clj`     | `domain/business/bpm_mgmt.clj` | `bpm_ops.cljs` / `bpm_admin.cljs`           | `/office/bpm/instance-ops` 等 |
| 请假申请       | `business/leave.clj`        | `domain/business/leave.clj` | `leave.cljs`                                     | `/office/oa/leave`        |
| 报销申请       | `business/reimburse.clj`    | `domain/business/reimburse.clj` | `reimburse.cljs`                             | `/office/oa/reimburse`    |
| OA 日历        | `business/oa.clj`           | `domain/business/oa.clj`    | `oa_calendar.cljs`                               | `/office/oa/calendar`     |
| OA 会议        | `business/oa.clj`           | `domain/business/oa.clj`    | `oa_meeting.cljs`                                | `/office/oa/meeting`      |
| HRM 员工       | `business/hrm.clj`          | `domain/business/hrm.clj`   | `hrm.cljs`                                       | `/office/hrm/employee`    |
| CRM 客户       | `business/crm.clj`          | `domain/business/crm.clj`   | `crm.cljs`                                       | `/office/crm/customer`    |
| 报表           | `business/util.clj`         | —                           | `report.cljs`                                    | `/office/report`          |

其他 BPM 管理路由：`/office/bpm/{form, category, user-group, listener, expression, settings, instance-manager, task-manager}`。表单设计/渲染与流程设计器组件在 `src/cljs/com/ruoyi/frontend/components/`：`form_designer`、`form_field`、`form_section`、`form_render`、`bpm_flow_designer`、`bpmn_modeler`、`bpmn_viewer`。

测试：`test/clj/com/ruoyi/bpm/core_test.clj` + `test/clj/com/ruoyi/business/` 下 8 个测试文件（bpm_approval、bpm_e2e_fixes、bpm_integration、bpm_node_config、bpm_p0、bpm_p1、bpm_phase3、bpm_phase4）。

---

## 四、默认账号

| 账号  | 密码     | 角色                  |
|-------|----------|-----------------------|
| admin | admin123 | 超级管理员 (所有权限) |
| ry    | admin123 | 普通用户 (只读)       |

---

## 五、开发命令速查

### 启动项目

```bash
# 方式 1：一键启动前后端（推荐开发使用，自动处理端口冲突）
./start_dev.sh

# 停止所有服务（按 .dev-pids 精确停止，端口扫描兜底，不误杀外部进程）
./stop_dev.sh

# 方式 2：手动分步启动
# 后端 (HTTP 3000, nREPL 7000, SQLite)，在项目根目录下执行
clojure -M:dev -m com.ruoyi.core &
    # 或: bb run

# 仅启动 nREPL（不启动 HTTP，需配合其他启动方式）
bb nrepl

# 前端 (watch 模式，增量编译)
npx shadow-cljs watch app &
    # 或: pnpm run watch

# 访问
open http://localhost:3000
```

> **端口冲突提示**：`start_dev.sh` 会自动检测并清理当前项目旧进程；HTTP 与 nREPL 端口若被外部进程占用会自动向后寻找空闲端口（如 `3001` / `7001`）；shadow-cljs 默认从 `9630` 起监听，被占用时自动顺延，脚本从 `logs/frontend.log` 解析实际端口。手动启动时若遇到 `BindException`，请通过 `NREPL_PORT` / `PORT` 环境变量换端口。
>
> **脚本启动后务必看输出**：`start_dev.sh` 最后会打印 `📡 nREPL: localhost:$NREPL_PORT`，所有后续 `clj-nrepl-eval` 命令都必须使用该端口，而不是默认的 7000。

### 系统对外端口总览

开发/运行时会占用以下端口，启动前请确认没有冲突：

| 服务         | 默认端口 | 配置方式                                             | 说明                          |
|--------------|----------|------------------------------------------------------|-------------------------------|
| HTTP 后端    | `3000`   | `PORT` 环境变量 / `system.edn` `:server/http`        | 前端静态资源也由同一端口提供  |
| nREPL        | `7000`   | `NREPL_PORT` 环境变量 / `system.edn` `:nrepl/server` | **动态开发/测试的核心入口**   |
| shadow-cljs  | `9630` 起 | 被占用时自动顺延；`start_dev.sh` 从日志解析实际端口 | 前端 watch/devtools 服务      |
| MySQL (可选) | `3306`   | `resources/config.edn` / `JDBC_URL`                  | 切到 MySQL 时才需要外部实例   |

**使用 `start_dev.sh` 一键启动时**：脚本会自动处理端口冲突——只杀掉当前项目目录下的旧进程；如果端口仍被外部占用，HTTP 与 nREPL 会自动向后找空闲端口（如 3001、7001），shadow-cljs 自动顺延并记录实际端口。因此用脚本启动后，**务必以脚本输出的 HTTP / nREPL 端口为准**。停止服务统一用 `./stop_dev.sh`（优先按 `.dev-pids` 精确停止，端口扫描兜底）。

### 找到 nREPL 端口 (每次开发前先确认)

> **默认端口是 `7000`**，可通过环境变量 `NREPL_PORT` 覆盖，绑定地址默认 `127.0.0.1`。
> **优先用 nREPL 进行动态开发/测试**，避免频繁重启 JVM。

按优先级查找当前 nREPL 端口：

```bash
# 1. 自动发现当前目录下的 nREPL 服务（最推荐）
clj-nrepl-eval --discover-ports

# 2. 项目根目录的 .nrepl-port 文件（如果存在）
cat .nrepl-port

# 3. 如果通过 start_dev.sh 启动，以脚本输出为准
grep "nREPL:" logs/backend.log 2>/dev/null || echo "查看 start_dev.sh 启动时的输出"

# 4. 查看启动日志中的 nREPL 启动信息
grep -i "nrepl" log/backend.log

# 5. 查看 system.edn 中的默认配置
grep -A1 ":nrepl/server" resources/system.edn

# 6. 通过环境变量确认
env | grep NREPL

# 7. 通过进程/端口反查（macOS）
lsof -iTCP -sTCP:LISTEN -nP | grep -E "(7000|7001|java|clojure)"
# 或 netstat
netstat -anv | grep LISTEN | grep -E "700[0-9]"
```

**推荐做法**：把端口发现封装到环境变量，后续所有 `clj-nrepl-eval` 命令都使用它：

```bash
export NREPL_PORT=$(cat .nrepl-port 2>/dev/null || echo 7000)
clj-nrepl-eval -p $NREPL_PORT '(user/rd)'
```

### nREPL 开发标准流程

每次修改后端代码时，按以下三步走，无需重启 JVM：

```
1. 启动后端（nREPL 会随后端一起启动）
   → 2. 确认/发现当前 nREPL 端口
      → 3. 用 clj-nrepl-eval 执行热重载或测试
```

**手动启动时 nREPL 是后端的一部分**：执行 `clojure -M:dev -m com.ruoyi.core` 或 `bb run` 后，`system.edn` 中的 `:nrepl/server` 会同时启动 nREPL，不需要再单独运行 `bb nrepl`。`bb nrepl` 仅在「只需要一个裸 nREPL 会话、不启动 HTTP 服务」时使用。

**首次连接验证**（后端启动后先执行，确认 nREPL 可连）：

```bash
# 步骤 1：确认后端健康
curl -s http://localhost:3000/api/health

# 步骤 2：设置/发现 nREPL 端口
export NREPL_PORT=$(cat .nrepl-port 2>/dev/null || echo 7000)

# 步骤 3：验证 nREPL 连接
clj-nrepl-eval -p $NREPL_PORT '(+ 1 1)'
# 预期输出: 2
```

### nREPL 动态开发/热重载 (修改 .clj 文件后优先使用)

```bash
# 常用快捷别名
clj-nrepl-eval -p $NREPL_PORT '(user/rd)'          # 重载域服务 (最快，推荐)
clj-nrepl-eval -p $NREPL_PORT '(user/rroutes)'     # 重载路由定义
clj-nrepl-eval -p $NREPL_PORT '(user/ra)'          # 重载所有命名空间
clj-nrepl-eval -p $NREPL_PORT '(user/rr)'          # 完全重启 Integrant 系统 (halt → prep → go)
clj-nrepl-eval -p $NREPL_PORT '(user/reset-db)'    # 重置数据库
clj-nrepl-eval -p $NREPL_PORT '(user/migrate)'     # 运行迁移

# 完整函数名（与别名等价）
clj-nrepl-eval -p $NREPL_PORT '(user/reload-domain)'
clj-nrepl-eval -p $NREPL_PORT '(user/reload-routes)'
clj-nrepl-eval -p $NREPL_PORT '(user/reload-all)'
clj-nrepl-eval -p $NREPL_PORT '(user/reload-system)'
```

**重载策略速查：**

| 修改内容               | 推荐命令                  | 是否需要 `rr` |
|------------------------|---------------------------|---------------|
| 控制器 / 服务 / 域逻辑 | `rd` (reload-domain)      | 否            |
| 路由定义               | `rroutes` (reload-routes) | 否            |
| 中间件                 | `rm` (reload-middleware)  | 否            |
| HugSQL `.sql` 文件     | `rr` (reload-system)      | 是            |
| `resources/system.edn` | `rr` (reload-system)      | 是            |
| Integrant 组件结构     | `rr` (reload-system)      | 是            |

### 用 nREPL 做动态测试/探索

除了 `bb test`，**优先通过 nREPL 运行单测试、单命名空间或即时断言**，反馈更快：

```bash
# 运行单个测试命名空间
clj-nrepl-eval -p $NREPL_PORT '(clojure.test/run-tests (quote com.ruoyi.system.user-test))'

# 运行单个测试函数
clj-nrepl-eval -p $NREPL_PORT '(clojure.test/test-var (quote com.ruoyi.system.user-test/create-user-test))'

# 快速验证函数行为（示例）
clj-nrepl-eval -p $NREPL_PORT '(com.ruoyi.infra.security/md5 "admin123")'

# 直接查询数据库（query-fn 已挂载到 state/system）
clj-nrepl-eval -p $NREPL_PORT '((:db.sql/query-fn integrant.repl.state/system) :system/user-list {})'

# 热切换数据库（无需重启 JVM）
clj-nrepl-eval -p $NREPL_PORT '(user/swap-db! "jdbc:mysql://root:password@127.0.0.1:3308/ruoyi?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" :migration-dir "migrations")'
```

### 批量/CI 测试

```bash
# 后端测试 (SQLite)
bb test

# E2E (需要后端运行中)
npm run test:e2e

# 覆盖率
bb coverage
# 查看: target/coverage/index.html
```

### nREPL 故障排查

| 现象                                | 排查/解决                                                                                                                                                       |
|-------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Connection refused`                | 确认后端已启动；确认 `$NREPL_PORT` 与实际端口一致；查看 `log/backend.log`                                                                                       |
| 不知道端口是多少                    | 按上方"找到 nREPL 端口"步骤，优先 `cat .nrepl-port` 或 `grep nrepl log/backend.log`                                                                             |
| 热重载后行为未变                    | 确认修改的是 `src/clj` 下的源文件；若改的是 HugSQL `.sql` 或 `system.edn`，必须用 `rr`                                                                          |
| 端口 7000 被占用                    | 方案 A：启动时换端口 `NREPL_PORT=7001 clojure -M:dev -m com.ruoyi.core`；方案 B：使用 `start_dev.sh` 自动寻找空闲端口；之后所有 `clj-nrepl-eval` 必须使用该端口 |
| HTTP 3000 / shadow-cljs 9630+ 被占用 | 使用 `start_dev.sh` 会自动清理当前项目旧进程；HTTP 被外部占用会自动换端口，shadow-cljs 会自动顺延，均无需手动处理                                                  |
| nREPL 未启动                        | 检查 `resources/system.edn` 中 `:nrepl/server` 是否被注释；检查 `:dev` profile 是否包含 nREPL 依赖                                                              |

### 构建

```bash
npx shadow-cljs release app          # 前端生产构建
clojure -T:build all                  # 后端 uberjar (含前端静态文件)，等价于 bb uberjar
java -jar target/rouyi-standalone.jar # 运行
```

### Git 操作

```bash
git checkout ruoyi-template    # 开发分支
git push origin ruoyi-template # 推送
```

---

## 六、数据库兼容规则 (SQLite / MySQL)

项目同时支持 SQLite 和 MySQL，通过两个环境变量切换：

| 环境变量        | SQLite 默认值          | MySQL 用法                                            |
|-----------------|------------------------|-------------------------------------------------------|
| `JDBC_URL`      | `jdbc:sqlite:rouyi.db` | `jdbc:mysql://user:pass@host:3306/ruoyi?useSSL=false` |
| `MIGRATION_DIR` | `migrations-sqlite`    | `migrations`                                          |

### 迁移文件

```
resources/migrations-sqlite/     ← SQLite DDL
resources/migrations/            ← MySQL DDL
```

两个目录必须同步。关键差异：

| 场景      | SQLite                           | MySQL                                 |
|-----------|----------------------------------|---------------------------------------|
| 自增主键  | `INTEGER PRIMARY KEY`            | `BIGINT AUTO_INCREMENT PRIMARY KEY`   |
| 时间字段  | `TEXT DEFAULT CURRENT_TIMESTAMP` | `TIMESTAMP DEFAULT CURRENT_TIMESTAMP` |
| 布尔/状态 | `CHAR(1)` / `INTEGER`            | `CHAR(1)` / `TINYINT`                 |

**⚠️ Migratus 语句分隔符（`--;;`）**：JDBC 单次只能执行一条语句，迁移文件中的
**每一条** SQL 语句之后都必须紧跟一行 `--;;` 分隔符（不是只在文件末尾放一个）。
漏写不会报错——只静默执行第一条语句，且迁移仍被标记为已完成，后续查询报
`no such column` 之类错误时很难排查。写多语句迁移（多条 ALTER/INSERT/CREATE）后
务必核对每条语句后都有 `--;;`，并新建数据库验证 schema 完整。

> 踩坑实录：`202608260001-bpm-model-form-fields` 的 SQLite 版 3 条 ALTER 共用
> 一个结尾分隔符，只有第一条生效，导致 `/office/bpm/model` 页面因缺列一直加载。

### SQL 查询规则

1. **优先共用语法**: 用 `INSTR` 不要用 `||`，用 `LIMIT/OFFSET` 不要用 `ROWNUM`
2. **函数名不同时提供命名变体**: 在 HugSQL 文件中给两个名字，在 Clojure 层通过 `detect-db-type` 选择
3. **元数据在 Clojure 层分支**: `com.ruoyi.infra.db` 中 `get-tables`、`paginate-query` 等按 `:sqlite` / `:mysql` 分情况处理

### 修改后必须双库测试

```bash
# SQLite
rm -f rouyi.db && bb test

# MySQL
docker exec ruoyi-mysql mysql -uroot -ppassword -e \
  "DROP DATABASE IF EXISTS ruoyi; CREATE DATABASE ruoyi CHARACTER SET utf8mb4;"
JDBC_URL="jdbc:mysql://root:password@127.0.0.1:3308/ruoyi?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
MIGRATION_DIR=migrations bb test
```

---

## 七、前端开发规范 (重要)

### 7.1 使用 React Hooks，禁用 reagent/atom

```clojure
;; ❌ 错误
(let [expanded? (r/atom false)]
  [:div {:on-click #(reset! expanded? true)} ...])

;; ✅ 正确
(let [[expanded? set-expanded!] (hooks/use-state false)]
  [:div {:on-click #(set-expanded! true)} ...])
```

### 7.2 Ant Design 6 常见错误

#### Button icon 必须是 React 元素
```clojure
;; ❌
[antd/button {:icon "search"} "搜索"]
;; ✅
[antd/button {:icon (r/as-element [:> SearchOutlined])} "搜索"]
```

#### Dropdown menu label 必须 r/as-element
```clojure
;; ❌
{:key "id" :label [:div {:onClick f} [:span "文本"]]}
;; ✅
{:key "id" :label (r/as-element [:div {:onClick f} [:span "文本"]])}
```

#### Modal/Drawer 属性迁移
```clojure
;; ❌ 旧 API
[:width 700 :destroyOnClose true]
;; ✅ 新 API
{:style {:width 700} :destroyOnHidden true}
```

#### message 必须通过 App.useApp 获取
```clojure
;; ❌
(.success js/antd.message "成功")
;; ✅
;; 在 antd.cljs 中定义 use-app-message 获取 message-api atom
;; 然后调用 (antd/success! "成功")
```

#### Card bodyStyle 改用 styles.body
```clojure
;; ❌
{:bodyStyle {:padding 12}}
;; ✅
{:styles {:body {:padding 12}}}
```

#### Progress 属性迁移
```clojure
;; ❌
{:strokeWidth 10 :trailColor "#f0f0f0"}
;; ✅
{:size 10 :railColor "#f0f0f0"}
```

### E2E 测试 (Playwright)

已接入 Playwright 对主要功能做端到端验证，默认跑在 `http://localhost:3000`。

```bash
# 安装浏览器（首次）
pnpm exec playwright install chromium

# 运行全部 E2E 用例并生成 HTML/JSON 报告
pnpm run test:e2e

# 查看 HTML 报告
pnpm run test:e2e:report
```

### 7.3 后端分页参数

前端传给后端的列表接口分页参数必须是 `page` / `size`（不是 `pageNum`/`pageSize`/`page-num`/`page-size`）。

### 7.4 菜单权限不可写死

左侧菜单必须从接口动态获取，按当前登录用户角色/权限动态构建。任何修改代码不得绕过、替换或破坏权限菜单加载流程。需要复刻截图外观时只能调整视觉样式。

### 7.5 自定义表单项需要手动读写

antd 无法自动向 Reagent 函数组件注入 `value`/`onChange`，需要通过 `Form.useForm` 实例手动读写：

```clojure
(let [[form] (antd/form-use-form)]
  [antd/form-item {:label "归属部门"}
   [dept-tree-select {:value (.getFieldValue form "dept_id")
                      :on-change #(.setFieldsValue form #js {"dept_id" %})}]])
```

### 7.6 列显隐检查

```clojure
;; ❌
(when (:user_id columns-config) ...)  ;; map 永远 truthy
;; ✅
(when (get-in columns-config [:user_id :visible?]) ...)
```

### 7.7 外部组件必须先导入再定义

```clojure
;; 在 antd.cljs 中:
(def switch (r/adapt-react-class Switch))
;; 然后才能在页面中用 [antd/switch]
```

---

## 八、项目文档索引

| 文档                                                 | 内容                                      |
|------------------------------------------------------|-------------------------------------------|
| [README.md](./README.md)                             | 完整项目调研文档（推荐新人先看此文档）    |
| [CHANGELOG.md](./CHANGELOG.md)                       | 版本演进历史（2026-09-20 回溯整理）       |
| [docs/guides/add-new-module.md](docs/guides/add-new-module.md) | 新增业务模块端到端指南（迁移→SQL→领域→控制器→前端→权限→测试） |
| [ROADMAP.md](./ROADMAP.md)                           | 功能齐平路线图（**已归档**，2026-06）     |
| [GAP_ANALYSIS.md](./GAP_ANALYSIS.md)                 | RuoYi-Vue 逐项对比分析（**已归档**，2026-06） |
| [RUOYI_VUE_COMPARISON.md](./RUOYI_VUE_COMPARISON.md) | 更详细的功能对比（**已归档**，2026-06）   |
| [build.clj](./build.clj)                             | Uberjar 构建配置                          |
| [docs/training/](./docs/training/)                   | 5 节开发培训 HTML 课件（基于 6 月代码，BPM 之前） |
| [docs/design/BPM_GAP_PLAN.md](./docs/design/BPM_GAP_PLAN.md) | BPM 对齐 ruoyi-office-vben 的差距补全规格（Phase 1-4 已完成） |
| [docs/design/BPM_OA_DESIGN.md](./docs/design/BPM_OA_DESIGN.md) | BPM/OA 办公一体化顶层设计 + 逐日 changelog |
| [test/coverage-report.md](./test/coverage-report.md) | 覆盖率报告（**已归档**，2026-06-13）      |

---

## 九、UI 视觉复刻规则

当需要参考 RuoYi-Vue 并进行 UI 复刻时：

1. **参考源码**: https://gitee.com/y_project/RuoYi-Vue/tree/master/ruoyi-ui
2. **颜色对应**:
   - 主色 (Primary): `#409eff` (Element UI 蓝)
   - 成功 (Success): `#67c23a`
   - 危险 (Danger): `#f56c6c`
   - 信息 (Info): `#909399`
   - 警告 (Warning): `#e6a23c`
3. **布局**:
   - 左侧部门树 200px，右侧内容区 flex: 1
   - 搜索栏用 `:ghost true` 风格卡片
   - 操作顺序: 确认对话框 → API 调用 → 成功提示 → 刷新列表

---

## 十、性能与安全注意事项

- **JWT 密钥** (`JWT_SECRET`): 生产环境必须通过环境变量设置，不要使用默认值
- **数据库**: 生产环境推荐 MySQL/PostgreSQL，SQLite 仅用于开发
- **日志**: 操作日志自动记录请求/响应，无需手动插入
- **认证**: 所有 `/api/system/*`（含日志监控、在线用户、定时任务）、`/api/business/*` 等系统路由均挂 `auth-middleware {:required? true}`（JWT 鉴权，见 `web/middleware/auth.clj`）
- **缓存**: 使用内存缓存，重启后清除。缓存键按模块命名空间隔离

---

## 会话结束检查清单

```
[ ] git status                              # 检查变更
[ ] git add <files> && git commit -m "..."  # 提交代码
[ ] git push origin ruoyi-template          # 推送到远程
[ ] 确认 nREPL 端口                         # cat .nrepl-port 2>/dev/null || echo 7000
[ ] 验证 nREPL 可连接                       # clj-nrepl-eval -p $NREPL_PORT '(+ 1 1)'
[ ] 检查后端是否正常运行                    # curl http://localhost:3000/api/health
[ ] HANDOFF（若工作未完成）                 # gt mail send...
```
