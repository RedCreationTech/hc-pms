# 治理与质量 HTTP 合同

状态: 已实现并通过本地 SQLite 50 tests / 590 assertions (含 A08 项目成员任命书, H02 干系人/RACI/沟通计划, C06 文档独立发布审批, C06 证据发布覆盖度只读派生, C04 文档归集视图, H01 章程初始预算, H01 章程显式授权项目经理, H09 变更量化影响与高影响只读派生, H08 风险超阈值自动升级, H08 复评重新评分并重算升级门控, H08 风险应对策略可选枚举字段, C02 需求验证方式可选枚举字段, C02 需求验证方式覆盖度只读派生, C10 典型风险库一键实例化, H02 沟通节奏标记已沟通与到期预警, C09 问题逾期预警, C09d 问题阻断级自动升级, H18 受控作废与受控恢复, H18c 归集剔除已作废与级联影响预览, 责任人跨类负载预警等用例), 属于本轮全量 PMS 回归 102 tests / 921 assertions 的组成部分. 新模块 MySQL 及生产验收采用 [验证记录](../verification.md) 的最终结果. 不包含真实外部系统写入, 二进制文件服务或自动应用变更. 所有路径以 `/api/pms/projects/:id/governance` 为前缀. 路由挂在现有 JWT 认证中间件内. 下述字段为明确白名单; 未列字段返回 400.

## 事务, 权限与读模型

