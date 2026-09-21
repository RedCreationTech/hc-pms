# Changelog

RuoYi-Clojure(Kit 后端 + Reagent/shadow-cljs 前端 + Ant Design 6 + Flowable 8.0 BPM)
全栈管理后台的演进记录.

> 本文件于 **2026-09-20 回溯整理**,依据 `git log`(407 个提交)与
> `docs/design/BPM_OA_DESIGN.md` 文末逐日 Changelog 聚合而成,
> 按月/里程碑归并,非逐提交罗列.版本按时间**倒序**排列.

## [Unreleased]

## [2026-09] BPM 差距补全 Phase 1-4 与设计器 UI 修复

### Added
- BPM Phase 4:webhooks / 触发器语义 / 子流程 / 路由分支节点 / 流程监听器落地.
- BPM Phase 1 审批闭环:加签 / 减签 / 任务抄送(含我的抄送分页)/ 撤回(含撤回到起始节点)/
  可退回节点列表 / 流程实例取消(发起人或管理员).
- BPM Phase 3 治理能力:流程定义版本分页,历史定义恢复回模型,单号当日递增规则,
  实例去重,标题/摘要字段,打印模板.
- BPM Phase 2 节点配置补全:审批策略 / 操作按钮 / 签名 / 超时处理.
- BPM 模型 UI P1:分类卡片列表视图,基本信息字段,设计器选项.

### Changed
- 流程模型设计器从弹窗改为整页编辑器(对齐 vben parity).
- 全代码库按 cljstyle 统一格式化.

### Fixed
- Migratus 多语句迁移的 `--;;` 分隔符补全(SQLite 多语句迁移此前只执行首条),
  并同步修正 MySQL 种子菜单.
- 样式审计与 E2E 闭环测试暴露的一批 BPM UI / 数据缺陷(09-19 集中修复).
- BPM 模型 UI P0 修复:新建模型入口(key 校验/重名/默认值),分类选择,
  select-strategies,权限开关,复制策略,办理人节点.

## [2026-08] 办公一体化:Flowable BPM + 请假/报销 + HRM/OA/CRM

### Added
- 集成内嵌 Flowable 8.0 工作流引擎(Phase 0 spike → `:app.bpm/engine` Integrant 组件,
  H2 独立库,`bpm/core.clj` 高层 API:部署/发起/待办/审批/驳回/转办/委派/历史).
- BPM 后端垂直切片:流程分类 / 流程模型 / 动态表单 / 流程实例映射 / 任务全链路.
- "业务记录 + BPM 审批流"旗舰集成示例:请假申请(`biz_oa_leave` + 内置
  `leaveApproval` 三级审批模型)与报销审批(`biz_oa_reimburse` + `reimburseApproval`),
  审批状态惰性自动同步.
- HTML/flex 流程设计器(对齐 vben simple-process-design):连线"+"加节点,节点配置抽屉,
  条件分支,BPMN XML ↔ 节点树双向转换;动态表单设计器与渲染器(组件库 20 种,
  含字段联动/校验/栅格/子表单/富文本/地区级联/表单模板).
- 表单字段 → 流程变量集成,审批表单回显,节点级字段权限(隐藏/只读/编辑).
- 多实例审批运行时(或签/会签/按比例);转办/委派/加签/减签/抄送操作.
- 审批流程图高亮(bpmn-js Viewer + HTML/flex 只读追踪).
- BPM 管理套件(10 菜单):用户分组 / 流程监听器 / 流程表达式 / 流程设置 /
  流程实例管理 / 流程任务管理 / 流程实例运维(数据驱动通用 CRUD).
- 办公报表看板(13 项聚合指标);HRM 员工,OA 日程/会议,CRM 客户模块(迁移/服务/控制器/路由/菜单/页面).
- BPM 动态菜单(办公目录,按权限动态下发);Playwright 办公业务流 E2E.

### Changed
- 内置请假/报销流程模型 BPMN/DI 重写为 vben 式从上到下垂直布局;请假改三级审批
  (部门经理→分管领导→HR),报销二级(部门经理→财务).
