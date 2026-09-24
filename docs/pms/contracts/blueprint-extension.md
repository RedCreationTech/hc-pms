# 蓝图对齐扩展 HTTP 合同 (增量 1-3, 2026-09-24)

状态: 已实现并通过本地 SQLite 全量 PMS 回归 123 tests / 1204 assertions (新增 CT 6 / FW 8 / PF 7 例) 与隔离 `:3100` 后端 Linux Chromium 浏览器用例 5 passed. 本地无 MySQL 实例, 迁移双库文件已同步但 MySQL 回归未执行. 不包含真实外部系统写入 (CRM/OA/ERP/PLM/MES/SRM/销服/BI 保持 `not_configured`), 不冒充已同步. 所有路由挂在现有 JWT 认证中间件内, 项目内命令沿用 `version` 乐观锁与 `{code, msg, data: {result, project_version}}` 信封; 平台级配置命令不带 `version`, `data` 直接是记录.

迁移 `202609240001-blueprint-extension` (SQLite/MySQL 同步): 新表 `pms_config_record(config_id, kind, code, revision, status, created_by, payload, created_at, updated_at)`, `kind IN (project-template, coding-rule, quarterly-target, rd-pool, period-lock)`, `status IN (draft, published, retired, frozen, locked)`; `pms_gov_record.kind` 新增 `template-instance|dq|node-pause`; `pms_delivery_record.kind` 新增 `survey|handover|site-task`; `pms_plan_task` 新增 `node_id, stage_code`; `pms_time_entry` 新增 `corrects_entry_id, correction_reason`; 菜单 5003-5007 与权限 `pms:config:edit`(5042), `pms:document:confidential`(5043), `pms:target:edit`(5044).

## 平台级配置 `/api/pms/config/:kind`

读取要求 `pms:config:list|pms:project:query|pms:finance:query|pms:dashboard:query` 之一; 维护权限按类型: `project-template`/`coding-rule` 需 `pms:config:edit`, `quarterly-target` 需 `pms:target:edit`, `rd-pool`/`period-lock` 需 `pms:finance:approve`. 记录以 `(kind, code, revision)` 唯一, `id` 标识版本, `history` 追加 `{action, actor_id, actor_name, at, reason}`.

| 路径 | 字段 | 业务结果 |
| --- | --- | --- |
| GET `/config/:kind` | - | `{rows, catalog, gate_types, current_user_id}`; `catalog` 为内置目录 (项目模板/编码规则), `gate_types` 为关口目录 |
| POST `/config/:kind` | 按类型白名单 (见下) | 创建首个版本 (draft; period-lock 直接 locked); 同编码存在未退役版本 409, 全部已退役则以新修订号重建 |
| POST `/config/:kind/import` | code | 从内置目录导入为草稿 (仅 project-template / coding-rule) |
| POST `/config/:kind/:id/revisions` | 同创建 (code 不可变) | 从最新版本派生草稿, 非最新 409, 草稿再修订 409, 改码 400 |
| POST `/config/:kind/:id/publish` | reason | draft -> published (rd-pool -> frozen); 同编码此前生效版本自动 retired |
| POST `/config/:kind/:id/retire` | reason (必填) | draft/published/frozen/locked -> retired (封期解锁) |
| GET `/config/rd-pool/:id/preview` | - | 只读分摊预览 `{pool, total_minutes, rows[{project_id, minutes, hours, amount}], conserved}` |
| POST `/config/rd-pool/:id/allocate` | reviewer_id (必填, 不能是执行人), name | frozen 且未分摊的费用池按期间内各项目已批准工时最大余数法分摊, 在每个受益项目生成 `actual` 草稿版本与 `labor` 条目 (`source_ref = rd-pool:<id>`), 结果写入 `allocation`, 重复 409 |