- GET 和 CSV 预检要求 `pms:project:query` 及项目阅读资格. 普通命令要求 `pms:project:edit` 及项目编辑资格. 审批要求 `pms:quality:approve`, 项目阅读资格, 指定审批人本人, 且审批人不能是提交人. 管理员不能绕过独立审批.
- 所有 POST 命令除预检外须传 `version`, 即 GET 返回的当前 `project_version`. 项目写入, 版本递增, 子对象变更和审计在同一数据库事务内. 陈旧版本和状态冲突返回 409; 未授权返回 403; 未找到当前项目内引用返回 404; 输入错误返回 400. 暂停和终态项目不可修改.
- 成功响应为 `{code: 200, msg: "...", data: {result: ..., project_version: N}}`. GET 和预检的 `data` 直接是各自结果. 业务失败使用同样的响应信封, 未知异常不暴露 SQL 或堆栈.
- `GET ""` 返回 `project_version`, `blockers.execution`, `blockers.closure`, 以及 `charters`, `requirements`, `documents`, `traces`, `risks`, `issues`, `meetings`, `actions`, `changes`, `gate_templates`, `gates`, `appointments`, `stakeholders`, `raci`, `comm_plans` 数组. 数组含全部不可变内容版本, 以 `id` 标识记录; `code` 是稳定业务编号, `revision` 是内容版本. 文档正文和任命书正文不进入列表. `blockers` 当前返回每个阶段的首个未满足条件. 另返回 `raci_conflicts`, 逐活动列出缺负责(A)或缺执行(R)的 `{activity, missing-accountable?, missing-responsible?}` 集合. 还返回只读追踪矩阵 `traceability` 与 `trace_summary`: `traceability` 按需求最新 `revision` 每行给出 `{requirement_id, code, revision, priority, design_links, verification_links, satisfied?, verified?, missing}`, 其中 `design_links` 计数满足关系的文档版本, `verification_links` 计数验证关系, `missing` 为 `["satisfies"|"verifies"]` 中缺失项; `trace_summary` 给出 `{requirements, fully-traced, missing-design, missing-verification}`. 分母仅取当前最新版本需求集合, 修订产生新版本后须重新追踪; 此矩阵是缺链提示, 不等于对批准范围基线的覆盖率(覆盖率分母规则仍待定义). 另返回只读文档归集视图 `document_collection`: 按每个文档 `code` 的最新 `revision` 且状态非 `discarded` 聚合, 给出 `{total, discarded-count, by-stage, by-structure-node, by-classification}`; `total` 与各分桶仅计入未作废的最新版本, `discarded-count` 单独统计最新版本已被作废的编号数; `by-stage` 与 `by-structure-node` 为 `[{key, count}]` 向量按 key 字典序且空值(未归集)排最后, `by-classification` 固定为 `[{classification, count}]` 覆盖 `public|internal|confidential` 三值. 该视图仅统计最新版本, 同一编号的旧修订不重复计数, 是只读归集, 不改变不可变版本, 授权或 SHA256 校验.
- `GET ""` 另返回只读典型风险库 `risk_library` (C10): 服务端内置的 `{key, category, title, probability, impact, mitigation, stage}` 条目向量, 供前端"从典型风险库选用"下拉展示; 它是随代码发布的精选目录, 不落库为独立治理记录类型, 因此无新增迁移. `risks` 数组中原样回显由库实例化写入的可选字段 `source_key`(库条目 key), `source_category`(库分类) 与 `stage`(适用阶段), 手工登记的风险不含这三个键. `issues` 数组按服务器当天计算只读派生字段 `issue_overdue`(存在到期日, 状态非 closed 且到期日不晚于当天时为 true)与 `issue_critical`(严重度为 blocker 时为 true), 仅用于台账预警展示. `comm_plans` 数组按服务器当天计算只读派生字段 `comm_overdue`(状态 active 且 `next_date` 不晚于当天时为 true)与 `comm_days_until`(距下次沟通的整数天数, 无有效日期时为 null), 并回显 `last_communicated_on`, `last_communication_note` 与逐次追加的 `communication_log`. 以上派生字段均在读取时计算, 不写入存储, 也不构成主动提醒或通知投递.
- `GET ""` 另返回三项只读治理洞察派生字段, 均在读取时按当前数据计算, 不写入存储, 不新增迁移, 也不构成提醒投递: (1) `stakeholders` 每条按 `influence`(权力)与 `interest`(利益)派生 `stakeholder_quadrant`(权力-利益矩阵象限: `manage-close`|`keep-satisfied`|`keep-informed`|`monitor`)与 `stakeholder_unbound`(未绑定 `owner_id` 时为 true), 供"干系人识别"台账给出管理策略与责任人缺口提示; (2) `raci` 每条按该干系人在全部活动中被指派为执行(R)的次数补充 `raci_r_load`(整数负载)与 `raci_overloaded`(负载达到阈值 3 时为 true), 负责(A)/咨询(C)/知会(I)不计入负载, 供"RACI职责矩阵"提示责任集中; (3) `meetings` 每条汇总其派生行动给出 `meeting_action_total`(行动总数), `meeting_open_actions`(状态非 closed/converted 的未完成数)与 `meeting_overdue_actions`(其中到期日不晚于服务器当天的未完成数), 供"会议行动"台账展示行动闭环与逾期集中度.
- `GET ""` 另在 `issues`, `risks`, `actions` 三张台账的每条记录上补充同一套只读跨类负载派生字段, 均在读取时按当前数据计算, 不写入存储, 不新增迁移, 也不构成提醒投递: `owner_open_load` 为该记录 `owner_id`(责任人)在"问题+风险+行动"三类中当前未关闭的事项总数(问题与风险排除 `closed`, 行动排除 `closed`/`converted`, 与逾期/闭环口径一致), `owner_overloaded` 在该跨类负载达到阈值 4 时为 true. 无 `owner_id` 的记录 `owner_open_load` 为 0 且 `owner_overloaded` 为 false. 三张台账对同一责任人回显相同的 `owner_open_load`, 用于暴露"一人跨问题/风险/行动被集中指派"的负载失衡.
- 需求, 风险, 问题和行动负责人必须是当前项目有效成员. 章程赞助人和会议参与人使用有效本地用户. 创建和审批均记录创建人, 提交人或决定人. 正文不进入通用审计日志.
- 章程可选初始预算: `initial_budget` 为最多两位小数的非负金额, 由服务端规范化为两位小数最小单位后回显 (如 `120000.5` -> `120000.50`), 负数或超过两位小数返回 400. `budget_currency` 取 `CNY|USD|EUR|GBP|HKD` 之一, 填预算而未选币种时缺省 `CNY`, 非法币种返回 400. 两字段随内容版本不可变冻结, 修订须重新提交完整内容 (旧版本预算值不漂移). 未填 `initial_budget` 则两键均不写入, 章程仍按原样创建. 预算是章程专属字段, 出现在变更申请体上按白名单返回 400. 此为立项期声明的初始预算, 不等于批准后锁定的财务基线或成本台账 (后者由财务域独立管理).
- 章程可选授权项目经理: `authorized_pm_id` 为可选显式字段, 须为有效本地用户 (经 `s/user!` 校验, 不存在或已停用返回 400). 填写时随内容版本不可变冻结, 修订须重新提交完整内容 (旧版本授权 PM 不漂移, 批准依据保持); 未填则不写入该键, 项目经理仍由项目 `manager_id` 隐含承载. 该字段是章程专属, 出现在变更申请体上按白名单返回 400. 此为章程显式记录的授权对象, 本轮不据此自动改写下述项目编辑/审批权限或触发通知投递 (仍待与权限联动补齐).
- 变更可选量化影响: `schedule_impact_days` 为可选整数天 (须为 0 至 3650 的整数, 非整数, 负数或超范围返回 400), `cost_impact_amount` 为可选金额 (最多两位小数的非负金额, 由服务端规范化为两位小数后回显, 如 `150000.5` -> `150000.50`; 负数或超两位小数返回 400). 两字段随内容版本不可变冻结, 修订须重新提交完整内容 (旧版本量化值不漂移); 未填则不写入对应键, 变更仍按原样创建. 两字段是变更专属, 出现在章程体上按白名单返回 400. `GET ""` 的 `changes` 每条按服务器读取时派生只读布尔 `change_high_impact`: 当 `schedule_impact_days >= 10` 或 `cost_impact_amount >= 100000.00` 时为 `true`, 否则为 `false` (未量化亦为 `false`); 该判定仅用于台账高影响预警展示, 不写入存储, 不新增迁移, 不自动升级审批链或改变状态机 (阈值联动审批仍待补齐).

## 命令字段和状态

下表省略通用 `version`. `:rid` 表示读模型的 `id`. `decision` 及其 `reason` 都必填.

