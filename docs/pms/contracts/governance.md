# 治理与质量 HTTP 合同

状态: 已实现并通过本地 SQLite 15 tests / 155 assertions (含 A08 项目成员任命书 3 个用例与 H02 干系人/RACI/沟通计划 2 个用例), 属于本轮全量 PMS 回归 67 tests / 486 assertions 的组成部分. 新模块 MySQL 及生产验收采用 [验证记录](../verification.md) 的最终结果. 不包含真实外部系统写入, 二进制文件服务或自动应用变更. 所有路径以 `/api/pms/projects/:id/governance` 为前缀. 路由挂在现有 JWT 认证中间件内. 下述字段为明确白名单; 未列字段返回 400.

## 事务, 权限与读模型

- GET 和 CSV 预检要求 `pms:project:query` 及项目阅读资格. 普通命令要求 `pms:project:edit` 及项目编辑资格. 审批要求 `pms:quality:approve`, 项目阅读资格, 指定审批人本人, 且审批人不能是提交人. 管理员不能绕过独立审批.
- 所有 POST 命令除预检外须传 `version`, 即 GET 返回的当前 `project_version`. 项目写入, 版本递增, 子对象变更和审计在同一数据库事务内. 陈旧版本和状态冲突返回 409; 未授权返回 403; 未找到当前项目内引用返回 404; 输入错误返回 400. 暂停和终态项目不可修改.
- 成功响应为 `{code: 200, msg: "...", data: {result: ..., project_version: N}}`. GET 和预检的 `data` 直接是各自结果. 业务失败使用同样的响应信封, 未知异常不暴露 SQL 或堆栈.
- `GET ""` 返回 `project_version`, `blockers.execution`, `blockers.closure`, 以及 `charters`, `requirements`, `documents`, `traces`, `risks`, `issues`, `meetings`, `actions`, `changes`, `gate_templates`, `gates`, `appointments`, `stakeholders`, `raci`, `comm_plans` 数组. 数组含全部不可变内容版本, 以 `id` 标识记录; `code` 是稳定业务编号, `revision` 是内容版本. 文档正文和任命书正文不进入列表. `blockers` 当前返回每个阶段的首个未满足条件. 另返回 `raci_conflicts`, 逐活动列出缺负责(A)或缺执行(R)的 `{activity, missing-accountable?, missing-responsible?}` 集合. 还返回只读追踪矩阵 `traceability` 与 `trace_summary`: `traceability` 按需求最新 `revision` 每行给出 `{requirement_id, code, revision, priority, design_links, verification_links, satisfied?, verified?, missing}`, 其中 `design_links` 计数满足关系的文档版本, `verification_links` 计数验证关系, `missing` 为 `["satisfies"|"verifies"]` 中缺失项; `trace_summary` 给出 `{requirements, fully-traced, missing-design, missing-verification}`. 分母仅取当前最新版本需求集合, 修订产生新版本后须重新追踪; 此矩阵是缺链提示, 不等于对批准范围基线的覆盖率(覆盖率分母规则仍待定义). 另返回只读文档归集视图 `document_collection`: 按每个文档 `code` 的最新 `revision` 聚合, 给出 `{total, by-stage, by-structure-node, by-classification}`; `by-stage` 与 `by-structure-node` 为 `[{key, count}]` 向量按 key 字典序且空值(未归集)排最后, `by-classification` 固定为 `[{classification, count}]` 覆盖 `public|internal|confidential` 三值. 该视图仅统计最新版本, 同一编号的旧修订不重复计数, 是只读归集, 不改变不可变版本, 授权或 SHA256 校验.
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
| POST `/documents/:rid/submit` | reviewer_id | registered/rejected -> in_review; 冻结当前最新版本并指定具备 `pms:quality:approve` 的独立发布审核人 (审核人不得为提交人, 须有项目阅读资格) |
| POST `/documents/:rid/decision` | decision: approved/rejected, reason | 只有指定审核人可决定当前最新提交版本; approved -> `approved` (正式签发发布, 记 `released_by`), rejected -> `rejected` (可再次提交) |
| POST `/traces` | requirement_id, target_kind: document/task, target_id, relation: satisfies/verifies | 关联确切需求版本与同项目文档版本或真实 WBS 任务 |
| POST `/risks` | title, probability: 1..5, impact: 1..5, owner_id, mitigation, due_date | 创建 open 风险, score = probability * impact |
| POST `/risks/:rid/mitigate` | mitigation, evidence_ids | open/mitigated -> mitigated, 必须记录实际证据 |
| POST `/risks/:rid/materialize` | 可选 title | 幂等生成问题, 记录 source_risk_id 和 issue_id; impact >= 4 为 blocker, 否则 major |
| POST `/risks/:rid/review` | outcome: active/mitigated/closed, review_note, evidence_ids, reviewer_id, 非关闭时必填 next_review_date | open/mitigated/materialized/closed -> in_review; 关联问题均须已关闭; 保留此前状态和复审依据 |
| POST `/risks/:rid/decision` | decision: approved/rejected, reason | 指定独立审核人批准后变为 open/mitigated/closed, 拒绝恢复复审前状态 |
| POST `/issues` | title, severity: blocker/major/minor, owner_id, due_date | 创建 open 问题 |
| POST `/issues/:rid/resolve` | resolution, evidence_ids, reviewer_id | open/rejected -> in_review, 提交整改证据及独立验证人 |
| POST `/issues/:rid/reopen` | reason, evidence_ids, reviewer_id | closed -> in_review, 以新原因和证据申请重开, 不直接恢复处理中 |
| POST `/issues/:rid/reassign` | owner_id, reason | 转派责任人: 状态保持 open/rejected, 新责任人须为当前项目成员否则 400, 缺原因 400, 无编辑权 403, 已关闭 409; payload 记录 reassigned_from(原责任人), reassign_reason, reassigned_by 供审计 |
| POST `/issues/:rid/decision` | decision: approved/rejected, reason | 整改验证时批准 -> closed, 拒绝 -> rejected; 重开评审时批准 -> open, 拒绝 -> closed; 必须指定人独立决定 |
| POST `/meetings` | title, held_on, minutes, attendee_ids, 可选 material_ids | 持久化纪要及 1..100 个参与人, 状态 recorded. material_ids 为可选会前资料, 须为 0..50 个不重复的同项目真实文档版本 `id`; 引用不存在或跨项目或非 document 类型 404, 重复或超 50 或非法数组 400; 留空记为 `[]` |
| POST `/meetings/:rid/actions` | title, owner_id, due_date | 创建归属该会议的 open 行动项 |
| POST `/actions/:rid/task` | 可选 start_date, duration_days, wbs_code | 同事务创建真实 WBS 任务, 状态 converted, 保存 target_task_id; 重试不重复创建 |
| POST `/actions/:rid/complete` | result, evidence_ids, reviewer_id | 提交行动完成: 状态 open/rejected -> in_review, 记录 review_action action_closure, result, 绑定的不可变证据版本 evidence_ids, 指定独立 reviewer_id(不得为提交人且具质量审批权与项目访问), submitted_by 为当前操作人; 缺证据 409, 审核人为本人 409, 无权限 403, 缺字段 400 |
| POST `/actions/:rid/verify` | decision: approved/rejected, reason | 由指定 reviewer_id 独立核验: in_review -> closed(approved)或 rejected; 非指定核验人 403, 自行核验本人提交 409; 关闭后逾期计算不再触发 |
| POST `/changes` | title, reason, scope_impact, schedule_impact, cost_impact, quality_impact, resource_impact | 创建正式变更申请草稿, 各影响维度均须说明 |
| POST `/changes/:rid/revisions` | 同上 | 最新申请的不可变新内容版本 |
| POST `/changes/:rid/submit` | reviewer_id | draft/rejected -> in_review |
| POST `/changes/:rid/decision` | decision: approved/rejected, reason | 独立批准或拒绝; 不会暗中修改任务或预算 |
| POST `/gate-templates` | code, title, stage: execution/closure, required: boolean, checks | 登记不可变模板; checks 为 1..30 个唯一编码检查项 |
| POST `/gates` | template_id, title, reviewer_id | 基于模板快照创建 draft Gate, 绑定独立审核人 |
| POST `/gates/:rid/checks` | checks | draft/ready/rejected -> ready; 完整提交所有模板项 |
| POST `/gates/:rid/submit` | 可选 waiver_reason | 通过全部必需检查并附证据, 或以明确理由申请豁免, 进入 in_review |
| POST `/gates/:rid/decision` | decision: approved/rejected/waived, reason | 独立签核; approved 再次验证证据; waived 必须已有豁免申请理由 |
| POST `/appointments` | issued_on, note | 服务器读取当前项目成员表生成不可变团队快照任命书, code 固定 APPT, revision 递增, 状态 issued; 客户端不得提交 content 或 snapshot |
| POST `/stakeholders` | code, name, role, category: internal/external/supplier/customer/regulator, interest: high/medium/low, influence: high/medium/low, 可选 owner_id | 登记 active 干系人首版, code 项目内唯一, 重复返回 409 |
| POST `/stakeholders/:rid/revisions` | 同上 | 从最新版本派生 active 新内容版本, code 不得改变, 保留 previous_id |
| POST `/raci` | activity, stakeholder_id, responsibility: R/A/C/I | 为活动指派确定职责; 同活动同干系人不得重复, 同活动至多一个 A; code 固定 RACI:活动:干系人 |
| POST `/comm-plans` | code, objective, channel: meeting/email/dashboard/report/review, frequency: daily/weekly/biweekly/monthly/quarterly, audience, next_date, 可选 owner_id | 登记 active 沟通计划首版; audience 为 1..50 个不重复同项目有效干系人; code 项目内唯一 |
| POST `/comm-plans/:rid/revisions` | 同上 | code 不变的受控新内容版本, 形成可审计的节奏调整记录 |
| POST `/comm-plans/:rid/meeting` | 可选 held_on | 由最新版本沟通计划生成 recorded 会议, 参会人取自受众干系人已绑定的项目成员, 无有效成员返回 409, 并回写计划 last_meeting_id |

