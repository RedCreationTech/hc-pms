# 若依 Clojure 全栈管理系统 (RuoYi-Clojure)

> **开发分支**: `ruoyi-template`
> **仓库**: [RedCreationTech/ruoyi_clojure](https://github.com/RedCreationTech/ruoyi_clojure)
> **参考**: [RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue) (v3.9.2) — 功能对照完成度 ~95%
> **更新于**: 2026-09-20

基于 **Kit 框架** + **ClojureScript** + **Reagent 2** + **Ant Design 6** 构建的 RuoYi 风格全栈管理后台，对齐原版若依框架核心功能。

---

## 目录

- [技术栈](#技术栈)
- [架构概览](#架构概览)
- [源文件全览](#源文件全览)
- [核心功能](#核心功能)
- [权限模型](#权限模型)
- [数据流](#数据流)
- [分支策略](#分支策略)
- [环境配置](#环境配置)
- [快速开始](#快速开始)
- [后端开发 (nREPL 热重载)](#后端开发-nrepl-热重载)
- [前端开发 (shadow-cljs)](#前端开发-shadow-cljs)
- [数据库兼容 (SQLite / MySQL)](#数据库兼容-sqlite--mysql)
- [测试](#测试)
- [API 概览](#api-概览)
- [开发约定](#开发约定)
- [当前完成度状态](#当前完成度状态)

---

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 语言 | Clojure 1.12, ClojureScript | — |
| 后端框架 | Kit (Integrant, Reitit 0.10, Ring 1.15) | — |
| 数据库 | SQLite (默认), MySQL 8, PostgreSQL | — |
| 数据库访问 | next.jdbc, conman (HugSQL), Migratus | — |
| 连接池 | HikariCP 5.1 | — |
| 安全 | Buddy (JWT + bcrypt 密码) | buddy 3.x |
| 序列化 | Muuntaja (JSON via cheshire, Transit) | — |
| 前端框架 | Reagent 2.0 (React 19), re-frame | — |
| 前端 UI | Ant Design 6 (React 组件) | — |
| 前端构建 | shadow-cljs (NPM via package.json) | — |
| 任务调度 | Quartz (kit-quartz cronut) | 2.3.2 |
| 工作流引擎 | Flowable 8.0.0 (BPMN 2.0) | — |
| 验证 | Malli 0.17 | — |
| 测试 | clojure.test, Playwright (E2E) | — |
| 覆盖率 | Cloverage | — |

---

## 架构概览

### 系统层级

```
┌──────────────────────────────────────────────────────────────┐
│                    前端 (ClojureScript)                        │
│  ┌─────────┐  ┌──────────┐  ┌────────────┐  ┌────────────┐  │
│  │ Reagent  │  │ re-frame │  │  Ant Design│  │   Bidi     │  │
│  │ (React)  │  │ 事件/订阅  │  │  6 组件    │  │  路由      │  │
│  └────┬────┘  └────┬─────┘  └─────┬──────┘  └─────┬──────┘  │
│       └────────────┼──────────────┼───────────────┘          │
│                    ▼              ▼                           │
│              ┌──────────────────────────┐                    │
│              │    HTTP API 调用 (fetch)   │                    │
│              └──────────┬───────────────┘                    │
└─────────────────────────┼────────────────────────────────────┘
                          │    端口 3000 (同一 HTTP 服务器)
┌─────────────────────────┼────────────────────────────────────┐
│                    后端 (Clojure, Kit/Integrant)               │
│  ┌──────────────────────┴──────────────────────────────┐     │
│  │            Undertow Web Server (端口 3000)           │     │
│  └──────────────────────┬──────────────────────────────┘     │
│  ┌──────────────────────┴──────────────────────────────┐     │
│  │              Ring Middleware Stack                    │     │
│  │  ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐  │     │
│  │  │ 参数  │→│ JWT   │→│ 异常  │→│ 操作  │→│ 格式  │  │     │
│  │  │ 解析  │  │ 认证  │  │ 处理  │  │ 日志  │  │ 转换  │  │     │
│  │  └──────┘  └──────┘  └──────┘  └──────┘  └──────┘  │     │
│  └──────────────────────┬──────────────────────────────┘     │
│  ┌──────────────────────┴──────────────────────────────┐     │
│  │              Reitit Router (/api/*)                   │     │
│  │  ┌──────┐  ┌──────┐  ┌──────────┐  ┌─────────┐  ┌────────┐  │     │
│  │  │ Auth │  │System│  │ Business │  │ Captcha │  │ Common │  │     │
│  │  └──┬───┘  └──┬───┘  └────┬─────┘  └────┬────┘  └───┬────┘  │     │
│  │  └─┼─────────┼──────────────┼───────────────┼─────────────┼────┘     │
│  │  ┌─┴─────────┴──────────────┴───────────────┴─────────────┴────┐     │
│  │         Web Controllers (system / business)                │     │
│  │  └───────────────────────────┬───────────────────────────┘  │     │
│  │  ┌───────────────────────────┴───────────────────────────┐  │     │
│  │              Domain Services                               │     │
│  │  User  Role  Menu  Dept  Post  Dict  Config  Log          │     │
│  │  BPM  BPM-Flow  BPM-Mgmt  OA  HRM  CRM  Leave  Reimburse  │     │
│  │  └───────────────────────────┬───────────────────────────┘  │     │
│  │  ┌───────────────────────────┴───────────────────────────┐  │     │
│  │     Infrastructure (Security, Cache, Online, DB, Sched)   │     │
│  │  └───────────────────────────┬───────────────────────────┘  │     │
│  │  ┌───────────────────────────┴───────────────────────────┐  │     │
│  │  ┌──────┐  ┌──────┐  ┌──────────┐  ┌───────────────┐      │     │
│  │  │SQLite│  │MySQL │  │ PostgreSQL│  │  缓存 (内存)   │      │     │
│  │  └──────┘  └──────┘  └──────────┘  └───────────────┘      │     │
│  │  └──────────────────────────────────────────────────────────┘  │     │
└──────────────────────────────────────────────────────────────────┘
```

### Integrant 组件依赖图

系统启动时通过 `resources/system.edn` 声明式组装所有组件。关键组件链：

```
nrepl/server ─┐
               ├── server/http ── handler/ring ── router/core ── routes/api ── system-routes
               │                                                    ├── auth-routes
               │                                                    ├── business-routes
               │                                                    ├── captcha-routes
               │                                                    └── common-routes
               │
db.sql/migrations ── db.sql/connection ──┬── db.sql/query-fn ──┬── user-service
                                         │                     ├── role-service
                                         │                     ├── menu-service
                                         │                     ├── (etc 16+ services)
                                         │                     └── job-scheduler
                                         │
                                    bpm/engine (Flowable 8.0, 独立 H2 文件数据库)
```

---

## 源文件全览

### 后端源码 (`src/clj/com/ruoyi/`)

#### 应用入口与配置

| 文件 | 职责 | 关键函数 |
|------|------|----------|
| `core.clj` | 应用入口，Integrant 启动/停止 | `-main`, `start-app`, `stop-app` |
| `config.clj` | 读取 `system.edn` 配置 | `system-config` |

#### 领域服务层 (`domain/`)

| 文件 | 职责 | 关键函数 |
|------|------|----------|
| `domain/system.clj` | 所有系统服务 Integrant 组件注册 (init-key) | `ig/init-key :app.system/*` |
| `domain/system/user.clj` | 用户 CRUD、查询、状态切换 | `list-users`, `create-user!`, `update-user!`, `delete-user!` |
| `domain/system/role.clj` | 角色 CRUD、菜单权限分配、数据权限 | `list-roles`, `create-role!`, `update-role-menu!`, `update-data-scope!` |
| `domain/system/menu.clj` | 菜单树管理、权限检查 | `list-menus`, `build-menu-tree`, `check-perms` |
| `domain/system/dept.clj` | 部门树管理 | `list-depts`, `build-dept-tree`, `create-dept!` |
| `domain/system/post.clj` | 岗位 CRUD | `list-posts`, `create-post!`, `update-post!` |
| `domain/system/dict.clj` | 字典类型与字典数据 | `list-dict-types`, `list-dict-data`, `create-dict-type!` |
| `domain/system/config.clj` | 系统参数配置 | `list-configs`, `get-config-by-key`, `update-config!` |
| `domain/system/log.clj` | 操作日志、登录日志 | `list-oper-logs`, `list-login-logs`, `log-operation!`, `log-login!` |

#### 办公一体化领域服务 (`domain/business/`)

| 文件 | 职责 |
|------|------|
| `domain/business/bpm.clj` | BPM 领域服务：流程分类/模型/定义版本/实例/任务/动态表单/加签减签/撤回退回/抄送 |
| `domain/business/bpm_flow.clj` | BPMN XML ↔ 流程节点树 双向转换 |
| `domain/business/bpm_mgmt.clj` | BPM 管理套件：用户分组/流程监听器/流程表达式/流程设置 (通用 CRUD) |
| `domain/business/oa.clj` | OA 协同办公 (日程/会议) |
| `domain/business/leave.clj` | 请假申请 (业务记录 + BPM 审批流集成) |
| `domain/business/reimburse.clj` | 报销申请 (业务记录 + BPM 审批流集成) |
| `domain/business/hrm.clj` | HRM 人力资源 (员工) |
| `domain/business/crm.clj` | CRM 客户管理 |

#### 基础设施层 (`infra/`)

| 文件 | 职责 | 关键函数 |
|------|------|----------|
| `infra/security.clj` | JWT 签发/验证, bcrypt 密码哈希 | `generate-token`, `parse-token`, `hash-password`, `verify-password` |
| `infra/db.clj` | 数据库抽象层，SQLite/MySQL 双库兼容 | `detect-db-type`, `paginate-query`, `get-tables`, `get-table-columns` |
| `infra/cache.clj` | 内存缓存 (clojure.core.cache) | `get-cache`, `put-cache!`, `list-cache-keys`, `clear-cache!` |
| `infra/cron.clj` | CRON 调度工具 | `cron->description` |
| `infra/data_perm.clj` | 数据权限过滤 (部门级) | `apply-data-scope` |
| `infra/datasource.clj` | 数据源监控 (HikariCP) | `get-datasource-status`, `list-queries` |
| `infra/online.clj` | 在线用户管理 + 令牌黑名单 | `heartbeat!`, `blacklisted?`, `list-online-users`, `force-logout!` |
| `infra/scheduler.clj` | Quartz 任务调度封装 | `create-job!`, `pause-job!`, `resume-job!`, `run-job-now!` |

#### Web 层 (`web/`)

| 文件 | 职责 |
|------|------|
| `web/handler.clj` | Ring handler 组装，Integrant init-key |
| `web/routes/api.clj` | 主路由表，聚合所有子路由 |
| `web/routes/auth.clj` | 登录/登出/注册/验证码路由 |
| `web/routes/system.clj` | 系统管理路由 (用户/角色/菜单/部门/岗位/字典/配置/公告/日志/监控/定时任务) |
| `web/routes/business.clj` | 办公一体化路由 (BPM 流程/OA/请假/报销/HRM/CRM/报表) |
| `web/routes/captcha.clj` | 验证码路由 |
| `web/routes/common.clj` | 通用路由 (个人中心, 文件上传/下载) |
| `web/routes/utils.clj` | 路由工具函数 |
| `web/middleware/auth.clj` | JWT 认证 + 权限中间件 |
| `web/middleware/core.clj` | 核心中间件 (trace 等) |
| `web/middleware/exception.clj` | 异常捕获中间件 |
| `web/middleware/formats.clj` | Muuntaja 格式配置 |
| `web/middleware/operlog.clj` | 操作日志中间件 |

#### 控制器 (`web/controllers/`)

| 文件 | 对应功能 |
|------|----------|
| `auth.clj` | 登录验证、获取用户信息、注册 |
| `captcha.clj` | 验证码生成 |
| `health.clj` | 健康检查 |
| `common.clj` | 通用接口 (文件上传/下载等) |
| `job.clj` | 定时任务管理 |
| `monitor.clj` | 服务器/数据源/缓存/Integrant 监控 |
| `register.clj` | 用户注册 |
| `system/user.clj` | 用户管理 API |
| `system/role.clj` | 角色管理 API |
| `system/menu.clj` | 菜单管理 API |
| `system/dept.clj` | 部门管理 API |
| `system/post.clj` | 岗位管理 API |
| `system/dict.clj` | 字典管理 API |
| `system/config.clj` | 参数管理 API |
| `system/notice.clj` | 通知公告 API |
| `system/log.clj` | 日志查询 API |
| `system/online.clj` | 在线用户 API |
| `system/profile.clj` | 个人中心 API |
| `system/cache.clj` | 缓存管理 API |
| `system/import_export.clj` | 数据导入导出 API (用户/角色/菜单/字典/部门/岗位/参数 CSV) |
| `business/bpm.clj` | BPM 流程 API (模型/定义/实例/任务/表单) |
| `business/bpm_mgmt.clj` | BPM 管理 API (用户分组/监听器/表达式/设置) |
| `business/oa.clj` | OA 日程/会议 API |
| `business/leave.clj` | 请假申请 API |
| `business/reimburse.clj` | 报销申请 API |
| `business/hrm.clj` | HRM 员工 API |
| `business/crm.clj` | CRM 客户 API |
| `business/util.clj` | 办公模块通用工具 |

#### BPM 引擎 (`bpm/`)

| 文件 | 职责 |
|------|------|
| `bpm/engine.clj` | Flowable ProcessEngine Integrant 组件初始化/销毁 (独立 H2 文件数据库) |
| `bpm/core.clj` | Flowable 引擎高层封装 (部署/启动/任务/变量/流转操作) |

另见 `task.clj` (定时任务示例目标函数) 与 `obs/` (OpenTelemetry 追踪封装)。

#### Integrant 工具 (`integrant/`)

| 文件 | 职责 |
|------|------|
| `integrant/state.clj` | 系统状态 atom (用于热重载) |
| `integrant/trace.clj` | 组件调用追踪 (调试/监控) |

### 前端源码 (`src/cljs/com/ruoyi/frontend/`)

#### 核心文件

| 文件 | 职责 |
|------|------|
| `app.cljs` | 应用入口，ConfigProvider + 页面路由 |
| `router.cljs` | 前端路由 (bidi)，30+ 页面路由映射 |
| `antd.cljs` | Ant Design 6 React 组件适配，message API 封装 |
| `api.cljs` | HTTP 客户端 (fetch 封装) |
| `events.cljs` | re-frame 事件注册 |
| `subs.cljs` | re-frame 订阅注册 |
| `db.cljs` | re-frame app-db 初始状态 |
| `theme.cljs` | 亮色/暗色主题配置，antd token 覆盖 |

#### 页面组件 (`pages/`)

| 文件 | 对应 RuoYi 模块 | 状态 |
|------|-----------------|------|
| `login.cljs` | 登录页 | ✅ |
| `layout.cljs` | 主布局 (侧边栏/顶栏/Tab) | ✅ |
| `dashboard.cljs` | 首页仪表盘 | ✅ |
| `user.cljs` | 用户管理 | ✅ |
| `role.cljs` | 角色管理 | ✅ |
| `menu.cljs` | 菜单管理 | ✅ |
| `dept.cljs` | 部门管理 | ✅ |
| `post.cljs` | 岗位管理 | ✅ |
| `dict.cljs` | 字典管理 | ✅ |
| `config.cljs` | 参数管理 | ✅ |
| `notice.cljs` | 通知公告 | ✅ |
| `oper_log.cljs` | 操作日志 | ✅ |
| `login_log.cljs` | 登录日志 | ✅ |
| `online.cljs` | 在线用户 | ✅ |
| `job.cljs` | 定时任务 | ✅ |
| `swagger.cljs` | Swagger 文档嵌入 | ✅ |
| `server.cljs` | 服务监控 | ✅ |
| `cache.cljs` | 缓存监控 | ✅ |
| `datasource.cljs` | 数据源监控 | ✅ |
| `integrant.cljs` | Integrant 依赖图 | ✅ |
| `profile.cljs` | 个人中心 | ✅ |

#### 办公一体化页面 (`pages/business/`, 路由前缀 `/office/`)

| 文件 | 对应模块 |
|------|----------|
| `bpm_model.cljs` / `bpm_model_editor.cljs` | 流程模型列表 / 流程模型设计器 (BPMN + 动态表单 + 节点配置) |
| `bpm_definition.cljs` | 流程定义版本管理 |
| `bpm_instance.cljs` | 我的流程 (实例) |
| `bpm_admin.cljs` / `bpm_ops.cljs` | 流程管理后台 (任务/实例管理、运维操作) |
| `bpm_todo.cljs` / `bpm_done.cljs` / `bpm_copy.cljs` | 我的待办 / 我的已办 / 我的抄送 |
| `bpm_start.cljs` | 发起流程 |
| `leave.cljs` / `reimburse.cljs` | 请假申请 / 报销申请 |
| `oa_calendar.cljs` / `oa_meeting.cljs` | OA 日程 / 会议 |
| `hrm.cljs` | HRM 员工管理 |
| `crm.cljs` | CRM 客户管理 |
| `report.cljs` | 办公一体化统计报表 |

#### 可复用组件 (`components/`)

| 文件 | 职责 |
|------|------|
| `action_menu.cljs` | 操作列下拉菜单 |
| `bpm_flow_designer.cljs` | BPM 流程节点树设计器 (加签/监听器/条件配置) |
| `bpmn_modeler.cljs` / `bpmn_viewer.cljs` | bpmn-js 建模器 / 只读流程图查看器 |
| `dept_tree_select.cljs` | 部门树选择器 |
| `dict_tag.cljs` | 字典标签渲染 |
| `error_boundary.cljs` | React 错误边界 |
| `form_designer.cljs` / `form_render.cljs` | 动态表单设计器 / 表单运行时渲染 |
| `form_field.cljs` / `form_section.cljs` | 通用表单字段 / 表单分区布局 |
| `icon_picker.cljs` | 图标选择器 |
| `layout_settings.cljs` | 布局设置面板 |
| `page_card.cljs` | 页面卡片容器 |
| `page_search.cljs` | 搜索栏组件 |
| `page_toolbar.cljs` | 工具栏组件 (新增/修改/删除按钮) |
| `pagination.cljs` | 分页组件 |
| `right_toolbar.cljs` | 右侧工具栏 (列显隐/刷新) |
| `search_input.cljs` | 搜索输入框 |
| `status_tag.cljs` | 状态标签 |
| `theme_switcher.cljs` | 主题切换器 |

### 测试 (`test/clj/com/ruoyi/`, 约 440 个 `deftest`)

| 目录 | 文件数 | 覆盖模块 |
|------|--------|----------|
| `core_test.clj` / `task_test.clj` | 2 | 应用启动与基本路由、定时任务目标函数 |
| `domain/system/` | 8 | user, role, menu, dept, post, dict, config, log |
| `infra/` | 6 | cache, data_perm, db, online, scheduler, security |
| `bpm/` + `business/` | 9 | Flowable 引擎封装、BPM 审批闭环 (加签/撤回/退回)、请假/报销集成 |
| `web/controllers/` | 19+ | 顶层控制器 (auth/captcha/common/health/job/register) + `system/` 13 个接口测试 |
| `web/middleware/` | 3 | auth, exception, operlog |
| `web/routes/` | 1 | utils |
| `obs/` | 1 | OpenTelemetry 追踪 |
| `E2E (Playwright)` | 14 个 spec | auth, user/role/post/dict/config/notice CRUD, navigation, business-flow 及报表类 |

---

## 核心功能

### 系统管理 (全部完成)

- **用户管理**: CRUD + 左侧部门树 + 搜索栏 + 工具栏 + 表格 + 详情抽屉 + 重置密码 + CSV 批量导入/导出
- **角色管理**: CRUD + 菜单权限树 + 数据权限 (全部/自定义/本部门/本部门及以下)
- **菜单管理**: 树形表格 + 图标选择器 + 路由/组件/权限标识配置
- **部门管理**: 树形表格 + 数据权限
- **岗位管理**: CRUD + 搜索
- **字典管理**: 字典类型 + 字典数据
- **参数管理**: 系统动态运行参数
- **通知公告**: 富文本编辑 + 发布

### 日志审计 (全部完成)

- **操作日志**: 记录请求方法/URL/参数/结果/耗时
- **登录日志**: 记录登录 IP/浏览器/操作系统/结果
- **在线用户**: 实时监控 + 强制下线

### 系统监控 (全部完成)

- **服务监控**: CPU/内存/JVM/操作系统/磁盘信息
- **数据源监控**: HikariCP 连接池状态
- **缓存监控**: 缓存键值浏览 + 清除
- **Integrant 依赖图**: 组件依赖可视化 + 函数调用追踪

### 任务调度

- CRON 表达式调度 (Quartz / kit-quartz cronut)
- 暂停/恢复/立即执行
- 任务执行日志

### BPM 工作流 (Flowable 8.0)

- 流程模型设计: BPMN 可视化设计器 + 流程节点树配置 (审批人/监听器/流转条件)
- 动态表单: 拖拽式表单设计器 + 运行时渲染
- 流程定义: 版本管理、BPMN XML 查看、历史版本恢复、挂起/激活
- 流程实例: 发起/取消/挂起/激活/终止、历史轨迹、高亮流程图、打印数据
- 任务中心: 我的待办/已办/抄送、通过/驳回/认领/转办/委派
- 高级审批: 加签/减签、撤回 (含撤回到起始节点)、可退回节点退回
- 流程管理: 任务管理/实例运维 (管理员)、用户分组/监听器/表达式/设置
- 引擎数据存储于独立 H2 文件数据库 (`FLOWABLE_JDBC_URL` 可覆盖)

### OA 办公一体化

- **请假申请**: 业务记录 + BPM 审批流集成 (自动同步审批状态)
- **报销申请**: 复用"业务记录 + BPM 审批流"模式
- **OA 日程**: 日历式日程管理
- **OA 会议**: 会议管理
- **统计报表**: 办公一体化统计看板

### HRM / CRM

- **HRM**: 员工管理 (CRUD)
- **CRM**: 客户管理 (CRUD)

### 安全机制

- JWT 认证 (Bearer Token, HS256)
- bcrypt 密码加密 (bcrypt+sha512)
- XSS 防护、CSRF 防护框架
- 登录限流 (可扩展)

---

## 权限模型

系统采用 **RBAC (Role-Based Access Control)** 权限模型：

```
用户 ──── N:N ──── 角色 ──── N:N ──── 菜单(含按钮权限)
 │                    │
 │                    ├── 数据权限范围
 │                    │    ├── 全部
 │                    │    ├── 自定义 (指定部门)
 │                    │    ├── 本部门
 │                    │    └── 本部门及以下
 │                    │
 │                    └── 权限标识 (perms)
 │                         ├── system:user:list
 │                         ├── system:user:add
 │                         └── system:user:edit
 │
 └── 部门 ──── 树形结构
```

### 中间件链

```
请求 → 参数解析 → JWT 校验 (wrap-jwt-auth) → 数据权限 → 操作日志 → 格式转换 → 控制器
                                                           ↓
                                                   401 未认证 / 403 无权限
```

---

## 数据流

### 前端 → 后端 请求流程

```
用户操作 → Reagent 组件 → re-frame event → api.cljs (fetch) → HTTP POST/GET
                                                                     ↓
                                                              Undertow Server
                                                                     ↓
                                                              Ring Middleware
                                                                     ↓
                                                              Reitit Router
                                                                     ↓
                                                              Controller
                                                                     ↓
                                                              Domain Service
                                                                     ↓
                                                              HugSQL → SQL
                                                                     ↓
                                                              next.jdbc → DB
                                                                     ↓
                                                              JSON Response
                                                                     ↓
                                                     re-frame event (成功/失败)
                                                                     ↓
                                                     re-frame sub → 组件 re-render
```

### 登录流程

```
1. POST /api/auth/login (username + password)
2. controller 验证 bcrypt 密码
3. 生成 JWT token (24h 有效期)
4. 返回 token + 用户信息/角色/权限
5. 前端存储 token → 后续请求携带 Authorization: Bearer <token>
6. wrap-jwt-auth 中间件每请求解析验证
7. 在线心跳 (heartbeat!) 每请求更新
```

---

## 分支策略

| 分支 | 用途 | 说明 |
|------|------|------|
| `ruoyi-template` | **开发主线** | 默认工作分支，所有开发在此进行 |
| `main` | 稳定发行版 | 只从 ruoyi-template 合并已验证的功能 |

**开发流程**:
```bash
# 确保在开发分支
git checkout ruoyi-template

# 开发完成后
git push origin ruoyi-template
```

---

## 环境配置

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `PORT` | `3000` | HTTP 服务端口 |
| `NREPL_PORT` | `7000` | nREPL 调试端口 |
| `JDBC_URL` | `jdbc:sqlite:rouyi.db` | 数据库连接 URL |
| `MIGRATION_DIR` | `migrations-sqlite` | 迁移文件目录 |
| `JWT_SECRET` | `rouyi-default-jwt-secret-key-change-in-production` | JWT 签名密钥 |
| `COOKIE_SECRET` | `KWGRWFTDVZAHISQO` | Cookie 签名密钥 |

### Profile

| Profile | 用途 | 配置文件 |
|---------|------|----------|
| `:dev` | 开发 (默认) | `env/dev/` |
| `:test` | 测试 | `env/test/` |
| `:prod` | 生产 | `env/prod/` |

---

## 快速开始

### 环境要求
- JDK 17+
- Clojure CLI (1.12+)
- Node.js 18+
- Babashka (可选，用于 bb.edn 任务快捷方式)

### 1. 启动后端

```bash
# 默认使用 SQLite，自动迁移并初始化数据
clojure -M:dev -m com.ruoyi.core

# 或使用 bb
bb run
```

后端默认运行在 http://localhost:3000，nREPL 端口 7000。

### 2. 启动前端 (开发模式)

```bash
# 安装依赖（首次）
pnpm install

# 开发模式（自动增量编译）
pnpm exec shadow-cljs watch app
```

前端由后端同一端口 (3000) 提供服务，编译产物输出到 `resources/public/js/`。

### 3. 访问

```bash
open http://localhost:3000
```

默认账号: `admin` / `admin123`

### 4. 构建生产包

```bash
# 前端发布
pnpm exec shadow-cljs release app

# 后端 Uberjar（包含前端静态文件）
bb uberjar   # 或: clojure -T:build all

# 运行
java -jar target/rouyi-standalone.jar
```

---

## 后端开发 (nREPL 热重载)

后端运行时通过 nREPL 端口 7000 热重载代码，**无需重启进程**。

### 可用重载命令

```bash
# 单模块重载（最快，推荐日常开发）
clj-nrepl-eval -p 7000 '(user/rd)'          # 重载域服务 (user, role, menu, dept, etc.)
clj-nrepl-eval -p 7000 '(user/rroutes)'     # 重载路由定义
clj-nrepl-eval -p 7000 '(user/ra)'          # 重载所有命名空间

# 数据库操作
clj-nrepl-eval -p 7000 '(user/reset-db)'    # 重置数据库（清空重建）
clj-nrepl-eval -p 7000 '(user/migrate)'     # 运行迁移

# 全局重载（较慢，结构变更时使用）
clj-nrepl-eval -p 7000 '(user/rr)'          # 完全重启系统 (halt → prep → go)
```

### 何时需要重启

- HugSQL `.sql` 文件变更（查询缓存在启动时加载）
- `resources/system.edn` 配置变更
- Integrant 组件结构变更

上述场景运行 `(user/rr)` 或重启进程。

### 何时只需 `(user/rd)` 重载

- 控制器、服务、域逻辑变更
- 单纯逻辑变更不需要重启

---

## 前端开发 (shadow-cljs)

```bash
# 启动 watch 模式（增量编译）
npx shadow-cljs watch app

# 首次编译约 2 分钟，后续修改秒级增量编译
# 编辑 .cljs 文件 → watch 自动检测 → 增量编译 → 刷新浏览器
```

### 前端关键约束

1. **使用 React Hooks，禁用 `reagent/atom`**: 组件内部局部状态用 `hooks/use-state`，全局状态用 re-frame subscription
2. **Ant Design 6 API 注意事项**:
   - `Button :icon` 必须传 React 元素，不能传字符串
   - Dropdown menu 的 `:label` 必须用 `r/as-element` 包裹
   - Modal/Drawer 的 `:width` 已废弃，改用 `:style {:width N}`
   - `:destroyOnClose` 已废弃，改用 `:destroyOnHidden`
   - `message` 必须通过 `App.useApp` 上下文获取，不能直接用静态方法
3. **后端分页参数使用 `page` / `size`**（不是 `pageNum`/`pageSize`）
4. **菜单权限不可写死**: 左侧菜单必须从接口动态获取
5. **自定义表单项**: 需要手动读写 form 字段值

详细前端约定见 [AGENTS.md](./AGENTS.md) 中的"Frontend Ant Design 常见错误"章节。

---

## 数据库兼容 (SQLite / MySQL)

项目同时支持 SQLite 和 MySQL，通过环境变量切换：

```bash
# SQLite（默认）
clojure -M:dev -m com.ruoyi.core

# MySQL
JDBC_URL="jdbc:mysql://root:pass@host:3306/ruoyi?useSSL=false" \
MIGRATION_DIR=migrations \
clojure -M:dev -m com.ruoyi.core
```

### 迁移文件

- `resources/migrations-sqlite/` — SQLite 专用 DDL
- `resources/migrations/` — MySQL 专用 DDL

修改表结构时 **两个目录必须同步更新**。

### SQL 查询原则

1. **优先共用语法**: 如 `INSTR` 代替 `||`
2. **函数名不同时提供命名变体**: 如 `last-insert-rowid` / `last-insert-rowid-mysql`
3. **元数据查询在 Clojure 层分支**: `detect-db-type` 判断后在 `com.ruoyi.infra.db` 中分情况处理

---

## 测试

### 后端测试

```bash
# 运行全部测试（SQLite）
bb test

# 运行单文件测试
clojure -M:test -n com.ruoyi.domain.system.user-test

# 生成覆盖率报告
bb coverage
# 报告位置: target/coverage/index.html
```

### E2E 测试 (Playwright)

```bash
# 安装浏览器（首次）
npx playwright install chromium

# 运行全部 E2E 用例
npm run test:e2e

# 查看 HTML 报告
npm run test:e2e:report
```

测试目录：`tests/e2e/`（14 个 spec，含 2 个公共 helper: `auth-helper.js` / `dom-helper.js`）
- 认证/导航: `auth.spec.js` — 登录/登出；`navigation.spec.js` — 菜单可访问性
- 系统管理 CRUD: `user-crud` / `role-crud` / `post-crud` / `dict-crud` / `config-crud` / `notice-crud`
- 业务流程: `business-flow.spec.js` — 办公一体化核心流程
- 报告类: `frontend-report` / `frontend-bpm-report` / `designer-report` / `full-demo-report` / `updated-report`

---

## API 概览

所有 API 前缀 `/api`，Swagger 文档：http://localhost:3000/api

### 认证
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录 |
| GET | `/api/auth/getInfo` | 获取当前用户信息 |
| POST | `/api/auth/logout` | 登出 |
| POST | `/api/auth/register` | 注册 |

### 系统管理
| 方法 | 路径 | 说明 |
|------|------|------|
| GET/POST/PUT/DELETE | `/api/system/user` | 用户管理 CRUD |
| GET/POST/PUT/DELETE | `/api/system/role` | 角色管理 |
| GET/POST/PUT/DELETE | `/api/system/menu` | 菜单管理 |
| GET/POST/PUT/DELETE | `/api/system/dept` | 部门管理 |
| GET/POST/PUT/DELETE | `/api/system/post` | 岗位管理 |
| GET/POST/PUT/DELETE | `/api/system/dict/type` | 字典类型 |
| GET/POST/PUT/DELETE | `/api/system/dict/data` | 字典数据 |
| GET/POST/PUT/DELETE | `/api/system/config` | 参数配置 |
| GET/POST/PUT/DELETE | `/api/system/notice` | 通知公告 |

### 日志监控 (挂载在 `/api/system/` 下)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/system/oper-log` | 操作日志列表 |
| GET | `/api/system/login-log` | 登录日志列表 |
| GET | `/api/system/online` | 在线用户 |
| DELETE | `/api/system/online/:token-id` | 强退用户 |
| GET | `/api/system/server` | 服务器监控 |
| GET | `/api/system/cache` | 缓存信息 |
| GET | `/api/system/datasource` | 数据源状态 |
| GET | `/api/system/integrant` | Integrant 依赖图 |

### 任务调度
| 方法 | 路径 | 说明 |
|------|------|------|
| GET/POST/PUT/DELETE | `/api/system/job` | 定时任务 CRUD |
| PUT | `/api/system/job/:id/changeStatus` | 暂停/恢复任务 |
| PUT | `/api/system/job/:id/run` | 立即执行一次 |
| GET/DELETE | `/api/system/job-log` | 任务执行日志 / 清空日志 |

### 办公一体化 (`/api/business/`)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET/POST/PUT/DELETE | `/api/business/bpm/category` | 流程分类 |
| GET/POST/PUT/DELETE | `/api/business/bpm/model` | 流程模型 (含部署/复制/排序/节点树) |
| GET | `/api/business/bpm/definition/page` | 流程定义版本分页 |
| GET/POST/PUT/DELETE | `/api/business/bpm/form` | 动态表单 |
| GET/POST | `/api/business/bpm/instance` | 流程实例 (发起/取消/挂起/激活/终止) |
| GET | `/api/business/bpm/todo` / `done` | 我的待办 / 已办 |
| POST | `/api/business/bpm/task/:id/approve` / `reject` | 审批通过 / 驳回 |
| POST/DELETE | `/api/business/bpm/task/create-sign` / `delete-sign` | 加签 / 减签 |
| PUT | `/api/business/bpm/task/withdraw` | 撤回已办任务 |
| GET | `/api/business/bpm/task/return-list` | 可退回节点列表 |
| POST | `/api/business/bpm/task/copy` | 任务抄送 |
| GET/POST/PUT/DELETE | `/api/business/bpm/:module` | BPM 管理 (用户分组/监听器/表达式/设置) |
| GET/POST/PUT/DELETE | `/api/business/oa/leave` | 请假申请 (入审批流) |
| GET/POST/PUT/DELETE | `/api/business/oa/reimburse` | 报销申请 (入审批流) |
| GET/POST/PUT/DELETE | `/api/business/oa/calendar` / `oa/meeting` | OA 日程 / 会议 |
| GET/POST/PUT/DELETE | `/api/business/hrm/employee` | HRM 员工 |
| GET/POST/PUT/DELETE | `/api/business/crm/customer` | CRM 客户 |
| GET | `/api/business/report/stats` | 办公一体化统计看板 |

---

## 开发约定

- 所有 Clojure 编辑通过 `safe-edit` / `validate` 保障括号安全
- SQL 查询统一放在 `resources/sql/*.sql`，使用 HugSQL 管理
- 每个 namespace 不超过 500 行，函数不超过 40 行
- 使用中文 docstring 描述职责、参数和返回值
- 后端分层: route → controller → service → query → db
- 前端状态管理统一使用 re-frame
- SQLite / MySQL 双库兼容，查询优先共用语法

---

## 当前完成度状态

基于 [GAP_ANALYSIS.md](./GAP_ANALYSIS.md) 和 [ROADMAP.md](./ROADMAP.md):

| 模块 | 后端 | 前端 | 完成度 | 说明 |
|------|------|------|--------|------|
| 用户管理 | ✅ | ✅ | 100% | CSV 批量导入/导出已实现 |
| 角色管理 | ✅ | ✅ | 95% | 数据权限已实现 |
| 菜单管理 | ✅ | ✅ | 95% | 按钮权限标识已实现 |
| 部门管理 | ✅ | ✅ | 95% | 树形表格 |
| 岗位管理 | ✅ | ✅ | 95% | — |
| 字典管理 | ✅ | ✅ | 95% | 字典类型+数据 |
| 参数管理 | ✅ | ✅ | 95% | — |
| 通知公告 | ✅ | ✅ | 90% | 富文本编辑器已接入 |
| 操作日志 | ✅ | ✅ | 95% | — |
| 登录日志 | ✅ | ✅ | 95% | — |
| 在线用户 | ✅ | ✅ | 95% | 强退可用 |
| 定时任务 | ✅ | ✅ | 95% | CRON 调度 (Quartz) |
| 服务监控 | ✅ | ✅ | 95% | — |
| 缓存监控 | ✅ | ✅ | 95% | — |
| 数据源监控 | ✅ | ✅ | 95% | HikariCP 状态 |
| Integrant 监控 | ✅ | ✅ | 95% | 依赖图+调用追踪 |
| 系统接口(Swagger) | ✅ | ✅ | 95% | 内嵌 Swagger UI |
| BPM 工作流 | ✅ | ✅ | 95% | Flowable 8.0：模型设计/动态表单/发起/待办/已办/抄送/加签减签/撤回退回/监听/表达式 |
| 请假/报销 | ✅ | ✅ | 95% | 业务记录 + BPM 审批流集成 |
| OA 日程/会议 | ✅ | ✅ | 95% | — |
| HRM 员工 | ✅ | ✅ | 95% | — |
| CRM 客户 | ✅ | ✅ | 95% | — |
| 办公报表 | ✅ | ✅ | 95% | 统计看板 |

**整体完成度: ~95%**

### 待完善

| 功能 | 优先级 | 说明 |
|------|--------|------|
| 数据备份与恢复 | 🟡 中 | 数据库备份/恢复 API |
| 移动端适配 | 🟢 低 | 小屏布局优化 |
| 表格列排序/筛选 | 🟡 中 | 增强表格交互 |
| 批量操作增强 | 🟢 低 | 批量删除/修改状态 |

---

## 参考文档

| 文档 | 说明 |
|------|------|
| [AGENTS.md](./AGENTS.md) | AI 代理开发指南、编码约定、前端规范 |
| [CHANGELOG.md](./CHANGELOG.md) | 版本演进历史（2026-09-20 回溯整理） |
| [docs/guides/add-new-module.md](./docs/guides/add-new-module.md) | 新增业务模块端到端指南（迁移→SQL→领域→控制器→前端→权限→测试） |
| [ROADMAP.md](./ROADMAP.md) | 功能齐平路线图（**已归档**，2026-06） |
| [GAP_ANALYSIS.md](./GAP_ANALYSIS.md) | RuoYi-Vue 详细功能对比（**已归档**，2026-06） |
| [RUOYI_VUE_COMPARISON.md](./RUOYI_VUE_COMPARISON.md) | RuoYi-Vue 逐项对比（**已归档**，2026-06） |
| [docs/training/](./docs/training/) | 开发培训材料 (5 节课，基于 6 月代码) |
| [docs/design/BPM_GAP_PLAN.md](./docs/design/BPM_GAP_PLAN.md) | BPM 对齐 ruoyi-office-vben 的差距补全规格 (Phase 1-4 已完成) |
| [docs/design/BPM_OA_DESIGN.md](./docs/design/BPM_OA_DESIGN.md) | BPM/OA 办公一体化顶层设计 + 逐日 changelog |
| [test/coverage-report.md](./test/coverage-report.md) | 测试覆盖率报告（**已归档**，2026-06-13） |
