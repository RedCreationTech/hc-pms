# HC-PMS 工业项目管理系统

基于 `RedCreationTech/ruoyi_clojure` 的 `ruoyi-template` 分支开发, 将订单项目的结构, 团队, 计划与交付证据逐步连接起来.

本轮完成 V1 工程设计, 并实现第一批项目核心功能. [设计入口](docs/pms/README.md) | [验证记录](docs/pms/verification.md) | [开发路线](docs/pms/10-development-roadmap.md) | [原模板文档](docs/template-readme.md)

## 项目目标

1. 逐项对齐原PPT展示的全部PMS功能, 包括业务流程, 计划与关口, 过程管理, 四算, 看板和外部集成.
2. 在上述能力基础上, 补齐标准PMS的业务闭环: 项目批准 -> 范围与计划基线 -> 资源安排 -> 执行跟踪 -> 风险/问题/变更控制 -> 质量与交付验收 -> 费用结算 -> 收尾归档与经验复用.

这两项是最终产品目标, 首批项目中心只是起点. [功能与验收矩阵](docs/pms/11-feature-acceptance-matrix.md)逐项记录PPT来源, 补全项, 验收条件, 开发批次与实现状态. 本项目的标准PMS闭环是工程验收基线, 不代表取得某项正式标准认证. 功能必须具备持久化, 权限, 流程约束, 审计及真实操作证据后才能标记完成.

## 当前功能

- 项目中心: 创建/编辑, 搜索, 状态过滤, 分页和详情.
- 项目结构: 创建项目时建立主节点, 支持主项目 -> BU子项目 -> 单机.
- 项目团队: 关联现有系统用户, 管理者/协作/只读角色.
- 生命周期: 草稿 -> 立项 -> 计划准备; 取消需要理由, 终态只读.
- 访问控制: 实时功能权限 + 项目范围, 列表/统计/详情和写操作一致隔离.
- 事务审计: 变更和审计原子提交, 聚合版本控制并发及事件顺序.
- 驾驶舱: 当前授权项目的总量, 活跃, 逾期和草稿统计.

计划基线, Gate审批, URS验证, FAT/SIT/SAT证据, 文档签发, 四算, 外部系统适配和AI建议已完成 V1 设计, 待后续批次开发. 当前不会把切换状态当成质量批准, `planning -> execution` 返回409.

## 技术与来源

Kit / Clojure 1.12, Reitit, HugSQL, Migratus; ClojureScript / Reagent 2 / re-frame / Ant Design 6. 本地默认SQLite, MySQL维护独立迁移. 保留模板身份, 组织, 动态权限菜单, 办公流程和Flowable.

固定模板基线: `99706b06fea4ff9efed6822a70cbda1c2bd98552`. `upstream` 指向模板, `origin` 指向产品仓库. 用户于2026-09-22明确授权向公开的 `RedCreationTech/hc-pms` 推送本项目工程及其模板历史. 原始业务PPT, 内部逐页提炼, 凭据和运行数据库不随源码发布.

## 本地启动

需要 JDK 21+, Clojure CLI, Node 22+ 和 pnpm. 以下命令在仓库根目录运行.

```bash
pnpm install --frozen-lockfile
# 终端1: 后端与随机nREPL端口, 限定本机访问
PORT=3100 HTTP_HOST=127.0.0.1 NREPL_PORT=0 FLOWABLE_ASYNC=false clojure -M:dev -m com.ruoyi.core
# 终端2: 前端增量编译
pnpm exec shadow-cljs watch app
```

打开 [项目中心](http://127.0.0.1:3100/pms/project) 或 [项目驾驶舱](http://127.0.0.1:3100/pms/dashboard). 新建本地数据库的模板演示账号为 `admin / admin123`. 对外部署前替换默认口令并设置 `JWT_SECRET`, `COOKIE_SECRET`. 不要将开发nREPL对外开放.

数据库默认为仓库内 `rouyi.db`, 首次启动自动迁移. 自定义数据库使用 `JDBC_URL`, MySQL同时设置 `MIGRATION_DIR=migrations`. 运行文件已被Git忽略.

## 验证

```bash
# 每次创建隔离临时SQLite, 不污染当前开发数据
clojure -M:test -n com.ruoyi.pms-test
# MySQL必须使用独立空测试数据库
PMS_TEST_JDBC_URL='jdbc:mysql://127.0.0.1:3306/hc_pms_test?user=USER&password=PASSWORD&useSSL=false&allowPublicKeyRetrieval=true' clojure -M:test -n com.ruoyi.pms-test
# 浏览器完整流程, 需要当前服务与已编译页面
BASE_URL=http://127.0.0.1:3100 pnpm exec playwright test tests/e2e/pms.spec.js --project=chromium
# 生产构建
pnpm exec shadow-cljs release app
clojure -T:build all
```

新增 `.github/workflows/pms.yml` 包含SQLite/MySQL隔离测试, 前端生产编译和浏览器流程. CI是否执行以及双库实测结果以验证记录为准, 不能仅凭配置存在认为测试已通过.

## 后续开发

下一批先完成WBS任务, 日历, 依赖DAG, 里程碑和基线版本, 再接入Gate/URS/验证/文档. 财务与企业集成按事实所有权和幂等事件设计实施, AI只基于有权限的证据生成待确认建议.
