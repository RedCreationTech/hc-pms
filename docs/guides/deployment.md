# 部署与运维指南

> 适用版本: RuoYi-Clojure (分支 ruoyi-template).本文所有技术事实均取自仓库内真实代码与配置, 关键处标注 `文件:行号` 以便核对.

## 1. 概述

本项目是单 jar 交付的全栈应用:

- 后端为 Clojure uberjar, 主类 `com.ruoyi.core` (build.clj:7), 内嵌 Undertow HTTP 服务, 并同时承载前端静态资源 (system.edn 中 `:static {:resources "public"}`, resources/system.edn:34).
- 前端为 ClojureScript, shadow-cljs release 产物输出到 `resources/public/js` (shadow-cljs.edn:12), 构建 jar 时随 `resources` 目录一并打入 (build.clj:27), 因此**交付物只有一个 jar**, 不需要单独部署 Nginx 托管前端 (反向代理仍可用于 TLS/负载均衡).
- 应用默认使用 **SQLite 嵌入式部署** (`jdbc:sqlite:rouyi.db`, resources/system.edn:154), 无需外部数据库即可运行; 生产推荐切换 MySQL (见第 5 节).
- 工作流引擎 Flowable 8.0 (deps.edn:51) 独立存储于 H2 文件库 (默认 `jdbc:h2:file:./flowable`, resources/system.edn:83), 与应用业务库解耦, 与应用库是**两个独立的数据存储**, 备份时需分别处理 (见第 6 节).

## 2. 构建

### 2.1 前端 release

```bash
npx shadow-cljs release app
```

产物: `resources/public/js/` (shadow-cljs.edn:12-14).`:asset-path "/js"` (shadow-cljs.edn:13), 由后端同源提供.

### 2.2 后端 uberjar

```bash
clojure -T:build all        # 等价于 bb uberjar (bb.edn:23-24)
```

- 任务定义: build.clj:41-42 (`all` = `clean` -> `prep` -> `uber`).
- lib 名 `com.ruoyi/rouyi`, 版本 `0.0.1-SNAPSHOT` (build.clj:6-8).
- `prep` 将 `src/clj`, `resources`, `env/prod/resources`, `env/prod/clj` 拷入 `target/classes` (build.clj:27-28) -- 注意 **生产环境生效的是 `env/prod/` 下的覆盖文件**, 不是 `env/dev/`.
- 产物: `target/rouyi-standalone.jar` (build.clj:11).当前仓库中实测约 58 MB (target/rouyi-standalone.jar, 构建时间不同会有差异).
- `env/prod/` 的实际内容 (已核实): 仅 `resources/logback.xml` (仅控制台输出, root level INFO) 与 `clj/com/ruoyi/env.clj` (设定 `:profile :prod`).**没有** `env/prod/resources/system.edn`, 即生产 profile 沿用根 `resources/system.edn`, 组件结构与 dev 一致.

### 2.3 交付物清单

| 文件 | 来源 | 说明 |
|------|------|------|
| `target/rouyi-standalone.jar` | `clojure -T:build all` | 唯一必需交付物, 含前端静态资源与全部迁移文件 |
| `rouyi.db` | 首次启动自动创建 | SQLite 业务库 (可用 `JDBC_URL` 换路径/换库) |
| `flowable.mv.db` 等 | 首次启动自动创建 | Flowable H2 引擎库文件, 前缀由 `FLOWABLE_JDBC_URL` 决定 |
| `JWT_SECRET` 等 | 环境变量 | 见第 4 节 |

## 3. 运行

```bash
java -jar target/rouyi-standalone.jar
```

- 启动流程: `-main` (src/clj/com/ruoyi/core.clj:76-81) -> `start-app` 以 `:profile :prod` 读取 `system.edn` (src/clj/com/ruoyi/core.clj:67-73, env/prod/clj/com/ruoyi/env.clj) -> Integrant 初始化全部组件 (HTTP, nREPL, 数据库, 迁移, Flowable 引擎, 调度器).
- HTTP 默认监听 `0.0.0.0:3000` (resources/system.edn:10-13).
- **启动时自动执行数据库迁移**: `:migrate-on-init? true` (resources/system.edn:185), 应用启动即完成 schema 初始化/升级, 无需手动跑迁移命令.
- 健康检查: `GET /api/health`, 返回 JSON, 含 `app.status = "up"` (src/clj/com/ruoyi/web/controllers/health.clj:9-15; 路由 src/clj/com/ruoyi/web/routes/api.clj:39-40).该路由不在鉴权组内, 可直接探测:

```bash
curl -s http://localhost:3000/api/health
# {"time":"...","up-since":"...","app":{"status":"up","message":""}}
```

