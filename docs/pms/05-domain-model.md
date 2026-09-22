# HC PMS 领域模型

状态: 目标逻辑模型. 本文区分 Batch 1 核心与后续设计. 物理表名, 字段类型和当前接口以数据库设计及实际迁移为准; 概念存在于本文不等于已落库.

## 聚合和边界

| 聚合 | 根对象 | 核心关联/不变量 | 交付边界 |
|---|---|---|---|
| 项目中心 | Project | 唯一业务编码, 负责人, 主项目信息, 生命周期 | Batch 1 |
| 项目结构 | ProjectNode | 所属主项目, parent_id, 类型, 排序; 同树且无环 | Batch 1 |
| 项目团队 | ProjectMember | 项目+用户唯一, 项目角色/职责, 有效性 | Batch 1 |
| 审计 | AuditEvent | 操作人, 时间, 对象, 操作, 变更摘要, 聚合版本 | Batch 1 |
| 计划 | Plan/PlanVersion | 对应项目或节点, 当前工作版与不可变基线 | 后续 |
| 工作分解 | WbsTask/Dependency | 分层工作, 执行者, 时间, FS等依赖类型 | 后续 |
| 关口 | GateReview | 适用模板, 检查项快照, 证据版本, 评审决定 | 后续 |
| 需求 | Requirement/RequirementVersion | 项目内唯一编号, 需求版本及追踪链接 | 后续 |
| 交付物 | Deliverable/DocumentVersion | 文件存储引用, 摘要, 版本, 审核与密级 | 后续 |
| 变更 | ChangeRequest | 变更范围, 旧/新版本, 影响, 批准依据 | 后续 |
| 问题/风险 | Issue/Risk | 责任与严重度, 处置, 验证, 相互来源关系 | 后续 |
| 会议 | Meeting/ActionItem | 参会人员, 纪要版本, 行动项转任务关联 | 后续 |
| 申请 | BusinessApplication | 类型, 来源任务, 外部流程关联与状态 | 后续 |
| 执行投影 | ExecutionSnapshot | 节点/工单/测试阶段, 来源版本, 时间和指标 | 后续 |
| 财务 | CostSnapshot/CostEntry/AllocationRun | 项目/期间/币种/口径/版本, 源账项和分摊 | 后续 |
| 集成 | ExternalMapping/InboxEvent/OutboxEvent | 外部业务键, 幂等键, 投递与处理状态 | 后续 |

## 项目中心

`Project` 保存编码, 名称, 项目类型, 负责人, 所属部门, 状态, 计划日期, 创建/修改元信息和版本信息. 首批还支持客户名称和合同编号的人工录入, 不应把这些值解释为外部系统已同步. 描述等扩展字段尚未提供. 外部合同/订单编号使用独立字段或映射表, 不把外部编号作为内部主键.

项目代码应标准化空白并在数据库层确保唯一; 日期校验要求结束不早于开始. 负责人必须是有效用户. 首批项目按一个受控聚合维护结构和成员, 不开放跨项目移动节点. 首批不提供项目删除或归档, 取消用状态保留记录. 后续的归档/删除命令需要引用和保留策略.

### 结构

首批节点类型为 `main/sub/machine`. main/sub 表达两级项目责任, machine 表达单机交付/计划对象, 不是第三级组织项目. 允许的关系是主节点下建立子项目, 子项目下建立单机. 根节点归属唯一主项目, 节点不能挂到自己或后代. 对节点的读写先检查其项目授权, 再校验父节点归属. 首批只新增和读取子项目/单机节点, 主节点名称和编号随项目编辑同步. 节点编辑/删除/迁移留到后续受控命令, 引用校验不得隐式丢失履约证据.

产品线或单元等更丰富结构可以后续由节点类型扩展. 不把层数当作语义; 同一类型的节点可以承载明确的外部编码. 单机级暂停在后续具备任务范围和恢复策略后启用.

### 成员和数据范围

功能权限回答用户能执行什么操作, 项目成员范围回答用户能操作哪些项目. 两者取交集. 管理员特权必须由模板中明确的管理员语义判断; 不能按用户名字符串, 前端路由或请求传入角色授权.

项目创建人/负责人获得的默认范围必须由服务端统一授予. 列表, 详情, 树, 成员, 统计及所有修改使用同一范围函数, 不只在列表加过滤. 修改负责人时必须同步处理成员关系并确保项目仍有可管理者. 首批提供 manager/editor/viewer 成员添加和角色调整, 不提供移除. 项目负责人具有写范围, manager/editor成员具有写范围, viewer仅具有读范围; 所有操作仍检查相应功能权限, 明确的管理员资格可越过项目范围. 变更负责人后旧负责人保留editor成员资格, 创建者保留读范围. 后续增加撤销成员命令时, 新请求重新验证权限并保留撤销审计, 不能把登录时的成员快照长期当作授权依据.

