# 若依 Clojure 全栈管理系统

基于 **Kit 框架** + **ClojureScript** + **Reagent** + **Ant Design** 构建的 Ruoyi 风格全栈管理后台，对齐原版若依框架核心功能。

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Clojure 1.12, Kit, Integrant, Reitit, Ring, next.jdbc, Migratus |
| 数据库 | PostgreSQL (默认), 支持 MySQL |
| 安全 | Buddy (JWT + bcrypt), XSS/CSRF 防护 |
| 前端 | ClojureScript, Shadow-CLJS, Reagent 2.x, React 19, Ant Design 6, re-frame |
| 任务调度 | Quartz |
| 代码生成 | 根据表结构自动生成前后端代码 |

---

## 核心功能

### 系统管理
- **用户管理**：配置系统用户，支持角色、部门、岗位关联
- **角色管理**：RBAC 权限模型，支持菜单/按钮权限、数据权限（部门级）
- **菜单管理**：树形菜单配置，支持路由、组件、权限标识
- **部门管理**：树形部门结构，支持数据权限范围控制
- **岗位管理**：岗位信息维护
- **字典管理**：字典类型与字典数据维护
- **参数管理**：系统运行参数配置

### 权限控制
- JWT Token 认证
- 菜单权限、按钮权限（perms 字段控制）
- 数据权限：全部、自定义、本部门、本部门及以下

### 日志审计
- 操作日志：记录请求方法、URL、参数、结果、耗时
- 登录日志：记录登录 IP、浏览器、操作系统、登录结果
- 在线用户：实时监控，支持强退

### 代码生成器
- 根据数据库表结构一键生成：
  - SQL 查询文件
  - Clojure 控制器模板
  - ClojureScript 前端页面模板

### 系统监控
- 服务监控：JVM、操作系统信息
- 数据源监控：活跃连接数、数据库版本
- 定时任务管理：CRON 表达式调度

### 安全机制
- JWT 认证（Bearer Token）
- bcrypt 密码加密
- XSS 防护、CSRF 防护
- 登录限流（可扩展）

---

## 项目结构

```
.
├── deps.edn                    # Clojure 依赖
├── shadow-cljs.edn             # ClojureScript 构建配置
├── package.json                # NPM 依赖 (React, Ant Design)
├── resources/
│   ├── system.edn              # Integrant 系统配置
│   ├── migrations/             # 数据库迁移文件
│   ├── sql/                    # HugSQL 查询文件
│   └── public/index.html       # 前端入口
├── src/clj/com/ruoyi/rouyi/    # 后端源码
│   ├── core.clj                # 应用入口
│   ├── infra/security.clj      # JWT / 密码加密
│   ├── domain/system/          # 领域服务层
│   ├── web/controllers/        # 控制器层
│   ├── web/routes/             # 路由配置
│   └── web/middleware/         # 中间件 (认证)
├── frontend/src/com/ruoyi/rouyi/frontend/  # 前端源码
│   ├── app.cljs                # 前端入口
│   ├── api.cljs                # HTTP 客户端
│   ├── events.cljs             # re-frame 事件
│   ├── subs.cljs               # re-frame 订阅
│   ├── antd.cljs               # Ant Design 组件封装
│   └── pages/                  # 页面组件
└── test/                       # 测试代码
```

---

## 快速开始

### 环境要求
- JDK 17+
- Clojure CLI
- Node.js 18+
- PostgreSQL 14+

### 1. 创建数据库

```bash
# 登录 PostgreSQL
createdb rouyi
psql -d rouyi -c "CREATE USER rouyi WITH PASSWORD 'rouyi';"
psql -d rouyi -c "GRANT ALL PRIVILEGES ON DATABASE rouyi TO rouyi;"
```

### 2. 启动后端

```bash
# 安装依赖并进入 REPL
clj -M:dev

# 在 REPL 中启动系统
(go)
```

后端默认运行在 http://localhost:3000

数据库迁移会在启动时自动执行，并初始化默认数据（admin / admin123）。

### 3. 启动前端

```bash
# 安装 NPM 依赖
npm install

# 开发模式
npx shadow-cljs watch app
```

前端开发服务器运行在 http://localhost:8700

### 4. 构建生产包

```bash
# 后端 Uberjar
clj -T:build uber

# 前端发布
npx shadow-cljs release app
```

---

## 默认账号

| 账号 | 密码 |
|------|------|
| admin | admin123 |

---

## API 概览

| 路径 | 说明 |
|------|------|
| POST /api/auth/login | 登录 |
| GET /api/auth/getInfo | 获取当前用户信息 |
| GET /api/system/user | 用户列表 |
| GET /api/system/role | 角色列表 |
| GET /api/system/menu/tree | 菜单树 |
| GET /api/system/dept | 部门列表 |
| GET /api/system/post | 岗位列表 |
| GET /api/system/dict/type | 字典类型 |
| GET /api/system/dict/data | 字典数据 |
| GET /api/system/config | 参数配置 |
| GET /api/system/oper-log | 操作日志 |
| GET /api/system/login-log | 登录日志 |
| GET /api/system/online | 在线用户 |
| GET /api/system/job | 定时任务 |
| GET /api/system/server | 服务监控 |
| GET /api/system/datasource | 数据源监控 |
| GET /api/tool/gen/tables | 代码生成 - 表列表 |
| GET /api/tool/gen/preview | 代码生成 - 预览 |

---

## 开发约定

- 所有 Clojure 编辑通过 `safe-edit` / `validate` 保障括号安全
- SQL 查询统一放在 `resources/sql/*.sql`，使用 HugSQL 管理
- 每个 namespace 不超过 500 行，函数不超过 40 行
- 使用中文 docstring 描述职责、参数和返回值
- 后端分层：route -> controller -> service -> query -> db
- 前端状态管理统一使用 re-frame