- `business.sql` 22 处 `datetime('now')` 改为 `CURRENT_TIMESTAMP`(双库通用).

### Fixed
- MySQL 双库验证并修复多个 SQLite-only 问题(TEXT 带 DEFAULT,
  `CREATE INDEX IF NOT EXISTS`,IN 空数组哨兵等),349 测试 / 882 断言在 MySQL 上 0 失败.
- 业务控制器 `:query-params` string key 导致的分页/过滤静默失效(引入 `bu/kquery`).
- 应用生命周期 bug(`stop-app` 后复用已关闭 HikariCP),全量测试套件转绿
  (349 测试 / 881 断言 / 0 失败).
- uberjar 生产构建(db.clj 补 migratus.core require),101MB 独立 jar 干净目录验证通过.

## [2026-07] 打磨与清理

### Added
- 用户管理列表支持数据权限范围 + 时间范围组合过滤.

### Changed
- 移除代码生成 / 表单构建器相关残留引用与菜单(此前 6 月实现的两个工具模块下线).
- AGENTS.md 改写为 nREPL 驱动开发流程(热重载命令,端口发现,故障排查).

### Fixed
- 暗色主题未覆盖布局容器 / 工具栏按钮 / 列表页内容区的多处残留.
- 验证码最后一位被截断.

## [2026-06] 系统管理 18 模块齐平 RuoYi-Vue

### Added
- 用户 / 角色 / 菜单 / 部门 / 岗位 / 字典 / 参数 / 通知公告 / 文件管理 / 个人中心
  等系统管理模块前后端完整实现,弥合 RuoYi-Vue 差异:角色-用户互选,数据权限范围,
  通用上传下载,验证码,用户注册,用户导入导出,缓存监控(getNames/getKeys/getValue/clear),
  定时任务增强(run/clean/changeStatus),代码生成器,在线表单构建器.
- 监控体系:操作日志 / 登录日志 / 在线用户 / 定时任务 / 服务监控 / 缓存监控 /
  数据源监控 / Integrant 依赖监控 / Swagger 接口文档.
- 前端多 Tab 页签管理(增删/激活/关闭其他),菜单拖拽排序(DndContext + 后端批量排序),
  字典标签组件,部门树选择器等可复用组件.
- Playwright E2E 测试体系接入(用户/角色/字典/参数/通知公告 CRUD 等用例).
- 内嵌 Flowable 工作流引擎 + BPMN 设计器初版集成;数据库运行时热切换
  (SQLite ↔ MySQL,无需重启 JVM).
- 项目管理文档体系:README / AGENTS.md / ROADMAP / GAP_ANALYSIS 等调研与路线图文档.

### Changed
- 构建迁移 npm → pnpm(hoisted node_modules).
- 密码哈希算法由 bcrypt+blake2b-512 改为 bcrypt+sha512,适配 MySQL
  `sys_user.password VARCHAR(100)` 长度限制.
- 前端路由移除 accountant 依赖,改写为手写 bidi 路由.
- 一批模块下线与聚焦:移除智能方案(方案智能编制)模块.

### Fixed
- 大量 UI 细节对齐 RuoYi-Vue 参考实现:布局留白,用户页排版与筛选行,
  弹窗比例,菜单表格树形列,Tab 右键菜单行为.
- MySQL 业务迁移修复,开发启动端口冲突处理,shadow-cljs Stale Output 修复.
- 登录状态持久化(刷新后从 localStorage 恢复 token,避免首屏 401).

## [2026-06-10] 初始版本

### Added
- 首次提交:RuoYi-Clojure 全栈管理后台骨架 -- Kit 框架(Integrant + Reitit)后端,
  Reagent 2 + re-frame + Ant Design 6 前端,SQLite 默认数据库,JWT 认证,
  HugSQL 查询层,Quartz 任务调度,uberjar 打包(含前端静态资源),
  clojure.test 测试套件与 Beads 问题跟踪初始化.
- Swagger API 文档与 SQLite 支持(含 MySQL DDL 迁移目录)在首周内落地.