所有日期均为有效 ISO 日期 `YYYY-MM-DD`. Gate 模板检查项是 `{code, title, required}`. Gate 检查结果是 `{code, passed, evidence_ids}`; 不能通过客户端改动模板的必需性. `evidence_ids` 是同项目真实文档版本 `id` 的不重复数组, 至多 50 条; 要求证据时至少 1 条. 不接受任意网址或自由文本作为已受控证据.

问题整改和重开分别以 `review_action: closure/reopen` 标识, `workflow_history` 保留此前关闭结论及每次重开决定. 风险复审使用 `review_action: risk_review`, 含 `review_previous_status`, `requested_outcome`, `submitted_by` 和 `reviewer_id`. 非关闭结论的 `next_review_date` 必须严格晚于服务端当天; 提交及批准时均验证, 过期的待审申请不能直接批准. 风险关闭仍须实际证据和独立批准. 风险关联问题在提交及批准复审时均须已关闭, 历史问题 ID 不丢失. 读模型增加 `last_reviewed_on`, `review_due_date`, `review_overdue`; 到期日为今天或之前且风险未关闭时显示逾期, 本轮不自动发送升级通知.

## 真实文档与 CSV

`content` 是真实提交文本, 包含首尾空格和换行的原始 UTF-8 字节; 非空且至多 1MiB. filename 不得含路径分隔符或换行. `registered` 表示已通过字段和摘要校验登记的不可变版本, 可用于验收引用. 文档发布审批: `POST /documents/:rid/submit` 使 `registered`/`rejected` -> `in_review` 并指定独立发布审核人, `POST /documents/:rid/decision` 由该审核人 `approved` -> `approved` (正式签发发布, 记 `released_by` 与 `decision_reason`) 或 `rejected` -> `rejected`. 提交与决定均走 `latest!`, 只能在最新版本上推进; 新修订回到 `registered`, 已批准的旧版本保持 `approved` 不漂移, 也不会替换旧 Gate, 问题或验收所引用的版本. 独立审批复用 `reviewer!` (须具备 `pms:quality:approve` 且非提交人) 与 `decision-actor!` (职责分离), 管理员不能绕过. 本轮不引入正式电子签章或外部文书模板.