| 路径 | 字段 | 业务结果 |
| --- | --- | --- |
| POST `/charters` | title, objective, scope, success_criteria, sponsor_id; 可选 initial_budget, budget_currency, authorized_pm_id | 创建新的章程草稿版本, 稳定 code 为 charter |
| POST `/charters/:rid/revisions` | 同上 | 从最新版本派生新的草稿, 原版本不覆盖 |
| POST `/charters/:rid/submit` | reviewer_id | draft/rejected -> in_review; 指定独立审批人 |
| POST `/charters/:rid/decision` | decision: approved/rejected, reason | 只有指定审核人可决定当前最新提交版本 |
| POST `/requirements` | code, text, category, priority: required/desired, owner_id, 可选 verification_method (test/inspection/demonstration/analysis, 缺省或空值不写键, 非法取值 400) | 登记不可变需求版本 |
| POST `/requirements/:rid/revisions` | 同上 | code 不变, revision + 1, 保留 previous_id; 修订可改 verification_method, 原版本值不漂移 |
| POST `/requirements/preview` | csv; 无 version | 全量预检, 不写库 |
| POST `/requirements/import` | csv | 任一行不合法则整批拒绝, 全部通过才事务导入 |
| POST `/documents` | code, title, filename, content | 登记真实 UTF-8 文本版本, 计算 SHA256 和 byte_size |
| POST `/documents/:rid/revisions` | 同上 | 不变 code 的新版本, 原正文与摘要不变 |
| POST `/documents/:rid/submit` | reviewer_id | registered/rejected -> in_review; 冻结当前最新版本并指定具备 `pms:quality:approve` 的独立发布审核人 (审核人不得为提交人, 须有项目阅读资格) |
| POST `/documents/:rid/decision` | decision: approved/rejected, reason | 只有指定审核人可决定当前最新提交版本; approved -> `approved` (正式签发发布, 记 `released_by`), rejected -> `rejected` (可再次提交) |
| POST `/traces` | requirement_id, target_kind: document/task, target_id, relation: satisfies/verifies | 关联确切需求版本与同项目文档版本或真实 WBS 任务 |
| POST `/risks` | title, probability: 1..5, impact: 1..5, owner_id, mitigation, due_date, 可选 response_strategy (avoid/transfer/mitigate/accept, 缺省不写键, 非法取值 400) | 创建 open 风险, score = probability * impact; score >= 16 时自动标记 `escalated: true`, `escalation_state: pending`, 附 `escalation_level`(16..19 management, >=20 steering)与 `escalation_reason` |
| POST `/risks/from-library` | template_key, owner_id, due_date | 从内置典型风险库(C10)选用一条, 按库中标准 probability*impact 自动评分并套用措施与适用阶段, 落库为一条 open 风险, 附加 `source_key`, `source_category`, `stage`; 与手工登记共用同一评分与超阈值自动升级门控(score>=16 即 `escalated`/`pending`); 未知 template_key 返回 404, 缺责任人或期限返回 400 |
| POST `/risks/:rid/mitigate` | mitigation, evidence_ids | open/mitigated -> mitigated, 必须记录实际证据; 若 `escalated` 且 `escalation_state` 仍为 pending 则 409, 须先经升级确认 |
| POST `/risks/:rid/escalate` | decision: approved/rejected, note | 由登记人之外的独立质量审批人(`pms:quality:approve` + 项目读范围)确认超阈值风险升级处置; approved -> `escalation_state: acknowledged`(责成处置), rejected -> `waived`(经评估可在现层处置), 均记录 `escalation_ack_by/note/on` 与 `workflow_history`; 仅 pending 可确认, 未升级或已确认返回 409, 登记人自确认返回 403 |
| POST `/risks/:rid/materialize` | 可选 title | 幂等生成问题, 记录 source_risk_id 和 issue_id; impact >= 4 为 blocker, 否则 major |
| POST `/risks/:rid/review` | outcome: active/mitigated/closed, review_note, evidence_ids, reviewer_id, 非关闭时必填 next_review_date, 可选 probability 与 impact (重新评分须成对填写) | open/mitigated/materialized/closed -> in_review; 关联问题均须已关闭; 保留此前状态和复审依据; 若同时提议新概率与影响则按 `review_proposed_probability`/`review_proposed_impact`/`review_proposed_score` 暂存为待批准提议, 不改动现有 `score` 与升级状态; 只填其一返回 400, 概率或影响越界(非 1..5)返回 400 |
| POST `/risks/:rid/decision` | decision: approved/rejected, reason | 指定独立审核人批准后变为 open/mitigated/closed, 拒绝恢复复审前状态; 批准且结论非关闭且存在复评提议重评时, 按新概率×影响重算 `score` 并重新判定 H08 超阈值升级门控(达阈值转为 `escalated`/`pending`, 未达阈值则清理 escalation 键), 无论批准或拒绝都清理 `review_proposed_*` 临时键 |
| POST `/issues` | title, severity: blocker/major/minor, owner_id, due_date | 创建 open 问题; severity=blocker 时登记即自动升级: 写入 `escalated: true`, `escalation_state: pending`, `escalation_level`(登记时已逾期为 steering, 否则 management)与 `escalation_reason`; 非阻断级不写任何升级键 |
| POST `/issues/:rid/resolve` | resolution, evidence_ids, reviewer_id | open/rejected -> in_review, 提交整改证据及独立验证人; 若 `escalated` 且 `escalation_state` 仍为 pending 则 409, 须先经独立升级确认 |
| POST `/issues/:rid/escalate` | decision: approved/rejected, note | 由登记人之外的独立质量审批人(`pms:quality:approve` + 项目读范围)确认阻断级问题升级处置; approved -> `escalation_state: acknowledged`(责成处置), rejected -> `waived`(经评估可在现层处置), 均记录 `escalation_ack_by/note/on` 与 `workflow_history`; 仅 pending 可确认, 未升级或已确认返回 409, 登记人自确认返回 403 |
| POST `/issues/:rid/reopen` | reason, evidence_ids, reviewer_id | closed -> in_review, 以新原因和证据申请重开, 不直接恢复处理中 |
| POST `/issues/:rid/reassign` | owner_id, reason | 转派责任人: 状态保持 open/rejected, 新责任人须为当前项目成员否则 400, 缺原因 400, 无编辑权 403, 已关闭 409; payload 记录 reassigned_from(原责任人), reassign_reason, reassigned_by 供审计 |
| POST `/issues/:rid/decision` | decision: approved/rejected, reason | 整改验证时批准 -> closed, 拒绝 -> rejected; 重开评审时批准 -> open, 拒绝 -> closed; 必须指定人独立决定 |
| POST `/meetings` | title, held_on, minutes, attendee_ids, 可选 material_ids | 持久化纪要及 1..100 个参与人, 状态 recorded. material_ids 为可选会前资料, 须为 0..50 个不重复的同项目真实文档版本 `id`; 引用不存在或跨项目或非 document 类型 404, 重复或超 50 或非法数组 400; 留空记为 `[]` |
| POST `/meetings/:rid/actions` | title, owner_id, due_date | 创建归属该会议的 open 行动项 |
| POST `/actions/:rid/task` | 可选 start_date, duration_days, wbs_code | 同事务创建真实 WBS 任务, 状态 converted, 保存 target_task_id; 重试不重复创建 |
| POST `/actions/:rid/complete` | result, evidence_ids, reviewer_id | 提交行动完成: 状态 open/rejected -> in_review, 记录 review_action action_closure, result, 绑定的不可变证据版本 evidence_ids, 指定独立 reviewer_id(不得为提交人且具质量审批权与项目访问), submitted_by 为当前操作人; 缺证据 409, 审核人为本人 409, 无权限 403, 缺字段 400 |
| POST `/actions/:rid/verify` | decision: approved/rejected, reason | 由指定 reviewer_id 独立核验: in_review -> closed(approved)或 rejected; 非指定核验人 403, 自行核验本人提交 409; 关闭后逾期计算不再触发 |
| POST `/changes` | title, reason, scope_impact, schedule_impact, cost_impact, quality_impact, resource_impact; 可选 schedule_impact_days, cost_impact_amount | 创建正式变更申请草稿, 各影响维度均须说明 |
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
| POST `/comm-plans/:rid/log` | 可选 on, 可选 note | 记录沟通计划(H02)的一次实际沟通: `on` 缺省取服务器当天, 服务端按该计划 `frequency` 的既定节奏(daily/weekly/biweekly/monthly/quarterly 分别顺延 1/7/14/30/90 天)自动顺延 `next_date`, 写入 `last_communicated_on`, `last_communication_note` 并追加一条 `communication_log`; 仅允许最新版本(陈旧版本 409), 非法日期 400, 计划不存在 404 |

