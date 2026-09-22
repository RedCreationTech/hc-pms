# 数据库设计 V1

本文件区分首批物理结构与后续目标模型. 首批可执行 DDL 以 `resources/migrations-sqlite/` 中202609220001业务表, 202609220002菜单和202609220003事件版本三个迁移以及对应 MySQL 文件为准, 三者必须完整运行. 后续表尚未执行迁移, 必须随所属能力及测试一起引入.

## 1. 共通规则

- 项目内对象必须携带 `project_id`, 引用节点时使用 `(project_id, node_id)` 复合约束, 防止跨项目关联. 不信任客户端传入的对象归属.
- 新业务 ID 使用应用生成的 UUID 字符串, 避免依赖跨库自增取值. 已有用户与部门继续使用模板 BIGINT ID.
- 业务日期为 ISO `YYYY-MM-DD`, 表示组织本地日历日期, 不做 UTC 偏移. 记录时间由数据库生成, 运行环境应统一时区; 外部接口的事件时间用 UTC ISO-8601.
- 项目状态和值域在服务层严格验证. `version` 从 1 开始, 每次聚合变更递增; 并发提交旧版本返回 409, 客户端重新获取详情.
- 金额后续使用 `DECIMAL(20,2)` 或整数最小货币单位, 禁止浮点计算. 不同币种不直接相加, 汇率带来源和生效日.
- 工时 `DECIMAL(12,2)`, 进度用 0..10000 整数基点或固定精度. 所有比率明示分子/分母和数据截止时间.
- 已批准版本和审计只追加, 业务取消不级联删除历史. 个人/部门停用不抹去历史署名.

## 2. 首批表

### pms_project

| 字段 | 类型 | 规则 |
|---|---|---|
| project_id | VARCHAR(36) PK | UUID |
| project_no | VARCHAR(64) UNIQUE NOT NULL | 去首尾空白, 全库唯一, 对外业务编号 |
| name | VARCHAR(200) NOT NULL | 项目名称 |
| customer | VARCHAR(200) | 客户名称快照, 后续关联 CRM 外部键 |
| contract_no | VARCHAR(100) | 合同编号引用 |
| project_type | VARCHAR(20) | equipment / line / service |
| manager_id | BIGINT NOT NULL | 有效系统用户 |
| dept_id | BIGINT NOT NULL | 有效归属部门 |
| start_date / end_date | VARCHAR(10) | ISO 日期, 起始不晚于结束 |
| status | VARCHAR(20) | draft / initiated / planning / cancelled 为当前可达状态 |
| version | INTEGER NOT NULL | 乐观锁 |
| created_by | BIGINT NOT NULL | 可信身份中的创建者 |
| created_at / updated_at | TIMESTAMP | 数据库记录时间 |

索引: 项目编号唯一索引, 经理索引, 状态索引. 项目经理与创建者的可见性, 以及成员关系, 共同组成首批项目授权范围. 列表和统计必须使用相同范围.

### pms_node

`node_id` PK, `project_id`, `parent_id` nullable, `node_type`, `node_code`, `name`, `created_at`.

约束: `UNIQUE(project_id, node_code)`, `UNIQUE(project_id,node_id)`, 项目外键, `(project_id,parent_id)` 指向同表 `(project_id,node_id)`. 服务规定唯一根为 main, main 下只允许 sub, sub 下只允许 machine, machine 无子节点. 根随项目创建事务生成, API 不接受第二个 main. 新增树节点也更新项目聚合版本和事件. 节点迁移/删除留到有计划引用后的专门命令, 首批不提供任意重挂树功能.

### pms_member

复合主键 `(project_id,user_id)`, `role` 为 manager/editor/viewer, `created_at`. `user_id` 索引支持项目可见性查询. 项目负责人及创建者与显式成员关系共同决定可见性. 编辑权限还须与用户全局功能权限相交, 成员关系不能越过功能权限.

### pms_event

`event_id` PK, `project_id` FK, `aggregate_version` INTEGER, `event_type`, `description`, `actor_id`, `actor_name`, `from_status`, `to_status`, `payload` JSON文本, `created_at`.

索引 `(project_id,created_at)`, 唯一索引 `(project_id,aggregate_version)`. 003迁移增加聚合版本, 事件按版本倒序展示, 避免同秒事件按随机UUID乱序. 项目建立/修改/状态改变/成员和结构变更在业务事务内追加事件. `payload` 保存必要的变更信息, 不保存口令和认证令牌. 当前记录为业务审计, 后续 outbox 另设表, 不把它直接当可靠消息队列.

## 3. 后续逻辑模型

以下字段省略通用 PK, project_id, version, created_at, created_by; 实际迁移不得省略隔离与唯一约束.