文档登记与修订接受可选归集字段: `classification` 密级为枚举 `public|internal|confidential`, 缺省记为 `internal`, 非法取值返回 400; `stage` 所属阶段与 `structure_node` 结构节点为至多 100 字符的可选文本, 留空记为空串. 这些字段随不可变版本存入 payload 并进入读模型, 仅用于项目内按阶段/结构/密级归集与追踪, 不替代项目授权, 本轮不据密级过滤下载或访问. 读模型 `document_collection` 按每个编号的最新版本聚合这些字段供工作台"文档归集视图"分层展示, 前端"文档与版本证据"另提供按密级的客户端过滤(仅过滤当前展示行, 不改变服务端授权与批量下载范围).

- `GET /documents/:rid/content` 返回统一 JSON 的 `data` 文档对象, 包含 content, filename, sha256, byte_size, revision 和 id, 便于带 JWT 预览和客户端下载.
- `GET /documents/:rid/download` 返回裸文本附件, Content-Disposition 和 X-Content-SHA256. 读取仍执行项目授权.
- `POST /documents/batch-download` 请求体 `{"record_ids": [...]}`, 须为 1 到 50 个不重复的文档版本ID (空, 重复或超上限返回 400). 服务端 `kernel/read!` 逐个校验为同项目 `document` (跨项目或类型不符 404, 无项目读取权 403), 任一非法整体失败. 成功返回 `application/zip` 附件 (Content-Disposition `documents.zip`, 附 `X-Batch-Count`), 每个版本以 `<id前8位>_<filename>` 入包并保留原始 UTF-8 正文, 另含 `MANIFEST.tsv` 逐行列出 `record_id, code, revision, entry, sha256, byte_size, classification, stage, structure_node` 供离线逐文件摘要与归集信息复核. 批量下载不改变任何记录状态, 不引入按密级过滤或二进制存储.
- CSV 表头必须严格为 `code,text,category,priority,owner_id`. 最多 500 行和 1MiB. 返回 `{valid?: boolean, count, rows, errors: [{line, error}]}`; JSON 字段名是 `"valid?"`. 行号含表头, 第一条数据为 2. 检查现有编号, 文件内重复, 所有字段和有效成员. 非法表头或不可解析 CSV 直接返回 400.