## 状态机

### 当前开发边界

| 当前状态 | 动作 | 目标 | 必要条件 |
|---|---|---|---|
| draft | 立项登记 | initiated | 相应功能权限和项目写范围, 当前version |
| initiated | 进入计划 | planning | 相应功能权限和项目写范围, 当前version |
| draft/initiated/planning | 取消 | cancelled | 有相应权限, 确认业务对象并记录原因 |
| planning | 开始执行 | 无迁移 | Batch 1 明确拒绝, HTTP 409 |
| cancelled | 再迁移 | 无迁移 | 首批不提供取消后恢复 |

创建/编辑时校验必要字段及有效负责人/部门, 首批状态命令额外校验版本和迁移边, 不代表已执行结构审查或立项审批. 取消必须填写原因. 状态更新不作为普通编辑字段任意赋值, 必须经过专用迁移命令. 拒绝的迁移不得改变状态或产生成功审计.

### 后续完整设计

目标状态为 `draft -> initiated -> planning -> execution -> closing -> closed`, 外加 `suspended` 和 `cancelled`. 后续 `execution` 状态在计划基线和 Gate 能力验收前保持禁用. V1 不增加 `testing` 或 `delivery` 项目枚举: Testing 对应 SIT/FAT/SAT 阶段及确认 Gate, Delivery 对应发运, 交底和现场交付阶段及验收 Gate. 阶段可以按单机并行, 不以主项目单一状态掩盖这种并行关系. Batch 1 尚未实现这些阶段/Gate.

从 planning 进入执行需要已批准基线和适用 Gate, 从执行进入收尾需要规定验收与阻塞问题闭合, 关闭需要收尾清单. 暂停保存 `resume_state` 及暂停范围, 恢复回到该状态并重新校验条件. 已关闭/取消项目只允许受控纠错或另建变更, 不自动重新开启.

任务目标状态为 draft/ready/in_progress/blocked/completed/cancelled. Gate 目标状态为 not_ready/ready/in_review/approved/rejected/waived. 申请投影则使用 submitted/external_pending/approved/rejected/withdrawn. 这些状态不能混用, 更不能把 integration delivered 映射成业务 approved.

## 计划, 基线与证据

PlanVersion 是一版完整计划的不可变标识. 草稿编辑后提交审批, 审批通过冻结为 baseline. 节点任务可以关联阶段, 负责人, 时间, 权重和依赖. Parent-child 树与任务依赖图分别校验, 两类循环都拒绝.

GateReview 保留检查模板版本, 每个检查结果, 审核意见及证据引用. DocumentVersion 存储内容摘要和不可变对象地址, RequirementVersion 存储条目内容快照. TraceLink 把两个确定版本连接起来, 包括 `satisfies/verifies/deviates_from/supersedes` 等关系, 不能只连会漂移的最新对象.

## 费用模型

`CostEntry` 至少包含源系统, 源账项 ID/行号/版本, 项目和可选节点, 费用类别, 原币金额, 币种, 期间, 记账日期, 调整关系及取数时间. `(source, entry_id, line_id, revision)` 可识别源账项, 当前有效版本的选择单独控制.

`CostSnapshot` 具有 estimate/budget/actual/settlement 口径, 版本, 批准状态和封账时间. `AllocationRun` 包含费用池, 期间, 算法版本, 工时快照, 输入总额, 输出明细, 舍入残差以及批准人. 不把未审批费用池当作正式成本. 完整算法和待确认口径见业务蓝图.

## 集成模型

`ExternalMapping` 的键包括 system, entity_type, external_id, 外部版本及 local_id. `InboxEvent` 保存认证来源, event_id, 收到时间, payload hash, schema_version, 处理结果和错误. `OutboxEvent` 在本地业务事务提交, 记录目标系统, 业务事件, 去重键和最少必要字段. 每次发送尝试独立写日志.

事件的 `occurred_at`, `received_at`, `processed_at` 分开保存, 便于识别延迟. 较老来源版本不能覆盖较新状态. 无法识别项目的事件进入隔离队列, 不自动创建无权限归属的业务数据.

## 统一不变量

- 数据库事务覆盖同一聚合的持久化和成功审计/出站事件.
- 对金额使用 decimal, 对时间明确时区, 对工作日计算使用版本化日历.
- 软删除对象不得出现在默认业务列表; 审计和源引用仍保留.
- 错误返回稳定业务代码和可处理信息, 不泄露 SQL, 内部路径或越权对象详情.
- 审计不保存密码, token 或完整敏感文件内容. 批准/拒绝记录保留行为人及当时角色.
- 后续并发版本冲突返回明确冲突, 不静默覆盖. 若首批尚未支持, 在验收记录中列为局限而非声称已完成.