类型字段: `project-template` = code, title, description, project_types (equipment/line/service/new_product/new_technology/special_rd/dept_affairs 子集), stages[{code, name, weight}] (权重合计 100), structure[{node_type: sub, suffix, name, machines[{suffix, name}]}], gate_templates[{code, title, gate_type, stage, required, blocks, checks[{code, title, required, require_released}]}], team_roles[], document_categories[], delivery{required_stages, required_test_types}, closure_items[{kind, title, required}]. `coding-rule` = code, object_type (project/sub/machine/document/requirement/task), pattern (占位符 `{YYYY} {YY} {MM} {TYPE} {PROJECT} {SEQ:n}`, 必含 SEQ), enforced, description, version_rule, collection_rule. `quarterly-target` = year, quarter, metric (revenue/gross_margin/closed_projects/on_time_rate), target_value, currency, basis (code 派生为 `<year>Q<quarter>-<metric>`). `rd-pool` = period (YYYY-MM), amount, currency, description (code `POOL-<period>`). `period-lock` = period, reason (code 为期间).

`GET /api/pms/coding-rules/next?object_type=&project_id=&type=` 返回 `{object_type, rule, code}`: 按生效规则渲染下一编号 (流水 = 现有数量 + 1); 生效且 `enforced` 的规则在创建项目/子项目/单机时强制校验格式 (不符 400).

## 跨项目只读 `/api/pms/todo` `/api/pms/search` `/api/pms/portfolio` `/api/pms/targets/board`

- `GET /todo` (需 `pms:project:list|query`): `{reviews, escalations, owned, summary{reviews, escalations, owned, overdue, due_soon}, delivery_status: local_only}`. `reviews` = 治理 (charter/change/document/gate/risk/issue/action/dq) 与交付 (material/bom/assembly/test/shipment/service/survey) 中 `status=in_review` 且 `reviewer_id` 为本人且非本人提交的记录, 含待本人验证签收的发运单与待本人审核的工时; `escalations` = 有 `pms:quality:approve` 时待确认的风险/问题升级 (非本人登记); `owned` = 本人负责的未关闭问题/行动/风险 (以复审到期日或到期日倒计时), 待完成交底 (截止日) 与待开始现场任务 (计划开始). 每项含 `{project_id, project_no, project_name, kind, label, tab, id, code, title, status, due_date, days, overdue, due_soon}`. 只读派生, 不投递外部消息.
- `GET /search?q=&classification=` (需 `pms:project:list|query`, `q` 必填 1-200): 先经 SQL 按授权范围与关键字预筛 (`pms/search-gov`/`search-delivery`/`search-tasks`, 各至多 500 行), 再按可读字段精确匹配; 机密文档仅对具备 `pms:document:confidential` (或管理员) 可见, `classification` 仅过滤文档. 返回 `{q, count, truncated, results[{project_id, project_no, project_name, kind, label, tab, id, code, title, revision, status, classification}], confidential_visible}`, 至多 200 条, 不返回正文.
- `GET /portfolio` (需 `pms:dashboard:query|pms:project:list`): `{projects[card], summary{total, active, overdue, blocker_issues, escalations, average_percent}, generated_at}`; card = 项目基本信息 + `overall_percent, leaf_count, stages[{code, name, weight, percent}], nodes[结构节点进度], node_count, kit_percent, bom_count, shortage_count, tests_required, tests_approved, tests_failed, shipments_received, open_issues, blocker_issues, open_risks, escalations, gates_passed, gates_total, finance (仅 `pms:finance:query`: estimate/budget/actual/settlement/revenue/margin/budget_variance), data_time`.
- `GET /targets/board` (需 `pms:finance:query|pms:dashboard:query`): `{rows[目标版本 + actual, achievement_pct, closed_projects[], source], finance_visible, metrics}`; 实际值 = 归档季度内 (以 `pms_lifecycle_state.archived_at` 为准) 关闭项目的已批准决算收入/毛利 (无财务权限时仅 counts_only), 结项数, 准时结项率.

## 项目治理新增命令 (前缀 `/api/pms/projects/:id/governance`)