所有日期均为有效 ISO 日期 `YYYY-MM-DD`. Gate 模板检查项是 `{code, title, required}`. Gate 检查结果是 `{code, passed, evidence_ids}`; 不能通过客户端改动模板的必需性. `evidence_ids` 是同项目真实文档版本 `id` 的不重复数组, 至多 50 条; 要求证据时至少 1 条. 不接受任意网址或自由文本作为已受控证据.

问题整改和重开分别以 `review_action: closure/reopen` 标识, `workflow_history` 保留此前关闭结论及每次重开决定. 风险复审使用 `review_action: risk_review`, 含 `review_previous_status`, `requested_outcome`, `submitted_by` 和 `reviewer_id`. 非关闭结论的 `next_review_date` 必须严格晚于服务端当天; 提交及批准时均验证, 过期的待审申请不能直接批准. 风险关闭仍须实际证据和独立批准. 风险关联问题在提交及批准复审时均须已关闭, 历史问题 ID 不丢失. 读模型增加 `last_reviewed_on`, `review_due_date`, `review_overdue`; 到期日为今天或之前且风险未关闭时显示逾期, 本轮不自动发送升级通知.

风险超阈值升级 (H08): `POST /risks` 在 `score = probability * impact` 达到阈值 16 时自动写入 `escalated: true` 与 `escalation_state: pending`, 并按分数给出 `escalation_level`(16..19 为 management, 20 及以上为 steering)与可读 `escalation_reason`; 未达阈值时 `escalated: false`. 升级状态随记录持久化, 读模型原样回显 `escalated`, `escalation_state`, `escalation_level`, `escalation_reason`, 确认后再回显 `escalation_decision`, `escalation_ack_by`, `escalation_ack_on`. 处于 pending 的升级会阻断该风险的 `mitigate`(返回 409), 必须由登记人之外的独立质量审批人调用 `escalate` 作出 approved(转为 acknowledged)或 rejected(转为 waived)后方可解除; `escalate` 走 `pms:quality:approve` 权限与项目读范围, 与既有独立批准命令一致采用只读写入范围, 因此只读审批人也能确认. 复评期间可对该风险重新评分: `POST /risks/:rid/review` 允许成对提交新的 `probability` 与 `impact`, 服务端以 `review_proposed_*` 键暂存为待批准提议而不立即改动 `score`; 独立审批人 `decision` 批准(且结论非关闭)后按新概率×影响重算 `score` 并重新判定同一套超阈值升级门控 (达阈值重新 `escalated`/`pending`, 未达阈值则清理 escalation 键), 拒绝或关闭则维持原评分并清理提议临时键. 评分与升级判定的纯函数抽取到 `risk-assessment` 命名空间, 供登记, 库实例化与复评重算共用, 避免命名空间循环引用. 本轮负责"新建超阈值升级 + 独立确认解除缓解门控 + 复评重新评分并重算门控"这一闭环; 诚实边界: 升级通知投递, 跨项目风险汇总升级仍待实现, MySQL 回归待补充.