| 聚合 / 表 | 关键字段 | 关系与不可变量 |
|---|---|---|
| pms_plan | node_id, plan_type, calendar_id, current_revision_id | 每节点可有多版计划, 只有一个当前草稿 |
| pms_plan_revision | plan_id, revision_no, status, baseline_at, approved_by | UNIQUE(plan_id,revision_no), 已批准版本不可改 |
| pms_task | revision_id, parent_task_id, wbs_code, name, owner_id, start_date, end_date, duration, weight, progress, actual_start, actual_finish, status | UNIQUE(revision_id,wbs_code), 与计划同项目, 叶任务累计进度 |
| pms_task_dependency | revision_id, predecessor_id, successor_id, relation_type, lag_days | 禁自关联/环, 同一版本, V1先支持FS |
| pms_calendar | organization_id, name, timezone | 工作日/节假日独立日历规则 |
| pms_calendar_day | calendar_id, date, working_minutes | UNIQUE(calendar_id,date), 用于持续时间及偏差计算 |
| pms_gate_template | template_code, revision, node_type, required_evidence_schema | 模板带版本, 编号可配置, 不硬编码DQ归属 |
| pms_gate | node_id, template_id, target_date, status, evidence_snapshot_id, decision_id | 一次批准绑定固定证据版本, 失效后重新评审 |
| pms_gate_check | gate_id, check_code, required, result, evidence_revision_id | 强制检查项全部满足才可申请 |
| pms_approval_request | entity_type, entity_id, entity_version, workflow_id, status, requester_id, reviewer_id | 幂等business_key, 申请与回执分离 |
| pms_urs_item | urs_code, source_revision_id, text, category, priority, owner_id, status | UNIQUE(project_id,urs_code), 内容版本化 |
| pms_trace_link | urs_item_id, target_type, target_id, target_revision, relation | 设计响应/任务/验证均可追踪, 同项目 |
| pms_verification | urs_item_id, method, test_stage, evidence_revision_id, result, executed_by, executed_at | 验证通过须证据, FAT/SIT/SAT分别记录 |
| pms_deviation | verification_id, severity, description, disposition, owner_id, due_date, status, closure_evidence_id | 未关闭关键偏差阻止对应验收 |
| pms_document | node_id, document_no, category, owner_id, current_revision_id | UNIQUE(project_id,document_no) |
| pms_document_revision | document_id, revision_no, storage_key, sha256, mime, size, status, approved_at | UNIQUE(document_id,revision_no), 发布版本不可覆盖 |
| pms_delivery_item | gate_id, document_revision_id, recipient_ref, issued_at, acknowledged_at, status | 指向明确文档版本, 签发和回执分开 |
| pms_cost_sheet | node_id, kind, revision, currency, status, total | kind=estimate/budget/actual/settlement, 概预算可多版 |
| pms_cost_line | sheet_id, cost_code, source_system, source_key, amount, hours, allocation_id | 来源唯一约束, 不重复计费 |
| pms_allocation | period, pool_id, driver_type, driver_snapshot, rule_revision, amount, status | 冻结分摊依据, 各接收方合计等于原金额 |
| pms_risk | node_id, probability, impact, score, owner_id, response, due_date, status | score=probability*impact, 影响可含进度/成本/质量 |
| pms_issue | node_id, source_risk_id, severity, owner_id, due_date, status, closure_evidence_id | 从风险转问题保留关联, 不覆盖风险历史 |
| pms_change | node_id, origin_type, origin_id, schedule_delta, cost_delta, scope_delta, status, approved_revision_id | 批准后生成新基线, 不回写历史基线 |
| pms_external_ref | system_code, entity_type, entity_id, external_key, external_version | UNIQUE(system_code,entity_type,external_key) |
| pms_outbox | event_id, aggregate_id, aggregate_version, event_type, payload, status, next_attempt, attempts | 与业务写入同事务, 至少一次投递 |
| pms_inbox | source_system, event_id, payload_hash, status, error_code | UNIQUE(source_system,event_id), 同ID不同hash报警 |
| pms_ai_run | requester_id, project_scope, as_of, model_id, prompt_version, input_hash, status | 权限快照, 成本/耗时/审计信息 |
| pms_ai_suggestion | run_id, suggestion_type, structured_output, evidence_refs, confidence, decision | 只读建议, 正式命令另行人工提交 |

## 4. 关键事务与恢复

1. 建项目: 校验权限和负责人 -> 插项目 -> 插主节点 -> 插成员 -> 插创建事件 -> 提交. 任何步骤失败全部回滚.
2. 状态更新: 读取授权项目 -> 比较版本和允许转换 -> 检查业务前提 -> CAS更新 -> 插事件 -> 提交. 并发失败不产生孤立事件.
3. 批准基线(后续): 冻结任务/依赖/日历快照 -> 检查依赖DAG -> 保存审批请求/outbox -> 提交; 审批成功后原子切换当前基线引用.
4. 收成本(后续): 写入唯一来源事件 -> 校验币种/项目映射 -> 入实际成本 -> 汇总同版本投影 -> 提交. 失败进入隔离队列, 不丢弃或重复记账.

## 5. 迁移和回滚

首批迁移分业务表, 菜单权限和审计版本三组. down 按003/002/001逆序, 先移除审计版本约束, 再删除菜单绑定, 最后按事件/成员/节点/项目的依赖顺序删除新表. 仅在独立验证数据库执行回滚验证. 已含正式项目数据的部署采用备份与前向修复, 不自动执行破坏性回滚.

验收覆盖: 空库全量迁移, 主子单机关联, 重复编号, 跨项目父节点, 非法日期, 旧版本提交, 角色和成员授权, 审计事务一致性. 两个数据库分别运行, 不能用 SQL 文本对比替代 MySQL 实测.
