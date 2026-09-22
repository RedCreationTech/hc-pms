# 交付执行合同

状态: 本地受控执行链已实现并通过 SQLite 8 tests / 45 assertions, 属于全量 56 tests / 381 assertions 的组成部分. 新模块 MySQL 及生产验收采用 [验证记录](../verification.md) 的最终结果. 本模块覆盖矩阵 D/E/H 的明确内部子能力, 未包含包材, 专用直发规则, 上岛/下岛和出口专用流程. ERP/OA/MES/CRM 的真实回传合同仍待提供, 不把手工记录显示为已同步. 试验类型和适用环节是可配置的本项目工程基线, 不声称已确认全部企业 Gate 编号或齐套算法.

前缀 `/api/pms/projects/:id/delivery`. GET 要求项目 query/read; 普通 POST 要求 project edit; `/decision` 与 `/receipt` 要求 `pms:quality:approve` + 项目阅读资格 + 指定独立审核人. 所有 POST 必须传项目 `version`, 返回统一 `{result, project_version}`. 记录以 `id` 为主键. 指定任务及所有 URS/文档版本均验证同项目. 文本附件使用治理模块的实际已登记版本. 未知字段拒绝. 除创建准备及审批外, 实际备料/装配/试验/发运/签收/售后执行须项目为 execution 或 closing.

GET `""` 返回:

- `configuration`: required_stages, required_test_types, source. 默认 stages 为 materials,assembly,quality,shipment, tests 为 SIT,FAT,SAT. source 为 engineering_default 或 project_configuration.
- `material_requests`, `boms`, `assemblies`, `tests`, `shipments`, `service_cases`: 平铺类型化记录数组, 通用 id/code/status/created_by/owner_id/reviewer_id/submitted_by/evidence_ids.
- `project_version`, `blockers` 字符串数组, `external_sync_status: "not_configured"`.

公共创建追踪字段 `task_id`, `requirement_ids`(1..50个URS版本ID)必填; BOM从申请继承追踪字段. 通用证据 `evidence_ids` 为治理 documents 的确切版本 id 数组, 不接受网址. 日期为 ISO 日期. 审批 `decision=approved/rejected`, `reason` 必填. 通用提交 `reviewer_id,evidence_ids` 必填.

| POST路径 | 其余字段 | 状态/规则 |
| --- | --- | --- |
| `/configuration` | required_stages,required_test_types,reason | 仅 draft/initiated/planning 可配置; 数组必须非空不重复; assembly要求materials, quality要求前两者, shipment要求全部前序环节 |
| `/material-requests` | code,title,request_type: standard/long_lead/raw_material/direct_ship,owner_id,needed_on,items,task_id,requirement_ids | draft. items 为1..200条 `{code,name,quantity,unit}`, quantity为正整数; 不假装已采购 |
| `/material-requests/:rid/submit` | reviewer_id,evidence_ids | draft/rejected -> in_review |
| `/material-requests/:rid/decision` | decision,reason | -> approved/rejected |
| `/boms` | code,title,material_request_id | 仅引用 approved 申请, 复制不可变物料行与追踪版本, draft |
| `/boms/:rid/freeze` | reviewer_id,evidence_ids | 申请冻结 -> in_review |
| `/boms/:rid/decision` | decision,reason | -> frozen/rejected, 物料清单不被后续实际齐套更新覆盖 |
| `/boms/:rid/kit` | items,evidence_ids | frozen/partial/ready可登记实际齐套; items是完整 `{code,available_quantity}` 清单; -> partial/ready |
| `/assemblies` | code,title,bom_id,owner_id,task_id,requirement_ids | 引用 ready BOM, draft |
| `/assemblies/:rid/start` | evidence_ids | draft/rejected -> in_progress, BOM仍须ready |
| `/assemblies/:rid/submit` | reviewer_id,evidence_ids | in_progress -> in_review, 表示交检申请 |
| `/assemblies/:rid/decision` | decision,reason | -> approved/rejected, 拒绝后返工再开工 |
| `/tests` | code,title,assembly_id,test_type: SIT/FAT/SAT,owner_id,criteria,task_id,requirement_ids | draft; criteria为1..30个 `{code,title,required}`检查项, 至少1项必需 |
| `/tests/:rid/results` | checks,due_date | 完整 checks为 `{code,passed,actual,evidence_ids}`; 必需失败自动生成同项目blocker整改问题并保存issue_id; draft/ready/rejected -> ready |
| `/tests/:rid/submit` | reviewer_id,evidence_ids | 仅装配交检已批准, 所有必需项通过且关联整改问题已closed才可 in_review |
| `/tests/:rid/decision` | decision,reason | -> approved/rejected, 指定人独立检验 |
| `/shipments` | code,title,assembly_ids,consignee,delivery_address,planned_date,reviewer_id,task_id,requirement_ids | draft; 指定reviewer_id用于放行和签收验证; assembly_ids为1..50个装配记录 |
| `/shipments/:rid/submit` | evidence_ids | 所有装配approved, 适用发运前SIT/FAT试验approved后提交in_review |
| `/shipments/:rid/decision` | decision,reason | 独立放行 -> released/rejected |
| `/shipments/:rid/dispatch` | shipped_on,tracking_no,evidence_ids | released -> shipped, 实际日期不可晚于当天; 记录实际发运人shipped_by和装箱发运证据 |
| `/shipments/:rid/receipt` | received_on,receiver_name,acceptance: accepted/conditional/rejected,evidence_ids, 可选exception_reason,owner_id,due_date | 指定审核人确认真实签收证据且不得是实际发运人; -> received/conditional/returned. 非完整接受必须提供异常原因/负责人/期限并生成售后异常 |
| `/service-cases` | code,title,shipment_id,owner_id,due_date,task_id,requirement_ids | open, 绑定实际发运单与追踪来源 |
| `/service-cases/:rid/resolve` | resolution,reviewer_id,evidence_ids | open/rejected -> in_review |
| `/service-cases/:rid/decision` | decision,reason | 独立验证 -> closed/rejected |