| 路径 | 字段 | 业务结果 |
| --- | --- | --- |
| POST `/template-instances` | template_id, reason | 项目 draft/initiated/planning 且未实例化时应用已发布模板: 建 sub/machine 节点 (编号 = 项目编号-后缀, 已存在复用), 登记 Gate 模板 (同码跳过), 建阶段容器 (summary 任务, `stage_code`) 与子项目/单机计划容器 (`node_id`), 缺交付配置时写入模板交付要求 (`source=project_template`), 生成收尾清单; 记录 `template-instance` (含阶段权重快照). 不适用类别/已实例化/执行后/未发布 409 |
| POST `/gate-templates` | code, title, stage (execution/closure/design/manufacturing/delivery/site), required, gate_type (目录类型), blocks (assembly.start/test.SIT/test.FAT/test.SAT/shipment.dispatch 子集), checks[{code, title, required, require_released}] | 类型化关口模板; 同码 409 |
| POST `/gate-templates/from-catalog` | gate_type, required | 从关口目录 (requirement-confirm/host-summary/attachment-summary/kitting/assembly-test-handover/fat-confirm/handover/sat-confirm/generic) 一键建立模板, 已存在同类型 409 |
| POST `/dqs` | code, title, owner_id, checklist[{code, title, required}], deliverable_ids, task_id | DQ 关键任务 draft |
| POST `/dqs/:rid/checks` | results[{code, passed, note}], deliverable_ids | 逐项登记; 全部必需项通过 -> ready 否则 draft |
| POST `/dqs/:rid/submit` | reviewer_id | ready/rejected -> in_review (必需项未通过或无交付件 409) |
| POST `/dqs/:rid/decision` | decision, reason | 指定签认人 approved/rejected |
| POST `/node-pauses` | node_id (非 main), reason | 局部暂停 active; 同节点重复 409 |
| POST `/node-pauses/:rid/resume` | impact_note | active -> closed, 记录恢复影响 |
| POST `/issues/:rid/escalate-overdue` | reason | 已逾期且未升级的未关闭问题追溯升级 (escalated, pending, management, `escalation_source=overdue_retroactive`); 未逾期/已升级 409 |
| POST `/meetings` | 原字段 + meeting_type (regular/kickoff/review/fat-kickoff/fat-summary), baseline_id | 启动会须绑定会前资料且引用主计划基线 (否则 409), 基线不存在 404; 记录 `baseline_revision/baseline_status` |
| POST `/traces` | 原字段 + phase (design/requirement-confirm/SIT/FAT/SAT), deviation_level (none/minor/major/blocker), deviation_note | 登记偏差 (非 none) 必须填说明 (400) |

读模型 `GET ""` 新增: `template_instances`, `dqs` (含 `dq_stale, dq_stale_count, dq_passed, dq_total`), `node_pauses`, `gate_progress[{template_id, code, title, gate_type, stage, blocks, required, instance_count, status, passed_checks, total_checks, passed}]`, `gate_catalog`, `document_tree[{stage, count, nodes[{structure_node, count, classifications[{classification, count, documents[]}]}]}]`, `meetings[*].baseline_stale/baseline_current_status`, `issues[*].issue_escalation_suggested`, `traceability[*].phases/worst_deviation`, `trace_summary` 新增 `coverage-pct, design-pct, verification-pct, by-phase, deviations, blocking-deviations`. 关口阻断: 声明了 `blocks` 的模板未有 approved/waived 实例时, 装配开工 (`assembly.start`), 试验登记 (`test.<类型>`) 与发运登记 (`shipment.dispatch`) 返回 409; 检查项 `require_released` 要求证据文档状态为 approved (已发布). 文档正文 `/documents/:rid/content`, `/download`, `/batch-download` 对 `classification=confidential` 逐次逐文件要求 `pms:document:confidential` (403).

计划: `POST/PUT /tasks` 新增可选 `node_id` (须为本项目结构节点) 与 `stage_code`; `GET /planning` 新增 `nodes, stages, progress_rollup{source, overall_percent, leaf_count, stages[{code, name, weight, task_count, done_count, percent}], unassigned_task_count, unassigned_percent, nodes[{node_id, node_type, node_code, name, task_count, done_count, percent}]}, node_pauses, paused_node_ids`, 每个任务 `node_paused`; 暂停节点及后代任务的 `feedback` 返回 409.

