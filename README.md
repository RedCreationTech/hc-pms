# HC-PMS 工业项目管理系统

基于 `RedCreationTech/ruoyi_clojure` 的 `ruoyi-template` 分支开发, 将订单项目的结构, 团队, 计划与交付证据逐步连接起来.

已实现项目核心工作台和本地业务闭环, 包括计划,治理,交付,工时成本与独立关闭. [操作体验指南](docs/pms/12-user-guide.md) | [设计入口](docs/pms/README.md) | [验证记录](docs/pms/verification.md) | [开发路线](docs/pms/10-development-roadmap.md) | [原模板文档](docs/template-readme.md)

## 项目目标

1. 逐项对齐原PPT展示的全部PMS功能, 包括业务流程, 计划与关口, 过程管理, 四算, 看板和外部集成.
2. 在上述能力基础上, 补齐标准PMS的业务闭环: 项目批准 -> 范围与计划基线 -> 资源安排 -> 执行跟踪 -> 风险/问题/变更控制 -> 质量与交付验收 -> 费用结算 -> 收尾归档与经验复用.

这两项是最终产品目标, 当前进度不等于原PPT全部功能已经验收. [功能与验收矩阵](docs/pms/11-feature-acceptance-matrix.md)逐项记录PPT来源, 补全项, 验收条件, 开发批次与实现状态. 本项目的标准PMS闭环是工程验收基线, 不代表取得某项正式标准认证. 功能必须具备持久化, 权限, 流程约束, 审计及真实操作证据后才能标记完成.

## 当前功能

- 项目中心,主/子/单机结构,团队及责任交接检查,有范围隔离的驾驶舱.
- WBS,FS/SS/FF/SF依赖,工作日历,关键路径/浮动,跨项目匿名人员负荷,独立基线审批和批准变更关联.
- 章程,URS版本与整批CSV预检,真实文本附件及下载,需求追踪,质量Gate,风险/问题,会议行动转任务,正式变更.
- 物料申请,BOM冻结与齐套,装配交检,SIT/FAT/SAT实际结果与复验,发运签收,售后异常的独立验证.
- 工时报工与独立审核,跨项目每日容量,四算不可变版本与独立审批,精确金额及按批准工时分摊.
- 清单,遗留移交,经验,正式关闭审批及证据快照;已关闭项目可独立审批重开,重新关闭必须重新审批.
- 接口运维收件去重/乱序保护,确定版本外发,真实HTTP回执/退避/死信/人工恢复及对账. 八类企业系统的真实字段适配和沙箱仍待配置.
- 平台模板与规则: 版本化项目模板/编码规则/季度目标/研发费用池/工时封期; 项目一次性应用模板生成结构节点, 关口模板, 计划容器与收尾清单, 阶段进度按权重卷积, 单机可局部暂停. 关口目录带阻断检查点 (齐套阻断装配开工, 交检阻断 SIT, FAT 确认阻断发运).
- 现场与看板: 工勘/DQ/启动会会前包, 齐套多层卷积, 包材申请, 装配步骤, 发货前条件, 交底截止与自动下发现场任务; 我的待办, 全局检索, 项目组合看板, 过程看板, 四算拉通, 工时更正与封期, 跨项目研发费用池分摊, 季度经营目标看板 (2026-09-24 蓝图对齐四增量, 无外部依赖).

生命周期为 draft -> initiated -> planning -> execution -> closing -> closed,并支持暂停恢复及取消. 进入执行须已批准基线/章程/Gate;最终关闭必须满足完整实际交付,质量,财务与独立审核条件. 终态只读,通用审计不泄露财务审核内容.

精确API见[模块合同](docs/pms/contracts/) (含 [蓝图对齐扩展合同](docs/pms/contracts/blueprint-extension.md)). 本地闭环不等于全部PPT需求或生产验收. 二进制文档与正式签章, 费率/汇率/税务/合并经营口径, 待批准的 Gate 适用编号/编码规则/交底日历口径, AI和八系统真实合同等剩余项见[验收矩阵](docs/pms/11-feature-acceptance-matrix.md), 不用模板遗留功能冒充PMS实现.

## 技术与来源

Kit / Clojure 1.12, Reitit, HugSQL, Migratus; ClojureScript / Reagent 2 / re-frame / Ant Design 6. 本地默认SQLite, MySQL 8.0.13+维护独立迁移. 保留模板身份, 组织, 动态权限菜单, 办公流程和Flowable.

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
clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'
# MySQL必须使用独立空测试数据库
PMS_TEST_JDBC_URL='jdbc:mysql://127.0.0.1:3306/hc_pms_test?user=USER&password=PASSWORD&useSSL=false&allowPublicKeyRetrieval=true' clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'
# 浏览器完整流程, 需要当前服务与已编译页面
BASE_URL=http://127.0.0.1:3100 pnpm exec playwright test tests/e2e/pms.spec.js tests/e2e/pms-workbench.spec.js --project=chromium
# 生产构建
pnpm exec shadow-cljs release app
clojure -T:build all
```

新增 `.github/workflows/pms.yml` 包含SQLite/MySQL隔离测试, 前端生产编译和浏览器流程. CI是否执行以及双库实测结果以验证记录为准, 不能仅凭配置存在认为测试已通过.

## 企业对接与后续验收

部署连接器协议见[接口合同](docs/pms/contracts/integration.md),源码不携带真实凭据. 未配置时页面明确显示未配置,外发返回503,不会假装同步成功. 后续以真实业务签收的规则和外部沙箱完成剩余矩阵;当前上线前还需生产升级/恢复/容量/兼容性验收.
