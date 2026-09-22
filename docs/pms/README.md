# HC-PMS 设计与开发基线

版本: V1, 2026-09-22. 根据既有 PMS 功能讨论, 原始业务蓝图以及指定 `ruoyi-template` 分支核对形成. 业务蓝图原件和逐页提炼保留在本地工作资料中, 不随本仓库公开发布.

## 阅读顺序

| 文档 | 内容 |
|---|---|
| [01 产品定位](01-product-overview.md) | 订单项目边界, 角色, 首批范围 |
| [02 业务蓝图](02-business-blueprint.md) | 两级项目, 三类计划, 四算, 五类过程, 集成 |
| [03 C4 上下文](03-c4-context.md) | 用户, 外部系统, 事实所有权 |
| [04 C4 容器](04-c4-container.md) | 技术架构, ADR, 事务与运维 |
| [05 领域模型](05-domain-model.md) | 聚合, 状态, 约束与关键命令 |
| [06 数据库设计](06-database-design.md) | 首批物理表与后续逻辑模型 |
| [07 API设计](07-api-design.md) | 首批接口, 输入/错误/权限, 后续接口族 |
| [08 界面设计](08-ui-design.md) | 导航, 页面与关键交互 |
| [09 AI设计](09-ai-agent-design.md) | 授权证据, 建议, 人工确认与评估 |
| [10 开发路线](10-development-roadmap.md) | 分批交付, 依赖和验收 |

## 来源核对与修正

- 基础代码: [ruoyi-template](https://github.com/RedCreationTech/ruoyi_clojure/tree/ruoyi-template), 固定基线 `99706b06fea4ff9efed6822a70cbda1c2bd98552`.
- 产品参考: [ruoyi-office-vben](https://github.com/yuqing2026/ruoyi-office-vben), [ruoyi-office](https://github.com/yuqing2026/ruoyi-office). 在本次读取的默认分支目录中没有检索到可直接复用的 PMS 模块路径. README 中的功能介绍仅作为产品参考, 不能据此宣称已取得或移植其 PMS 实现.
- 实际模板为 Kit + ClojureScript/Reagent/AntD, 首批延续此栈. Vue3/Vben 与 PostgreSQL 不作为本轮已实现技术.
- 原业务蓝图中的嵌入式集成表图标未提供可读取的完整字段合同. Gate前段编号,日历规则和成本细项应在对应批次做配置与现场确认, 不影响首批项目基础功能开发.

## 当前边界

本轮已开始 Batch 1 的实现, 交付项目中心/结构/成员/基础生命周期/审计/驾驶舱. 完整 Gate, WBS基线, URS验证, 文档签发, 财务四算, 外部适配器及 AI 执行仍为设计与后续工作. 具体测试证据和环境限制见 [验证记录](verification.md).

架构图使用本机 PlantUML 内置 C4 库渲染. 源码位于 `doc/diagrams`, 不需要向公共渲染服务发送设计内容.