- 默认账号: `admin / admin123` (超级管理员), `ry / admin123` (普通只读).
- 前端入口: `http://localhost:3000/` (jar 内静态资源, system.edn:34).

## 4. 环境变量总表

所有变量均在 `resources/system.edn` 中通过 aero 的 `#env` 标签读取 (读取入口 src/clj/com/ruoyi/config.clj:9-11, 使用 kit.config); `JWT_SECRET` 例外, 直接在代码中读取.

| 变量名 | 默认值 | 说明 | 代码出处 |
|--------|--------|------|----------|
| `PORT` | `3000` | HTTP 服务端口 | resources/system.edn:11 |
| `HTTP_HOST` | `0.0.0.0` | HTTP 绑定地址 | resources/system.edn:12 |
| `NREPL_PORT` | `7000` | nREPL 端口.**生产 jar 同样启动 nREPL** (system.edn 无 profile 分支, env/prod 无覆盖), 默认绑定 127.0.0.1 | resources/system.edn:7 |
| `NREPL_HOST` | `127.0.0.1` | nREPL 绑定地址.切勿在公网主机改为 0.0.0.0 | resources/system.edn:8 |
| `JDBC_URL` | `jdbc:sqlite:rouyi.db` | 应用业务库 JDBC URL; 三个 profile (dev/test/prod) 的默认值相同 | resources/system.edn:154, 161, 168 |
| `MIGRATION_DIR` | `migrations-sqlite` | Migratus 迁移目录; 切 MySQL 时必须改为 `migrations` | resources/system.edn:186, 190, 194 |
| `FLOWABLE_JDBC_URL` | `jdbc:h2:file:./flowable;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE` | Flowable 引擎库 JDBC URL (独立存储, 与业务库解耦) | resources/system.edn:83; 消费处 src/clj/com/ruoyi/bpm/engine.clj:40 |
| `FLOWABLE_ASYNC` | `true` | Flowable 异步执行器开关; 超时边界/定时事件依赖它, 只有明确不需要时才设 `false` | resources/system.edn:85; 消费处 src/clj/com/ruoyi/bpm/engine.clj:26-31, 38, 42 |
| `JWT_SECRET` | `rouyi-default-jwt-secret-key-change-in-production` | JWT 签名密钥.**生产必须显式设置**, 否则使用公开源码中的默认密钥 | src/clj/com/ruoyi/infra/security.clj:9-12 |
| `COOKIE_SECRET` | `KWGRWFTDVZAHISQO` | ring session cookie 密钥 | resources/system.edn:19 |
| `PMS_FILE_DIR` | `data/pms-files` | PMS 证据文件 (PDF/图片/Office 等真实附件) 的内容寻址存储目录, 结构 `<project_id>/<sha256>`; 生产须放在持久卷并纳入备份 | resources/system.edn `:app.pms/service`; 消费处 src/clj/com/ruoyi/domain/pms/governance/files.clj |
| `PMS_FILE_MAX_MB` | `50` | 单个证据文件上限 (MiB), 超过返回 400 | 同上 |

生产启动示例:

```bash
JWT_SECRET="$(openssl rand -hex 32)" \
JDBC_URL="jdbc:mysql://root:password@127.0.0.1:3308/ruoyi?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
MIGRATION_DIR=migrations \
java -jar target/rouyi-standalone.jar
```

注意: `#env` 读取的是**进程环境变量**, 不是 `.env` 文件; 如需 `.env` 文件需自行在启动脚本中 `export`.

## 5. 数据库切换与迁移

### 5.1 默认: SQLite

零配置即用.库文件 `rouyi.db` 位于 JVM 工作目录 (resources/system.edn:154).

### 5.2 切换 MySQL 步骤

1. 准备 MySQL 实例 (8.0 均可, 参考开发环境用法, 端口示例 3308, 待确认以实际环境为准):

```bash
docker exec ruoyi-mysql mysql -uroot -ppassword -e \
  "DROP DATABASE IF EXISTS ruoyi; CREATE DATABASE ruoyi CHARACTER SET utf8mb4;"
```

2. 设置两个环境变量后启动 jar (见第 4 节示例):

```bash
export JDBC_URL="jdbc:mysql://root:password@127.0.0.1:3308/ruoyi?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
export MIGRATION_DIR=migrations
```

- `JDBC_URL` 切换连接目标 (resources/system.edn:168, prod profile).
- `MIGRATION_DIR=migrations` 切换到 MySQL 方言迁移目录 (resources/system.edn:194).**只改 JDBC_URL 不改 MIGRATION_DIR 会用 SQLite 方言 DDL 建 MySQL 表, 导致启动失败.**

### 5.3 迁移机制

