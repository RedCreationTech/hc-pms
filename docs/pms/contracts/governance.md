# 治理与质量 HTTP 合同

状态: 本次开发的本地业务实现. 不包含真实外部系统写入, 二进制文件服务或自动应用变更. 所有路径以 `/api/pms/projects/:id/governance` 为前缀. 路由挂在现有 JWT 认证中间件内. 下述字段为明确白名单; 未列字段返回 400.

## 事务, 权限与读模型

- GET 和 CSV 预检要求 `pms:project:query` 及项目阅读资格. 普通命令要求 `pms:project:edit` 及项目编辑资格. 审批要求 `pms:quality:approve`, 项目阅读资格, 指定审批人本人, 且审批人不能是提交人. 管理员不能绕过独立审批.
- 所有 POST 命令除预检外须传 `version`, 即 GET 返回的当前 `project_version`. 项目写入, 版本递增, 子对象变更和审计在同一数据库事务内. 陈旧版本和状态冲突返回 409; 未授权返回 403; 未找到当前项目内引用返回 404; 输入错误返回 400. 暂停和终态项目不可修改.
- 成功响应为 `{code: 200, msg: "...", data: {result: ..., project_version: N}}`. GET 和预检的 `data` 直接是各自结果. 业务失败使用同样的响应信封, 未知异常不暴露 SQL 或堆栈.
- `GET ""` 返回 `project_version`, `blockers.execution`, `blockers.closure`, 以及 `charters`, `requirements`, `documents`, `traces`, `risks`, `issues`, `meetings`, `actions`, `changes`, `gate_templates`, `gates` 数组. 数组含全部不可变内容版本, 以 `id` 标识记录; `code` 是稳定业务编号, `revision` 是内容版本. 文档正文不进入列表. `blockers` 当前返回每个阶段的首个未满足条件.
- 需求, 风险, 问题和行动负责人必须是当前项目有效成员. 章程赞助人和会议参与人使用有效本地用户. 创建和审批均记录创建人, 提交人或决定人. 正文不进入通用审计日志.

## 命令字段和状态

下表省略通用 `version`. `:rid` 表示读模型的 `id`. `decision` 及其 `reason` 都必填.

| 路径 | 字段 | 业务结果 |
| --- | --- | --- |
| POST `/charters` | title, objective, scope, success_criteria, sponsor_id | 创建新的章程草稿版本, 稳定 code 为 charter |
| POST `/charters/:rid/revisions` | 同上 | 从最新版本派生新的草稿, 原版本不覆盖 |
| POST `/charters/:rid/submit` | reviewer_id | draft/rejected -> in_review; 指定独立审批人 |
| POST `/charters/:rid/decision` | decision: approved/rejected, reason | 只有指定审核人可决定当前最新提交版本 |
| POST `/requirements` | code, text, category, priority: required/desired, owner_id | 登记不可变需求版本 |
| POST `/requirements/:rid/revisions` | 同上 | code 不变, revision + 1, 保留 previous_id |
| POST `/requirements/preview` | csv; 无 version | 全量预检, 不写库 |
| POST `/requirements/import` | csv | 任一行不合法则整批拒绝, 全部通过才事务导入 |
| POST `/documents` | code, title, filename, content | 登记真实 UTF-8 文本版本, 计算 SHA256 和 byte_size |
| POST `/documents/:rid/revisions` | 同上 | 不变 code 的新版本, 原正文与摘要不变 |
| POST `/traces` | requirement_id, target_kind: document/task, target_id, relation: satisfies/verifies | 关联确切需求版本与同项目文档版本或真实 WBS 任务 |
| POST `/risks` | title, probability: 1..5, impact: 1..5, owner_id, mitigation, due_date | 创建 open 风险, score = probability * impact |
| POST `/risks/:rid/mitigate` | mitigation, evidence_ids | open/mitigated -> mitigated, 必须记录实际证据 |
| POST `/risks/:rid/materialize` | 可选 title | 幂等生成问题, 记录 source_risk_id 和 issue_id; impact >= 4 为 blocker, 否则 major |
| POST `/issues` | title, severity: blocker/major/minor, owner_id, due_date | 创建 open 问题 |
| POST `/issues/:rid/resolve` | resolution, evidence_ids, reviewer_id | open/rejected -> in_review, 提交整改证据及独立验证人 |
| POST `/issues/:rid/decision` | decision: approved/rejected, reason | approved -> closed, rejected -> rejected; 独立验证 |
| POST `/meetings` | title, held_on, minutes, attendee_ids | 持久化纪要及 1..100 个参与人, 状态 recorded |
| POST `/meetings/:rid/actions` | title, owner_id, due_date | 创建归属该会议的 open 行动项 |
| POST `/actions/:rid/task` | 可选 start_date, duration_days, wbs_code | 同事务创建真实 WBS 任务, 状态 converted, 保存 target_task_id; 重试不重复创建 |
| POST `/changes` | title, reason, scope_impact, schedule_impact, cost_impact, quality_impact, resource_impact | 创建正式变更申请草稿, 各影响维度均须说明 |
| POST `/changes/:rid/revisions` | 同上 | 最新申请的不可变新内容版本 |
| POST `/changes/:rid/submit` | reviewer_id | draft/rejected -> in_review |
| POST `/changes/:rid/decision` | decision: approved/rejected, reason | 独立批准或拒绝; 不会暗中修改任务或预算 |
| POST `/gate-templates` | code, title, stage: execution/closure, required: boolean, checks | 登记不可变模板; checks 为 1..30 个唯一编码检查项 |
| POST `/gates` | template_id, title, reviewer_id | 基于模板快照创建 draft Gate, 绑定独立审核人 |
| POST `/gates/:rid/checks` | checks | draft/ready/rejected -> ready; 完整提交所有模板项 |
| POST `/gates/:rid/submit` | 可选 waiver_reason | 通过全部必需检查并附证据, 或以明确理由申请豁免, 进入 in_review |
| POST `/gates/:rid/decision` | decision: approved/rejected/waived, reason | 独立签核; approved 再次验证证据; waived 必须已有豁免申请理由 |