齐套字段 `complete_line_count`, `required_line_count`, `kit_percent` 采用已齐套行数/冻结BOM总行数; 每行展示数量与实际可用量, 不混加不同计量单位. 不支持未审核替代料或隐式BOM修改. 超过冻结数量的可用量拒绝, 部分齐套不能开工. 同一 BOM 已有开工装配后不能降低齐套状态.

所有试验实际结果必须有证据, 必需失败自动登记治理问题并通过原有独立整改关闭流程处理. 复验不删除前次问题关联. 质量审批再核验冻结检查结果和问题关闭状态. 正式客户签收是本地责任人的证据验证, 不冒称电子签章或客户外部账户直接审批. 条件接收及拒收须关闭关联售后异常后再次确认完整接受才能作为最终收尾证据.

试验录入实际结果, 提交和批准均校验前置关系: 装配交检已批准, FAT之前适用SIT已批准, SAT之前适用SIT/FAT已批准且该装配已有received/conditional发运签收事实. 检查结果固定当时的 `prerequisite_test_ids`; 上游出现新试验版本后, 旧下游资格失效, 必须基于新前序版本复验. 发运放行与实际发运均重验适用SIT/FAT和阻塞问题. `received_on` 不可晚于当天且不得早于 `shipped_on`; 计划日期可在未来. `result_history` 保留实际结果, 证据版本, 操作者和对应项目版本.

`delivery/closure-blockers [q project]` 返回未完成事项字符串数组, `delivery/closure-ready! [q project]` 在存在阻塞时409. 必需materials环节要求至少一份ready BOM; 未采用的申请草稿不冒称采购已完成. 必需assembly或quality环节要求实际装配记录且所有装配均approved, quality逐个检查适用类型的最新有效试验. 必需shipment环节要求每个装配被received发运单覆盖, 所有已建发运单均received; 所有售后异常须closed. 空装配集合不能满足必需质量环节. 项目正式收尾仍由根生命周期调用计划/Gate/财务/清单共同验证.

`delivery.store/task-referenced? [q project task-id]` 供计划删除前检查引用. `delivery/member-removal-blockers [q project uid]` 用于保护在办责任人和指定审核人. `/receipt` 操作可重提条件接收/拒收记录, 同一命令不会默默清掉旧异常. 所有更正有项目版本和事务审计, 本轮不提供删除执行证据的命令.
