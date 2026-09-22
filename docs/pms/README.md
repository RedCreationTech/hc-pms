# HC-PMS 设计与开发基线

版本: V1, 2026-09-22. 根据既有 PMS 功能讨论, 原始业务蓝图以及指定 `ruoyi-template` 分支核对形成. 业务蓝图原件和逐页提炼保留在本地工作资料中, 不随本仓库公开发布.

## 最终目标与完成口径

用户于2026-09-22明确两个连续目标: 先对齐原PPT展示的PMS全部功能, 再在此基础上完成标准PMS的功能闭环. 本项目以订单项目从批准, 范围与计划, 资源和执行, 风险/问题/变更控制, 质量与验收, 费用结算到收尾归档的完整链路作为工程验收基线. 不把这一基线称为正式标准认证.

需求完成以[功能验收矩阵](11-feature-acceptance-matrix.md)和实际验证记录为准. 页面存在或接口返回成功不足以证明业务闭环; 待澄清的PPT规则保留在矩阵中, 不能静默移出范围. 首批范围仅表示开发顺序, 不缩减最终目标.

## 阅读顺序

| 文档 | 内容 |
|---|---|
| [01 产品定位](01-product-overview.md) | 订单项目边界, 角色, 目标范围 |
| [02 业务蓝图](02-business-blueprint.md) | 两级项目, 三类计划, 四算, 五类过程, 集成 |
| [03 C4 上下文](03-c4-context.md) | 用户, 外部系统, 事实所有权 |
| [04 C4 容器](04-c4-container.md) | 技术架构, ADR, 事务与运维 |
| [05 领域模型](05-domain-model.md) | 聚合, 状态, 约束与关键命令 |
| [06 数据库设计](06-database-design.md) | 首批物理表与后续逻辑模型 |
| [07 API设计](07-api-design.md) | 首批接口, 输入/错误/权限, 后续接口族 |
| [08 界面设计](08-ui-design.md) | 导航, 页面与关键交互 |
| [09 AI设计](09-ai-agent-design.md) | 授权证据, 建议, 人工确认与评估 |
| [10 开发路线](10-development-roadmap.md) | 分批交付, 依赖和验收 |
| [11 功能验收矩阵](11-feature-acceptance-matrix.md) | PPT逐项覆盖, 标准PMS补全, 状态与验收证据 |
| [12 操作体验指南](12-user-guide.md) | 从新建项目到独立审批, 实际交付, 财务收尾与受控重开的操作顺序 |
| [当前验证记录](verification.md) | 本轮双库, 浏览器, 构建与运行的真实证据和限制 |
| [Batch 1 历史验证](verification-batch1.md) | 首批项目中心的历史基线, 不替代本轮回归 |

本轮新增模块的实际字段, 状态命令, 权限和业务前提见 [规划合同](contracts/planning.md), [治理合同](contracts/governance.md), [交付合同](contracts/delivery.md), [工时财务与收尾合同](contracts/finance-closure.md), [集成运行时合同](contracts/integration.md). 设计中的后续逻辑模型不能当作已经实现的接口.

## 来源核对与修正

- 基础代码: [ruoyi-template](https://github.com/RedCreationTech/ruoyi_clojure/tree/ruoyi-template), 固定基线 `99706b06fea4ff9efed6822a70cbda1c2bd98552`.
- 产品参考: [ruoyi-office-vben](https://github.com/yuqing2026/ruoyi-office-vben), [ruoyi-office](https://github.com/yuqing2026/ruoyi-office). 在本次读取的默认分支目录中没有检索到可直接复用的 PMS 模块路径. README 中的功能介绍仅作为产品参考, 不能据此宣称已取得或移植其 PMS 实现.
- 实际模板为 Kit + ClojureScript/Reagent/AntD6, 本轮延续此栈, 采用 SQLite 开发和 MySQL 兼容验证. Vue3/Vben 与 PostgreSQL 不作为本轮已实现技术.
- 原业务蓝图中的嵌入式集成表图标未提供可读取的完整字段合同. Gate前段编号, 企业日历适用规则和成本细项仍需业务确认; 已实现的工程默认规则均有明确边界.

## 当前边界

当前交付已包含项目中心及本地工业订单主链: 章程独立批准, WBS/排程/容量/基线, URS版本与真实文本证据, 风险/问题/变更和Gate, 物料申请/冻结BOM/齐套/装配/SIT/FAT/发运签收/SAT/售后, 工时与本地四算, 正式收尾快照和受控重开. 各模块使用真实持久化, 项目权限, 独立审批, 版本并发保护及事务审计. 通用集成运行时具备入出站去重, 版本保护, 实际HTTP回执验证, 重试和死信处理.

源码提交 `cfe4b15` 的 [CI运行35682432061](https://github.com/RedCreationTech/hc-pms/actions/runs/35682432061) 中 SQLite 与 MySQL 后端已通过, SQLite 62 tests / 423 assertions, MySQL 62 tests / 392 assertions. 本地 8 个真实浏览器用例通过, 生产前端和 uberjar 构建通过; 独立启动验证 health=true, 项目页面200, 未认证请求401. Linux CI浏览器同组8例5.1分钟首次全部通过,无重试/flaky,SQLite/MySQL/web三项全success. 详细结果见 [验证记录](verification.md).

全部PPT能力与标准PMS补全目标仍未完成. 八套真实企业系统适配器尚待接口和沙箱; 完整主子单机计划网络/进度卷积/局部暂停, 专属业务模板与专项包材/直发/现场流程, 二进制/密级/批量文档和正式签发, RACI/沟通通知/知识复用, 完整财务费率/跨项目费用池/期间封账/汇率税务/合并经营, AI仍有明确待办. 生产升级恢复, 容量兼容性, 历史数据迁移和业务UAT尚未验收. machine是交付结构节点, 不冒称第三级组织项目; 本地人工事实与HTTP协议测试不冒称已连接企业源系统. 逐项范围及证据见 [106项验收矩阵](11-feature-acceptance-matrix.md).

架构图使用本机 PlantUML 内置 C4 库渲染. 源码位于 `doc/diagrams`, 不需要向公共渲染服务发送设计内容.