所有日期均为有效 ISO 日期 `YYYY-MM-DD`. Gate 模板检查项是 `{code, title, required}`. Gate 检查结果是 `{code, passed, evidence_ids}`; 不能通过客户端改动模板的必需性. `evidence_ids` 是同项目真实文档版本 `id` 的不重复数组, 至多 50 条; 要求证据时至少 1 条. 不接受任意网址或自由文本作为已受控证据.

## 真实文档与 CSV

`content` 是真实提交文本, 包含首尾空格和换行的原始 UTF-8 字节; 非空且至多 1MiB. filename 不得含路径分隔符或换行. `registered` 表示已通过字段和摘要校验登记的不可变版本, 可用于验收引用; 本次不含独立文档发布审批. 新版本不会替换旧 Gate, 问题或验收所引用的版本.

- `GET /documents/:rid/content` 返回统一 JSON 的 `data` 文档对象, 包含 content, filename, sha256, byte_size, revision 和 id, 便于带 JWT 预览和客户端下载.
- `GET /documents/:rid/download` 返回裸文本附件, Content-Disposition 和 X-Content-SHA256. 读取仍执行项目授权.
- CSV 表头必须严格为 `code,text,category,priority,owner_id`. 最多 500 行和 1MiB. 返回 `{valid?: boolean, count, rows, errors: [{line, error}]}`; JSON 字段名是 `"valid?"`. 行号含表头, 第一条数据为 2. 检查现有编号, 文件内重复, 所有字段和有效成员. 非法表头或不可解析 CSV 直接返回 400.

## 生命周期调用约定

`governance/execution-ready! [q project]` 要求当前最新章程 approved, 至少配置一个 required execution 模板, 且这些模板对应的所有实例均 approved 或 waived. 没有实例不算通过. 前段 Gate 编号未被假定为已确认业务规则, 使用配置的模板名称和阶段.

`governance/closure-ready! [q project]` 要求无未 closed 的 blocker 问题, 且 required closure Gate 全部签核. 计划基线, 费用结算和正式收尾清单由各自模块再验证; 单独 Gate 通过不代表整个项目可以关闭.

`governance/evidence-version! [q project id]` 验证同项目实际已登记文档版本, 返回含正文摘要的对象. `governance/approved-change! [q project id]` 验证最新正式变更已独立批准. 计划模块可将 change_id 固定在新的基线发布中, 仍需显式编辑和基线审批. `blockers` 供前端说明生命周期前置缺口.

会议行动转任务复用 planning/create-task-record!, 因而同样检查当前计划是否可编辑, 成员责任人和项目计划修订. 返回 `{target_task_id, action}`. 风险转问题与行动转任务在新项目版本下重试返回同一目标, 仍产生一条本次命令的审计记录; 陈旧版本直接 409.

## 实现边界

记录表的 kind 和状态有数据库约束, 服务层按业务类型逐字段校验, 再由固定命令推进状态. 未提供任意 payload CRUD, 直接写状态或删除证据入口. 章程/变更审批冻结提交版本; URS/文档版本不可覆盖; Gate 保存模板及证据版本快照. 所有引用由项目作用域查询验证.

暂未提供二进制上传, 外部文档仓库接入, 源系统推送, 自动变更应用, 电子签名认证或法律层面的签章能力. 本地持久化, 审批和证据摘要用于本项目工程验收闭环, 不声称任何正式合规认证.