风险应对策略 (H08 延伸): `POST /risks` 接受可选枚举字段 `response_strategy`, 取值为 PMI 四类应对策略 `avoid`(规避)/`transfer`(转移)/`mitigate`(减轻)/`accept`(接受); 服务端用 `risk-response-strategies` 集合经 `s/enum!` 校验, 非法取值返回 400, 未填则不写入该键 (与既有登记用例零回归). 该字段随风险记录 payload JSON 持久化, 读模型原样回显, 前端"登记项目风险"表单以下拉供选择, 风险台账"应对策略"列以 geekblue 标签回显中文策略名 (未设定显示灰字"未设定"). 这是复用"免迁移给治理 kind 加可选强类型字段"套路的一个枚举变体: 不新增治理记录类型, 不加数据库迁移, 不改变评分与升级门控逻辑 (从典型风险库 `from-library` 实例化的风险默认不带 `response_strategy`, 仍为 `nil`). 本轮负责"登记风险时可声明结构化应对策略并在台账可视"这一最小能力; 诚实边界: 应对策略目前仅为登记属性, 尚未与后续缓解动作或审批流做联动约束, 也未做按策略聚合的只读统计, MySQL 回归待补充.

需求验证方式 (C02 延伸): `POST /requirements` 接受可选枚举字段 `verification_method`, 取值为 ISO/IEC/IEEE 29148 四类验证方法 `test`(测试)/`inspection`(检验)/`demonstration`(演示)/`analysis`(分析); 服务端用 `requirement-verification-methods` 集合经 `s/enum!` 校验, 非法取值返回 400. 该字段随需求记录 payload JSON 持久化, 读模型原样回显, 前端"新增URS需求"表单以下拉供选择, 需求台账"验证方式"列以 geekblue 标签回显中文方法名 (未设定显示灰字"未设定"). 这是复用"免迁移给治理 kind 加可选强类型字段"套路的一个枚举变体: 不新增治理记录类型, 不加数据库迁移, 不改读模型 (字段随 payload 自动往返). 关键取舍: 可选枚举的写入门控用值存在性 `(seq vm)` 判定而非键存在性 `(contains? body ...)`, 使前端未选中的 `:select` 提交空串或缺键时都视为"未设定"零回归, 而显式非法值仍触发 400. 批量导入的 CSV 表头仍严格保持 `code,text,category,priority,owner_id` 五列 (导入行不含 `verification_method`, 记为 `nil`), 可选字段仅追加进创建/修订请求体白名单 `requirement-input-fields` 而不污染 `requirement-fields`. 修订可改 `verification_method`, 但每个版本是不可变记录, 原版本值不漂移. 本轮负责"需求可声明验证方式并在台账可视"这一最小能力; 诚实边界: 验证方式目前仅为登记属性, 尚未与追踪矩阵的"验证需求"关系或关闭证据做联动校验 (即声明了 test 不代表已挂验证证据), MySQL 回归待补充. 按验证方式聚合的只读统计见下条"需求验证方式覆盖度 (C02 延伸二)".

需求验证方式覆盖度 (C02 延伸二): GET `/governance` 读模型新增只派生字段 `verification_coverage`, 由 `governance.evidence/verification-coverage` 纯函数对需求记录聚合而来, **不落库、不投递、不改动任何不可变版本** (免迁移, 免新命令, 免新 kind). 口径: 以每个业务编码 `code` 的最新有效版本为统计单位 (复用 `store/latest` 的按 code 分组取最高 revision 不变量, 与 C04 文档归集"修订不重复计数"同一套), 再剔除最新版本处于受控作废 `discarded` 的编号 (沿用 H18c 归集剔除口径). 输出 `{total, declared, undeclared, coverage-pct, by-method:[{method, count}]}`, 其中 `total` 为计入的需求编号数, `declared` 为其中已声明四类验证方法之一者, `coverage-pct` 为 `declared/total` 四舍五入整数百分比 (`total` 为 0 时给 0), `by-method` 固定按 `test`/`inspection`/`demonstration`/`analysis` 四类各自计数 (未声明者不计入任何方法). 前端"URS与追踪"页签在需求台账之后新增"验证方式覆盖度"面板, 以蓝色标签显示计入需求数, 以绿/金/红标签显示已声明百分比 (100% 绿, 0% 红, 其余金), 有未设定项时追加橙色"未设定 N"标签, 并按四类方法以 geekblue/灰标签回显"方法 · 计数". 本轮负责"按验证方式对需求声明做只读覆盖度统计并界面可视"这一能力, 关闭此前 C01/C02 关于"未做聚合只读统计"的边界; 诚实边界: 覆盖度只反映"是否声明了验证方式", 不等于已配齐对应验收证据, 也不做按类别/优先级的更细切分, MySQL 回归待补充.

证据发布覆盖度 (C06 延伸): GET `/governance` 读模型新增只派生字段 `release_coverage`, 由 `governance.evidence/release-coverage` 纯函数对文档记录聚合而来, **不落库、不投递、不改动任何不可变版本** (免迁移, 免新命令, 免新 kind). 口径: 以每个业务编码 `code` 的最新有效版本为统计单位 (复用 `store/latest` 的按 code 分组取最高 revision 不变量, 与 C04 文档归集/C02 验证方式覆盖度"修订不重复计数"同一套), 再剔除最新版本处于受控作废 `discarded` 的编号 (沿用 H18c 归集剔除口径). 输出 `{total, approved, in-review, registered, rejected, released-pct}`: `total` 为计入的文档编号数, 其余按发布生命周期四态各自计数(`approved` 已发布, `in_review` 待审, `registered` 未提交, `rejected` 已驳回), `released-pct` 为 `approved/total` 四舍五入整数百分比 (`total` 为 0 时给 0). 该字段与 C04 文档归集互补: 归集看"证据按阶段/结构节点/密级分布在哪", 本项看"证据发布审批推进到哪一步". 前端"证据版本"页签在文档归集视图之后新增"证据发布覆盖度"面板, 以蓝色标签"覆盖文档 N"显示计入数(命名区别于归集面板的"最新版本证据"), 以绿/金/红标签显示已发布率 (100% 绿, 0% 红, 其余金), 并以绿"已发布", processing"待审", default"未提交", 红"已驳回"标签回显各态计数(计数为 0 的状态标签不渲染). 本轮负责"按发布生命周期对证据做只读覆盖度统计并界面可视"这一能力; 诚实边界: 覆盖度只反映各文档最新版本处于哪个发布状态, 不等于文档内容质量或签章合规, 亦不做按阶段/密级切分的发布进度漏斗, MySQL 回归待补充.