- 迁移工具为 Migratus, `store :database` (迁移记录存业务库), `migrate-on-init? true` (启动时自动迁移, resources/system.edn:182-194).
- 两个迁移目录必须保持同步, DDL 方言不同:
  - `resources/migrations-sqlite/` -- SQLite DDL, 自增主键 `INTEGER PRIMARY KEY`, 时间字段 `TEXT DEFAULT CURRENT_TIMESTAMP`.
  - `resources/migrations/` -- MySQL DDL, 自增主键 `BIGINT AUTO_INCREMENT PRIMARY KEY`, 时间字段 `TIMESTAMP DEFAULT CURRENT_TIMESTAMP`.
- **Migratus 语句分隔符 `--;;` 坑**: JDBC 单次只能执行一条语句, 迁移文件内**每条** SQL 后都必须紧跟一行 `--;;`.漏写不会报错, 只会静默执行第一条语句且迁移仍被标记为完成, 之后出现 `no such column` 之类错误极难排查.排查某迁移是否踩坑: 对比数据库实际表结构与该迁移文件, 或直接对全新库重跑迁移验证 schema 完整性.
- 新增迁移时需同时编写两个目录下的对应文件 (命名保持一致的 id 前缀).

### 5.4 Flowable 引擎库

Flowable 的 `ACT_*` 表存放在独立的 H2 文件库, 由 Flowable 自身的 `database-schema-update "true"` 自动建表/升级 (resources/system.edn:84, src/clj/com/ruoyi/bpm/engine.clj:41), **不走 Migratus, 也不在迁移目录中**.可通过 `FLOWABLE_JDBC_URL` 把引擎库指向外部 MySQL/PostgreSQL (src/clj/com/ruoyi/bpm/engine.clj:13 注释明确支持).

## 6. 备份与恢复

应用有**两个独立数据存储**, 备份时缺一不可:

### 6.1 业务库 (SQLite)

- 单文件 `rouyi.db`.**必须先停服再复制**: SQLite 允许在线复制, 但运行中复制可能抓到不一致快照 (WAL/进行中事务).最稳妥方式:

```bash
./stop_dev.sh   # 或杀掉 java 进程
cp rouyi.db "backups/rouyi-$(date +%Y%m%d).db"
```

- 也可用 SQLite 在线备份 API (`.backup` 命令) 实现热备, 但本项目未内置该封装, 自行评估 (待确认: 生产未内置备份脚本).
- 恢复: 停服后用备份文件覆盖 `rouyi.db` 再启动.

### 6.2 Flowable 引擎库 (H2 文件)

- 默认 URL `jdbc:h2:file:./flowable` (resources/system.edn:83), 对应工作目录下 `flowable.mv.db` (主数据) 与 `flowable.trace.db` (追踪日志) 等文件, 前缀随 URL 变化.
- **必须停服复制**.H2 文件库持有文件锁且可能有未刷盘写, 运行中直接 `cp` 会得到损坏或过期副本; 若启动时报文件锁/损坏错误, 通常就是热拷贝导致 (见第 8 节).
- **版本注意**: 引擎固定使用 H2 1.4.200 (deps.edn:52), 因为 Flowable 的 IDENTITY 方言不兼容 H2 2.x (src/clj/com/ruoyi/bpm/engine.clj:9).**不要**用 H2 2.x 的 jar/工具去打开 1.4.200 生成的文件, 也不要混用不同 H2 版本的工具做离线导出; 恢复时保持运行库与备份工具的 H2 版本一致.

### 6.3 MySQL 部署的备份

业务库与引擎库都在 MySQL 时, 用标准工具:

```bash
mysqldump -h127.0.0.1 -P3308 -uroot -ppassword --databases ruoyi > backup-ruoyi.sql
mysqldump -h127.0.0.1 -P3308 -uroot -ppassword --databases flowable > backup-flowable.sql
```

(库名以 `JDBC_URL` / `FLOWABLE_JDBC_URL` 实际配置为准.)

### 6.4 应用内日志数据

操作日志/登录日志存于业务库表内, 随业务库一并备份; 与文件系统日志 (见 7.4) 无关.

## 7. 生产检查清单

1. **JWT_SECRET 必须改**: 默认值硬编码在公开源码中 (src/clj/com/ruoyi/infra/security.clj:11-12), 不改则任何人可伪造 token.建议 `openssl rand -hex 32`.同时建议设置 `COOKIE_SECRET` (默认同为公开值, resources/system.edn:19).
2. **数据库推荐 MySQL**: SQLite 仅适合小规模/单机, 生产用 MySQL 并按 5.2 节设置 `JDBC_URL` + `MIGRATION_DIR`.
3. **nREPL 暴露控制**: 生产 jar 默认仍启动 nREPL (resources/system.edn:6-8, env/prod 无 system.edn 覆盖, 已核实 env/prod 仅含 logback.xml 与 env.clj).当前缓解因素是默认绑定 `127.0.0.1` (仅本机可连).部署要点:
   - 保持 `NREPL_HOST=127.0.0.1` (默认即如此), 不要把 nREPL 端口直接暴露公网;
   - 若使用容器/反向代理, 不要把 7000 映射出去;
   - 如需彻底禁用 nREPL: 目前 `system.edn` 无开关 (待确认: 尚无官方禁用方式, 可用防火墙封端口或自建覆盖版 system.edn 打包).