## 项目交付新增命令 (前缀 `/api/pms/projects/:id/delivery`)

| 路径 | 字段 | 业务结果 |
| --- | --- | --- |
| POST `/configuration` | 原字段 + required_survey_visits (0-10), pre_ship_conditions (warehouse_in/payment 子集), handover_deadline_days (0-30), site_lag_days (0-30), handover_required (bool) | 执行前配置; 缺省 0 / [] / 2 / 2 / false |
| POST `/material-requests` | request_type 新增 packaging (须 packaging_spec), 可选 node_id | 包材申请; 结果按 `task_links` 回写来源任务 |
| POST `/surveys` | code, title, visit_no (1-20, 同次序未驳回则唯一), owner_id, planned_date, deliverable, task_id | 工勘 draft |
| POST `/surveys/:rid/submit` | reviewer_id, evidence_ids, actual_date (不可未来), findings | -> in_review |
| POST `/surveys/:rid/decision` | decision, reason | approved/rejected; `required_survey_visits` 未满足时收尾阻塞 |
| POST `/assemblies/:rid/steps` | step (on_island/assembling/unit_inspection/wiring_inspection/off_island/handover), actual_date, note, evidence_ids | 顺序单调, 不可重复/倒退/未来/早于上一步 (409/400) |
| POST `/shipments/:rid/conditions` | warehouse_in_confirmed, warehouse_note, payment_confirmed, payment_note, evidence_ids | 本地事实 `preconditions` (source local_fact); 配置要求的条件未确认时提交/放行/发运 409 |
| POST `/shipments/:rid/dispatch` | 原字段 | 发运后自动生成 `handover` (deadline = shipped_on + handover_deadline_days 自然日, `crm_sync_status=not_configured`) |
| POST `/handovers/:rid/complete` | document_ids (>=1), checklist_note, completed_on | open -> closed, `completed_late` 如实标记; 生成 4 个 `site-task` (positioning/installation/commissioning/sat, planned_start = completed_on + site_lag_days, `erp_dispatch_status=not_configured`) |
| POST `/site-tasks/:rid/start` | actual_start, note | draft -> in_progress; 前序未 closed 409 |
| POST `/site-tasks/:rid/complete` | actual_end, evidence_ids, result | in_progress -> closed; 早于开始 400 |

读模型 `GET ""` 新增: `surveys, handovers (handover_days_left, handover_overdue), site_tasks (site_delayed, site_days_to_start), assemblies[*].steps/step_count/step_total/current_step/next_step, shipments[*].preship_checklist[{code, label, required, satisfied, source}], kitting_rollup{bom_count, required_lines, complete_lines, kit_percent, nodes[...], shortages[...], unassigned_bom_count}, task_links{task_id: [...]}, assembly_steps`; 收尾 `blockers` 新增工勘次数不足与 (配置 `handover_required` 时) 交底未完成.

## 财务新增 (前缀 `/api/pms/projects/:id`)

- `POST /time-entries/:entry_id/correct` (本人, `pms:project:edit`): hours, note, reason, reviewer_id; 已批准原单 -> `corrected` 并释放同日容量, 生成 `corrects_entry_id` 关联的更正单 (submitted, 重新预留容量); 更正单被驳回时原单恢复 approved 并重新预留; 已更正原单不参与 `approved-times` 分摊. 生效封期 (`period-lock` locked) 内的工作日期提交与更正均 409.
- `GET /finance` 新增 `four_count{comparable, currency, versions{estimate|budget|actual|settlement}, rows[{category, estimate, budget, actual, settlement}], totals, variances{budget_vs_estimate, actual_vs_budget, settlement_vs_actual, settlement_vs_budget}}`: 各口径取最新已批准版本按分类对比, 币种不一致时 `comparable=false`.
