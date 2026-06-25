# RuoYi-Clojure Agent Instructions

> **开发分支**: `ruoyi-template`
> **仓库**: RedCreationTech/ruoyi_clojure (git@github.com:RedCreationTech/ruoyi_clojure.git)
> **当前 Git**: 1 commit ahead of main (commit `af08efe` - "Fix user management actions")
> **参考原版**: https://gitee.com/y_project/RuoYi-Vue (v3.9.2)

---

## 一、项目概况

RuoYi-Clojure 是基于 **Kit 框架** + **Reagent 2** + **Ant Design 6** 构建的 RuoYi 风格全栈管理后台。完整实现了 18 个 RuoYi-Vue 功能模块，完成度 ~95%。

### 技术栈速查

| 层 | 技术 | 端口/工具 |
|----|------|-----------|
| 后端 | Clojure 1.12 + Kit (Integrant, Reitit) | HTTP 3000, nREPL 7000 |
| 数据库 | SQLite (默认) / MySQL / PostgreSQL | JDBC URL + MIGRATION_DIR 切换 |
| 前端 | ClojureScript + Reagent 2 + re-frame + Ant Design 6 | shadow-cljs watch app |
| CSS | 无独立 CSS 框架 — 全部通过 antd ConfigProvider token 和内联 style 控制 | — |
| 构建 | shadow-cljs (前端) + tools.build uberjar (后端) | — |
| 任务调度 | Quartz (kit-quartz 集成) | — |
| 工作流 | Flowable 7.1 (BPMN 2.0) | 独立 H2 内嵌数据库 |
| 测试 | clojure.test + Playwright E2E + Cloverage | bb test / npm run test:e2e |

### 物理路径

```
/home/kevin/gt/ruoyi/                    ← 项目 rig 根目录
  mayor/rig/                             ← 你在的目录（git 克隆）
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
                                                              ├── gen-routes
                                                              ├── business-routes
                                                              ├── workflow-routes
                                                              └── captcha-routes
db.sql/migrations → db.sql/connection → db.sql/query-fn → 16+ domain services
                                              workflow/engine (Flowable)
```

### 前端三层结构

```
bidi router → pages (reagent component + re-frame) → api.cljs (fetch) → HTTP
                                                          ↑
                                              re-frame events/subs (状态管理)
```

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

| 模块 | 后端控制器 | 前端页面 | 关键路由路径 |
|------|-----------|---------|-------------|
| 用户管理 | `system/user.clj` | `pages/user.cljs` | `/system/user` |
| 角色管理 | `system/role.clj` | `pages/role.cljs` | `/system/role` |
| 菜单管理 | `system/menu.clj` | `pages/menu.cljs` | `/system/menu` |
| 部门管理 | `system/dept.clj` | `pages/dept.cljs` | `/system/dept` |
| 岗位管理 | `system/post.clj` | `pages/post.cljs` | `/system/post` |
| 字典管理 | `system/dict.clj` | `pages/dict.cljs` | `/system/dict` |
| 参数管理 | `system/config.clj` | `pages/config.cljs` | `/system/config` |
| 通知公告 | `system/notice.clj` | `pages/notice.cljs` | `/system/notice` |
| 文件管理 | `system/file.clj` | `pages/file_manager.cljs` | `/system/file` |
| 个人中心 | `system/profile.clj` | `pages/profile.cljs` | `/system/user/profile` |

### 日志监控 (都在 `controllers/monitor/` + `pages/`)

| 模块 | 后端控制器 | 前端页面 | 路由路径 |
|------|-----------|---------|---------|
| 操作日志 | `controllers/monitor.clj` | `pages/oper_log.cljs` | `/monitor/operlog` |
| 登录日志 | `controllers/monitor.clj` | `pages/login_log.cljs` | `/monitor/logininfor` |
| 在线用户 | `system/online.clj` | `pages/online.cljs` | `/monitor/online` |
| 定时任务 | `controllers/job.clj` | `pages/job.cljs` | `/monitor/job` |
| 服务监控 | `controllers/monitor.clj` | `pages/server.cljs` | `/monitor/server` |
| 缓存监控 | `system/cache.clj` | `pages/cache.cljs` | `/monitor/cache` |
| 数据源监控 | `controllers/monitor.clj` | `pages/datasource.cljs` | `/monitor/datasource` |
| Integrant 监控 | `controllers/monitor.clj` | `pages/integrant.cljs` | `/monitor/integrant` |

### 工具与扩展

| 模块 | 后端控制器 | 前端页面 | 路由路径 |
|------|-----------|---------|---------|
| 代码生成 | `controllers/gen.clj` | `pages/gen.cljs` | `/tool/build` |
| 表单构建器 | `system/form_template.clj` | `pages/form_builder.cljs` | — |
| Swagger 接口 | `controllers/common.clj` | `pages/swagger.cljs` | `/monitor/swagger` |
| 工作流定义 | `controllers/workflow.clj` | `pages/workflow/definitions.cljs` | `/workflow/definitions` |
| 工作流设计器 | — | `pages/workflow/designer.cljs` | `/workflow/designer` |
| 待办任务 | `controllers/workflow.clj` | `pages/workflow/tasks.cljs` | `/workflow/tasks` |