4. **HTTP 绑定**: 默认 `0.0.0.0:3000` (resources/system.edn:12), 反向代理场景可用 `HTTP_HOST=127.0.0.1` 收敛.
5. **日志轮转**: 生产 logback 仅输出到控制台 (env/prod/resources/logback.xml, root INFO), 日志轮转依赖部署环境:
   - systemd: 用 `StandardOutput=append:` 或 journald (journald 自带轮转);
   - nohup/手动重定向: 自行接入 logrotate;
   - 参考: dev 环境的 logback 配置 (env/dev/resources/logback.xml) 演示了 RollingFileAppender (单文件 100MB, 保留 30 天), 生产可仿写一份放入 `env/prod/resources/logback.xml` 重新打包.
   - 仓库中 `log/` 目录为 dev logback 产物, `logs/` 目录为 `start_dev.sh` 重定向产物 (start_dev.sh:113, 164), 生产 jar 均不产生.
6. **Flowable 异步执行器**: 保持默认 `FLOWABLE_ASYNC=true`, 超时边界/定时事件依赖它 (src/clj/com/ruoyi/bpm/engine.clj:11-12).
7. **时区/编码**: MySQL URL 中保留 `serverTimezone=Asia/Shanghai`, 库字符集 `utf8mb4` (见 5.2 节).

## 8. 故障排查

### 8.1 端口占用 (BindException)

- HTTP: 改 `PORT`, 或释放 3000 端口.`start_dev.sh` 在开发环境会自动向后找空闲端口 (start_dev.sh:82-87), uberjar 无此逻辑, 直接报错.
- nREPL: 改 `NREPL_PORT` (resources/system.edn:7).
- 排查命令 (macOS): `lsof -iTCP:3000 -sTCP:LISTEN -nP`.

### 8.2 启动时迁移失败 / 表结构缺失

- 看启动日志中 Migratus 相关报错, 确认使用的迁移目录 (`MIGRATION_DIR`) 与 `JDBC_URL` 方言匹配 (见 5.2).
- 症状为 `no such column` / `no such table` 但服务已"启动成功": 高度怀疑 `--;;` 分隔符漏写导致多语句迁移只执行了第一条 (见 5.3).处理: 修正迁移文件后, 删除业务库中该迁移的记录行并重启 (迁移记录存在 Migratus 表内), 或新建空库重跑.
- 注意 `start_dev.sh` 每次启动会 `rm -f rouyi.db` 重建开发库 (start_dev.sh:112), 属于开发脚本行为, 生产 jar 不会删库.

### 8.3 Flowable H2 启动失败 (文件锁 / 库损坏)

- 典型报错: H2 文件被另一进程锁定, 或热拷贝副本无法打开.原因与处理:
  1. 同一 `FLOWABLE_JDBC_URL` 指向的文件被第二个进程打开 (重复启动了 jar, 或旧进程未退): `lsof flowable.mv.db` 找到占用进程, 停掉多余实例.
  2. 备份/恢复时热拷贝了 H2 文件: 回到停服状态下重新复制 (见 6.2).
  3. 用错 H2 版本 (2.x) 打开 1.4.200 文件: 确认 classpath 中 H2 为 1.4.200 (deps.edn:52), 升级 Flowable 前评估此约束.
- 恢复顺序: 先停服, 用最近的有效备份替换 `flowable.mv.db` (及对应 trace 文件), 再启动; Flowable 会按 `database-schema-update` 自行校验结构.

### 8.4 健康检查不通

- `curl -v http://localhost:3000/api/health` 确认端口与路径 (`/api` 前缀, 路由 src/clj/com/ruoyi/web/routes/api.clj:39).
- 若端口在监听但无响应: 看控制台输出, 生产 logback 只打 INFO 到 stdout (env/prod/resources/logback.xml).
- 首次启动慢属正常 (uberjar 初始化 Flowable 引擎 + 自动迁移).

### 8.5 nREPL 连接不上

- 确认绑定地址: 默认 `127.0.0.1`, 远程机器上 `clj-nrepl-eval` 需先 SSH 到部署机再连.
- 端口以 `NREPL_PORT` 为准, 检查 `lsof -iTCP:7000 -sTCP:LISTEN -nP`.