问题阻断级自动升级 (C09d): `POST /issues` 在 `severity = blocker` 时登记即自动升级, 写入 `escalated: true` 与 `escalation_state: pending`, 并按登记时是否已逾期给出 `escalation_level`(到期日早于或等于服务端当天为 steering 管理层, 否则 management 经理层)与可读 `escalation_reason`; 非阻断级(major/minor)不写任何升级键, 与既有问题用例兼容. 升级状态随记录持久化, 读模型原样回显 `escalated`, `escalation_state`, `escalation_level`, `escalation_reason`, 确认后再回显 `escalation_decision`, `escalation_ack_by`, `escalation_ack_on`. 处于 pending 的升级会阻断该问题的 `resolve`(返回 409), 必须由登记人之外的独立质量审批人调用 `escalate` 作出 approved(转为 acknowledged)或 rejected(转为 waived)后方可解除; `escalate` 走 `pms:quality:approve` 权限与项目读范围, 与既有独立批准命令一致采用只读写入范围, 因此只读审批人也能确认, 登记人自确认返回 403. 诚实边界: 本轮仅对"阻断级登记即升级"和"独立确认解除提交解决门控"这一条最小闭环负责; 由风险 `materialize` 生成的问题暂不自动升级(避免与既有 materialize 用例回归), 逾期后对已登记问题的追溯升级, 升级通知投递与跨项目汇总仍待实现.

典型风险库 (C10): 服务端内置一份精选风险目录 `risk-library` (含进度/供应/技术/成本/人员等常见条目, 每条固定 category, 标准 probability 与 impact, 应对措施与适用阶段), 通过工作台只读字段 `risk_library` 暴露给前端下拉. `POST /risks/from-library` 依 `template_key` 选出一条, 按库中标准概率×影响自动评分并套用措施与阶段, 落库为一条普通 `risk` (kind 仍为 `risk`, 不新增治理记录类型, 因而无迁移), 并额外写入 `source_key` 与 `source_category` 以保留来源可追溯. 库实例化与手工登记共用同一评分与 H08 超阈值自动升级门控 (score>=16 即自动 `escalated`/`pending`), 因此从库选用的重大风险同样需独立质量审批人确认后方可缓解. 本轮负责的是"内置典型风险分类可复用并一键转为项目风险"这一条闭环; 诚实边界: 该库是随代码发布的精选目录, 尚非用户可自行编写并持久化的模板 CRUD, 也没有自动扫描把库条目推送/提醒到项目的机制, MySQL 回归待补充.

## 真实文档与 CSV

`content` 是真实提交文本, 包含首尾空格和换行的原始 UTF-8 字节; 非空且至多 1MiB. filename 不得含路径分隔符或换行. `registered` 表示已通过字段和摘要校验登记的不可变版本, 可用于验收引用. 文档发布审批: `POST /documents/:rid/submit` 使 `registered`/`rejected` -> `in_review` 并指定独立发布审核人, `POST /documents/:rid/decision` 由该审核人 `approved` -> `approved` (正式签发发布, 记 `released_by` 与 `decision_reason`) 或 `rejected` -> `rejected`. 提交与决定均走 `latest!`, 只能在最新版本上推进; 新修订回到 `registered`, 已批准的旧版本保持 `approved` 不漂移, 也不会替换旧 Gate, 问题或验收所引用的版本. 独立审批复用 `reviewer!` (须具备 `pms:quality:approve` 且非提交人) 与 `decision-actor!` (职责分离), 管理员不能绕过. 本轮不引入正式电子签章或外部文书模板.

文档登记与修订接受可选归集字段: `classification` 密级为枚举 `public|internal|confidential`, 缺省记为 `internal`, 非法取值返回 400; `stage` 所属阶段与 `structure_node` 结构节点为至多 100 字符的可选文本, 留空记为空串. 这些字段随不可变版本存入 payload 并进入读模型, 仅用于项目内按阶段/结构/密级归集与追踪, 不替代项目授权, 本轮不据密级过滤下载或访问. 读模型 `document_collection` 按每个编号的最新版本且状态非 `discarded` 聚合这些字段供工作台"文档归集视图"分层展示 (已被受控作废的最新版本编号不计入, 单独以 `discarded-count` 呈现), 前端"文档与版本证据"另提供按密级的客户端过滤(仅过滤当前展示行, 不改变服务端授权与批量下载范围).

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
- 沟通计划维护目标, 渠道, 频率, 1..50 个不重复的同项目有效干系人受众和下次沟通日期, 状态 `active`; 受控修订形成可审计的节奏调整记录. `POST /comm-plans/:rid/meeting` 仅允许最新版本, 从受众干系人已绑定的项目成员去重生成参会人 (无有效成员返回 409), 落库一条 `recorded` 会议并把 `last_meeting_id` 回写到计划, 形成沟通计划到会议的闭环. `held_on` 缺省取计划 `next_date`. `POST /comm-plans/:rid/log` 记录一次实际沟通: `on` 缺省取服务器当天, 服务端按该计划 `frequency` 的既定节奏 (daily/weekly/biweekly/monthly/quarterly 分别顺延 1/7/14/30/90 天) 自动顺延 `next_date`, 写入 `last_communicated_on`, `last_communication_note` 并逐次追加 `communication_log`, 使"沟通节奏可执行并有调整记录"成为本地受控事实; 读模型据此输出 `comm_overdue` 与 `comm_days_until` 供台账到期预警.