## 项目成员任命书

任命书是受控的团队快照文书, 存于独立表 `pms_appointment`, 与通用 `pms_gov_record` 分离. 签发时服务器读取当前项目成员表 `pms_member` (含经理与创建者, 按 `user_id` 升序去重) 生成 `snapshot`, 由快照以固定键序序列化后计算 `snapshot_sha256`, 并派生 `content` 正文与 `headcount`. 客户端只能提交 `issued_on` 和可选 `note`, 不得提交 `content`, `snapshot` 或 `headcount`, 否则 400. 因此"任命内容与当时团队快照一致"由服务器保证, 不可被前端伪造.

- `POST /appointments` 生成新的不可变版本, `code` 固定 `APPT`, `revision` 取当前 `MAX(revision)+1`, 状态恒为 `issued`. 再次任命不清空旧版; `UNIQUE(project_id, code, revision)` 保证版本链. 团队为空返回 409.
- `GET /appointments/:rid/content` 返回统一 JSON 的 `data`, 含 `snapshot` (数组), `snapshot_sha256`, `content`, `headcount`, `revision`, `issued_on`, `issued_by`, `note`. 读取要求 `pms:project:query` 及项目阅读资格; 记录不属于该项目返回 404.
- `GET /appointments/:rid/download` 返回裸文本 `text/plain` 附件, 附 `Content-Disposition: attachment; filename=appointment-v<revision>.txt` 和 `X-Content-SHA256`, 便于离线归档与摘要复核.
- 列表读模型 `appointments` 省略 `content`, 仅保留快照与摘要供表格展示.