### 业务模块 (`controllers/business/` + `pages/`)

| 模块 | 路由路径 |
|------|---------|
| 方案管理 | `/solution` |
| 项目信息管理 | `/project/info` |
| 标准规范 | `/resource/standard` |
| 向量知识库 | `/resource/vector-kb` |
| 结构化知识库 | `/resource/structured-kb` |
| 优秀案例库 | `/resource/case` |
| 通用图集库 | `/resource/atlas` |

---

## 四、默认账号

| 账号 | 密码 | 角色 |
|------|------|------|
| admin | admin123 | 超级管理员 (所有权限) |
| ry | admin123 | 普通用户 (只读) |

---

## 五、开发命令速查

### 启动项目

```bash
# 后端 (port 3000, nREPL 7000, SQLite)
cd /home/kevin/gt/ruoyi/mayor/rig
clojure -M:dev -m com.ruoyi.core &
    # 或: bb run

# 前端 (watch 模式，增量编译)
npx shadow-cljs watch app &

# 访问
open http://localhost:3000
```

### nREPL 热重载 (修改 .clj 文件后必须执行)

```bash
clj-nrepl-eval -p 7000 '(user/rd)'          # 重载域服务 (最快，推荐)
clj-nrepl-eval -p 7000 '(user/rroutes)'     # 重载路由定义
clj-nrepl-eval -p 7000 '(user/ra)'          # 重载所有命名空间
clj-nrepl-eval -p 7000 '(user/rr)'          # 完全重启 Integrant 系统 (halt → prep → go)
clj-nrepl-eval -p 7000 '(user/reset-db)'    # 重置数据库
clj-nrepl-eval -p 7000 '(user/migrate)'     # 运行迁移
```

**何时需要 `rr` (完整重启)：**
- HugSQL `.sql` 文件变更
- `resources/system.edn` 配置变更
- Integrant 组件结构变更

**何时只需 `rd` (重载 Domain)：**
- 控制器、服务、域逻辑变更

### 测试

```bash
# 后端测试 (SQLite)
bb test

# E2E (需要后端运行中)
npm run test:e2e

# 覆盖率
bb coverage
# 查看: target/coverage/index.html
```

### 构建

```bash
npx shadow-cljs release app          # 前端生产构建
clojure -T:build all                  # 后端 uberjar (含前端静态文件)
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

| 环境变量 | SQLite 默认值 | MySQL 用法 |
|----------|--------------|------------|
| `JDBC_URL` | `jdbc:sqlite:rouyi.db` | `jdbc:mysql://user:pass@host:3306/ruoyi?useSSL=false` |
| `MIGRATION_DIR` | `migrations-sqlite` | `migrations` |

### 迁移文件

```
resources/migrations-sqlite/     ← SQLite DDL
resources/migrations/            ← MySQL DDL
```

两个目录必须同步。关键差异：

| 场景 | SQLite | MySQL |
|------|--------|-------|
| 自增主键 | `INTEGER PRIMARY KEY` | `BIGINT AUTO_INCREMENT PRIMARY KEY` |
| 时间字段 | `TEXT DEFAULT CURRENT_TIMESTAMP` | `TIMESTAMP DEFAULT CURRENT_TIMESTAMP` |
| 布尔/状态 | `CHAR(1)` / `INTEGER` | `CHAR(1)` / `TINYINT` |

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

| 文档 | 内容 |
|------|------|
| [README.md](./README.md) | 完整项目调研文档（推荐新人先看此文档） |
| [ROADMAP.md](./ROADMAP.md) | 功能齐平路线图（面向 RuoYi-Vue 封面规划） |
| [GAP_ANALYSIS.md](./GAP_ANALYSIS.md) | RuoYi-Vue 逐项对比分析（95% 完成） |
| [REMAINING.md](./REMAINING.md) | 剩余工作清单（按 Phase 划分） |
| [RUOYI_VUE_COMPARISON.md](./RUOYI_VUE_COMPARISON.md) | 更详细的功能对比（同上但更细） |
| [build.clj](./build.clj) | Uberjar 构建配置 |
| [docs/training/](./docs/training/) | 5 节开发培训 HTML 课件 |
| [test/coverage-report.md](./test/coverage-report.md) | 最新覆盖率报告 |

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
- **认证**: 所有 `/api/system/*` 和 `/api/monitor/*` 路由通过 `require-auth` 中间件保护
- **缓存**: 使用内存缓存，重启后清除。缓存键按模块命名空间隔离

---

## 会话结束检查清单

```
[ ] git status                              # 检查变更
[ ] git add <files> && git commit -m "..."  # 提交代码
[ ] git push origin ruoyi-template          # 推送到远程
[ ] 检查后端是否正常运行                    # curl http://localhost:3000/api/health
[ ] HANDOFF（若工作未完成）                 # gt mail send...
```