工作台"干系人与沟通"页签已提供干系人/RACI/沟通计划的前端视图与浏览器端到端验证; 沟通节奏的执行由"标记已沟通"顺延下次日期, 生成会议与逐次沟通留痕这三类本地受控事实体现. 仍待补齐: 外部通知或消息渠道自动推送, 以及按节奏定时派发提醒 (本轮不声称自动提醒已交付).

## 受控作废 (H18)

对 `requirement` (URS需求), `document` (证据文档) 与 `stakeholder` (干系人) 三类记录提供受控作废命令, 把"这条记录不再有效"表达为一次可审计的状态迁移, 而不是物理删除. 命令走 `pms:project:edit` 写权限与项目作用域, 无编辑权用户 403, 携带未知字段 400:

- `POST /requirements/:rid/discard`
- `POST /documents/:rid/discard`
- `POST /stakeholders/:rid/discard`

请求体仅接受 `reason` (可选, 至多 500 字符) 与 `version`. 三层门控按序执行: `latest!` 只允许最新版本 (陈旧版本 409), `status!` 只允许处于可作废状态集合的记录 (requirement 须 `registered`, document 须 `registered`/`rejected`, stakeholder 须 `active`, 否则 409; 例如提交进入发布评审 `in_review` 的文档不能直接作废), 再做引用守卫. 服务端在事务内收集该记录被其它对象引用的证据 (requirement 被追踪指向, document 被追踪/会议会前资料/问题与风险证据/行动证据引用, stakeholder 被 RACI 指派或列入沟通受众), 命中任一引用即 409 并在消息里列出前若干条引用来源, 拒绝作废. 通过守卫后调用 `change!` 把状态写为 `discarded`, 同时记录 `discard_reason`, `discarded_by`, `discarded_on` 并追加一条 `workflow_history` 审计项 (含作废前状态), 记录内容, 编号与既有版本链全部保留可追溯.

作废是终态: 已作废记录再次作废命中状态守卫返回 409. 读模型原样回显 `status: "discarded"` 与作废字段, 工作台据此在状态列显示"已作废"并隐藏作废入口; 已作废干系人不再满足"有效干系人"前置, 后续 RACI 指派或沟通受众引用它按既有 `active-stakeholder!` 状态守卫返回 409. `document_collection` 归集视图按业务编码取最新版本聚合, 且只计入未作废 (状态非 `discarded`) 的最新版本: 若某编号的最新版本已被受控作废, 该编号不再计入 `total` 及各分桶 (`by-stage`/`by-structure-node`/`by-classification`), 而是单独以 `discarded-count` 透明呈现, 前端在"文档归集视图"以红色"已作废 N 未计入"标签提示; 恢复后重新计入. 归集仍是只读派生, 不改变不可变版本或作废状态本身.

受控撤销作废 (恢复): 与作废对称, 每类记录另有 `POST /requirements/:rid/restore`, `POST /documents/:rid/restore`, `POST /stakeholders/:rid/restore` 三条命令, 同样走 `pms:project:edit` 权限与项目作用域, 请求体仅接受 `reason` 与 `version`. 门控为 `latest!` (陈旧 409) + `status!` 要求当前恰为 `discarded` (非作废记录恢复返回 409). 服务端从该记录 `workflow_history` 里最近一条 `discarded` 审计项取回作废前状态, 用 `change!` 把状态回退到该前态 (requirement/document 回 `registered`, 被拒文档回 `rejected`, 干系人回 `active`), 并记 `restore_reason`/`restored_by`/`restored_on` + 追加一条 `workflow_history` 的 `restored` 审计项 (含 `restored_to`), 不重放任何业务副作用. 若历史里找不到可解析的作废前状态则返回 409 拒绝恢复. 恢复后记录重新满足各自状态前置 (如已恢复干系人可再被 RACI 指派), 界面"恢复"入口消失, "作废"入口重新出现. 恢复免迁移 (`discarded` 与目标状态均已在 CHECK 内).

级联影响预览 (作废前只读预检): 为帮助用户在真正作废前看清受影响范围, 三类记录各提供一条只读预览命令, 走 `pms:project:query` 读权限与项目作用域 (无读取权 403, 未知记录 404), 不写入不改变任何状态:

- `GET /requirements/:rid/discard-preview`
- `GET /documents/:rid/discard-preview`
- `GET /stakeholders/:rid/discard-preview`

返回 `{kind, record_id, code, revision, status, latest?, status_discardable?, references, discardable?}`: `latest?` 标记是否最新版本, `status_discardable?` 标记当前状态是否落在可作废集合, `references` 复用与作废守卫同一套引用收集逻辑列出仍指向该记录的对象 (如"需求追踪 <code>", "会议 <标题>", "问题 <标题>", "RACI <活动>", "沟通计划 <code>"), `discardable?` 仅在同时满足最新版本, 状态可作废且无任何引用时为真. 工作台需求/证据文档/干系人行内提供"级联影响"按钮打开只读弹窗, 以标签呈现"可安全作废"或"不可作废"并列出引用清单, 与真正作废命令的守卫口径一致 (预览为可安全作废的记录, 实际作废仍可能在并发下命中守卫). 预览免迁移, 纯读派生.