## 干系人, RACI与沟通计划

干系人, RACI 职责矩阵和沟通计划三类对象复用通用治理存储 `pms_gov_record` (kind 为 `stakeholder`, `raci`, `comm-plan`), 不另建独立表. 三者都是不可变版本记录: 修订以 `previous_id` 回指前一版, `revision` 递增, `code` 项目内唯一且修订不得更改, 陈旧版本上继续修订返回 409.

- 干系人登记分类 (internal/external/supplier/customer/regulator), 角色, 关注度与影响力等级, 可选绑定项目成员责任人 `owner_id`. 状态恒为 `active`.
- RACI 为具体 `activity` 指派 R/A/C/I 之一. 同一活动同一干系人不得重复指派; 同一活动至多一个负责(A)角色, 违反返回 409. 读模型 `raci_conflicts` 逐活动汇总缺 A 或缺 R 的完整性缺口, 供工作台冲突检查, 不阻止登记本身.
- 沟通计划维护目标, 渠道, 频率, 1..50 个不重复的同项目有效干系人受众和下次沟通日期, 状态 `active`; 受控修订形成可审计的节奏调整记录. `POST /comm-plans/:rid/meeting` 仅允许最新版本, 从受众干系人已绑定的项目成员去重生成参会人 (无有效成员返回 409), 落库一条 `recorded` 会议并把 `last_meeting_id` 回写到计划, 形成沟通计划到会议的闭环. `held_on` 缺省取计划 `next_date`.

本轮未提供治理工作台前端干系人/RACI/沟通计划视图与浏览器验证, 亦未接通外部通知或消息渠道推送; 沟通节奏的执行由生成会议这一本地受控事实体现, 不声称自动提醒已交付.

## 生命周期调用约定

`governance/execution-ready! [q project]` 要求当前最新章程 approved, 至少配置一个 required execution 模板, 且这些模板对应的所有实例均 approved 或 waived. 没有实例不算通过. 前段 Gate 编号未被假定为已确认业务规则, 使用配置的模板名称和阶段.

`governance/closure-ready! [q project]` 要求无未 closed 的 blocker 问题, 且 required closure Gate 全部签核. 计划基线, 费用结算和正式收尾清单由各自模块再验证; 单独 Gate 通过不代表整个项目可以关闭.

`governance/evidence-version! [q project id]` 验证同项目实际已登记文档版本, 返回含正文摘要的对象. `governance/approved-change! [q project id]` 验证最新正式变更已独立批准. 执行期间发布新计划基线必须引用已批准的 `change_id`, 仍需显式编辑计划和独立基线审批, 不会自动应用影响说明中的任务或费用变化. `blockers` 供前端说明生命周期前置缺口.

会议行动转任务复用 planning/create-task-record!, 因而同样检查当前计划是否可编辑, 成员责任人和项目计划修订. 返回 `{target_task_id, action}`. 风险转问题与行动转任务在新项目版本下重试返回同一目标, 仍产生一条本次命令的审计记录; 陈旧版本直接 409.

行动读模型对每条 action 计算派生字段 `action_overdue`: 当存在 `due_date` 且状态不属于 closed/converted 且到期日不晚于服务器当前日期时为 true; 完成提交进入 in_review 仍保持逾期, 独立核验关闭后转为 false. 该字段仅在读取时计算, 不写入存储, 也不构成主动提醒.

## 实现边界

记录表的 kind 和状态有数据库约束, 服务层按业务类型逐字段校验, 再由固定命令推进状态. 未提供任意 payload CRUD, 直接写状态或删除证据入口. 章程/变更审批冻结提交版本; URS/文档版本不可覆盖; Gate 保存模板及证据版本快照. 所有引用由项目作用域查询验证.

暂未提供二进制上传, 外部文档仓库接入, 源系统推送, 自动变更应用, 电子签名认证或法律层面的签章能力. 本地持久化, 审批和证据摘要用于本项目工程验收闭环, 不声称任何正式合规认证.