诚实边界: `discarded` 是 `pms_gov_record` 状态 CHECK 约束新增的取值, 需要一次表重建迁移 (`202609220011-gov-status-discard`, SQLite 与 MySQL 各一份, 因 SQLite 不能 ALTER CHECK 故按 PRAGMA foreign_keys 关闭 -> 建新表 -> 迁移数据 -> 换名 -> 重建索引 -> 恢复外键的整表重建套路). 本轮已交付"作废即软删除+引用守卫+审计留痕", "受控恢复回作废前状态+审计留痕", "级联影响只读预览"与"已作废文档从归集口径剔除并单独计数"这一组本地闭环; 仍待实现的是正式历史按保留策略归档, 以及已作废证据对历史 Gate/验收快照的显式标注, MySQL 迁移回归亦待补充.

## 生命周期调用约定

`governance/execution-ready! [q project]` 要求当前最新章程 approved, 至少配置一个 required execution 模板, 且这些模板对应的所有实例均 approved 或 waived. 没有实例不算通过. 前段 Gate 编号未被假定为已确认业务规则, 使用配置的模板名称和阶段.

`governance/closure-ready! [q project]` 要求无未 closed 的 blocker 问题, 且 required closure Gate 全部签核. 计划基线, 费用结算和正式收尾清单由各自模块再验证; 单独 Gate 通过不代表整个项目可以关闭.

`governance/evidence-version! [q project id]` 验证同项目实际已登记文档版本, 返回含正文摘要的对象. `governance/approved-change! [q project id]` 验证最新正式变更已独立批准. 执行期间发布新计划基线必须引用已批准的 `change_id`, 仍需显式编辑计划和独立基线审批, 不会自动应用影响说明中的任务或费用变化. `blockers` 供前端说明生命周期前置缺口.

会议行动转任务复用 planning/create-task-record!, 因而同样检查当前计划是否可编辑, 成员责任人和项目计划修订. 返回 `{target_task_id, action}`. 风险转问题与行动转任务在新项目版本下重试返回同一目标, 仍产生一条本次命令的审计记录; 陈旧版本直接 409.

行动读模型对每条 action 计算派生字段 `action_overdue`: 当存在 `due_date` 且状态不属于 closed/converted 且到期日不晚于服务器当前日期时为 true; 完成提交进入 in_review 仍保持逾期, 独立核验关闭后转为 false. 该字段仅在读取时计算, 不写入存储, 也不构成主动提醒.

到期倒计时读模型 (免迁移的只读派生列): 问题与会议行动两条以 `due_date` 为到期口径的台账在读取时额外计算剩余天数与临期标记, 供台账"到期倒计时"列把原有的二元"已逾期"细化为可提前关注的剩余天数. `issue_due_in_days` / `action_due_in_days` 为到期日相对服务器当天的剩余天数 (负值表示已逾期天数), 仅在记录未关闭 (行动为未 closed 且未 converted) 时给出, 否则为 `nil`; `issue_due_soon` / `action_due_soon` 在剩余 1 到 `due-soon-days` (当前 3) 天时为 true, 逾期当天及以前不算临期. 该计算与既有 `issue_overdue` / `action_overdue` 同源同口径 (服务器当天, 排除关闭), 只读取派生, 不写入存储, 不构成提醒投递. 键名不带尾随问号以稳定 JSON 序列化.

风险台账复用同一"到期倒计时"列口径, 但到期基准是 `review_due_date` (下次复审日): 未复审过的风险回退到其 `due_date`, 经独立批准设置 `next_review_date` 后改为该复审日. `risk-read-model` 额外计算 `review_due_in_days` (相对服务器当天的剩余复审天数, 负值即逾期) 与 `review_due_soon` (剩余 1 到 `review-due-soon-days` (当前 3) 天时为 true), 均在状态为 closed 时给出 `nil`/false, 与既有 `review_overdue` 同源同口径, 只读派生不落库不投递.

风险与问题双向来源关联读模型 (免迁移的只读可见性): `POST /risks/:rid/materialize` 在生成问题时已把双向关联 ID 持久化在记录 payload 里 (问题侧 `source_risk_id`, 风险侧 `issue_id`), 二者在 `(dissoc % :content)` 后仍随 `risks`/`issues` 数组回显. 为让 C10"风险实现转问题保留关联"在界面直接可读, workspace 读取层在结果聚合后追加一次 `enrich-risk-issue-links`: 问题的 `source_risk_id` 命中风险时补 `issue_source_risk_id` 与 `issue_source_risk_title` (来源风险标题), 风险的 `issue_id` 命中问题时补 `risk_issue_id` 与 `risk_issue_title` (转出问题标题); 手工登记的问题 (无 `source_risk_id`) 与未转问题的风险 (无 `issue_id`) 不写这些键, 对端记录缺失时标题回落 `nil`. 前端问题台账"来源风险"列以 geekblue 标签回显来源风险标题 (无来源显示"手工登记"), 风险台账"转出问题"列以 cyan 标签回显转出问题标题 (未转出显示灰字). 该标注仅在读取时按当前数据互相补全标题, 不写入存储, 不新增迁移, 不改变 `materialize` 的幂等语义, 也不构成提醒投递. 键名不带尾随问号以稳定 JSON 序列化.

## 实现边界

记录表的 kind 和状态有数据库约束, 服务层按业务类型逐字段校验, 再由固定命令推进状态. 未提供任意 payload CRUD, 直接写状态或删除证据入口. 章程/变更审批冻结提交版本; URS/文档版本不可覆盖; Gate 保存模板及证据版本快照. 所有引用由项目作用域查询验证.

暂未提供二进制上传, 外部文档仓库接入, 源系统推送, 自动变更应用, 电子签名认证或法律层面的签章能力. 本地持久化, 审批和证据摘要用于本项目工程验收闭环, 不声称任何正式合规认证.
