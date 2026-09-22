# 计划工作台接口合同

所有路径位于 `/api/pms/projects/:id`. 请求使用 JSON, 返回标准 `code/msg/data` 信封和真实 HTTP 状态. 所有写操作携带当前项目 `version`, 返回 `data = {result, project_version}`. 乐观锁冲突返回 409, 未授权返回 403, 跨项目对象不可读取或绑定.

## 读取与权限

`GET /planning` 返回 `project_version`, `plan_revision`, `plan_status`, `calendar`, `tasks`, `dependencies`, `resources`, `capacities`, `allocations`, `schedule`, `overallocations`, `baselines`, `feedback`, `current_user_id`, `approved_changes`(仅当前项目最新已批准变更的 id/title).

读取要求 `pms:project:query` 和项目可读范围. 设计变更, 提交及进度反馈要求 `pms:project:edit` 和项目经理/编辑成员范围. 审批要求 `pms:plan:approve`, 项目可读范围和与提交者不同的用户. 管理员同样不能自审.

## WBS与排程

| 操作 | 路径 | 请求字段, 均另外携带 version |
| --- | --- | --- |
| 创建任务 | POST /tasks | parent_id, wbs_code, name, task_type, duration_days, owner_id, start_date, description |
| 修改任务 | PUT /tasks/:task_id | 上述字段的变更值 |
| 删除任务 | DELETE /tasks/:task_id | 仅 version |
| 添加依赖 | POST /dependencies | predecessor_id, successor_id, dependency_type, lag_days |
| 删除依赖 | DELETE /dependencies/:dependency_id | 仅 version |
| 保存日历 | PUT /calendar | working_days, holidays, extra_workdays, hours_per_day |
| 反馈实际进度 | POST /tasks/:task_id/feedback | status, percent_complete, remaining_days, comment |

任务类型为 `summary/task/milestone`. 普通任务工期为 1-3650 个工作日, 汇总任务与里程碑输入工期为 0. 父任务只能是汇总任务, 禁止跨项目或循环父子关系. 汇总任务的排程由子任务汇总. 开始日期表示最早允许开始日期, 未提供时取项目开始日期; 两者均缺失时拒绝保存. 负责人是当前项目有效成员. 草稿允许暂缺负责人, 计划提交时所有叶任务必须有负责人.

任务编号在项目内唯一. 已有子级, 依赖, 分配或实际反馈的任务不能直接删除. 实际反馈仅在执行阶段允许, 状态为 `in_progress/blocked/done`, 百分比不可倒退, `done` 必须为 100% 且剩余工期为 0. 修改计划不会覆盖已上报进度. 工时单由财务模块管理, 本接口不采集工时.

依赖类型支持 FS, SS, FF, SF. 使用工作日索引和半开区间 `[开始, 开始+工期)` 计算约束, lag_days 为 -3650 至 3650 的整数工作日. FS 表示后项开始不早于前项完成边界加滞后; SS/FF/SF 分别连接开始-开始, 完成-完成, 开始-完成. 前项和后项必须是不同的非汇总任务, 禁止重复边或依赖环.

日历 `working_days` 使用 1-7 表示周一至周日, 至少一个; `holidays` 和 `extra_workdays` 是 ISO 日期数组, 额外工作日优先. `hours_per_day` 为 0.01-24. 默认周一至周五, 每天 8 小时.

`schedule.tasks` 含 task_id, name, task_type, start_date, end_date, duration_days, total_float, critical, working_dates. end_date 为最后实际占用工作日, 里程碑为其发生日. critical_path 是所有零总时差叶任务的拓扑序, 可能包含并行的多条关键支路. 项目级还有 start_date, end_date, working_days. 以100年计算窗口和每项目1000项任务限制保护计算边界.

## 资源

| 操作 | 路径 | 请求字段, 均另外携带 version |
| --- | --- | --- |
| 创建资源 | POST /resources | name, resource_type, user_id, daily_capacity |
| 修改资源 | PUT /resources/:resource_id | 上述字段的变更值 |
| 删除资源 | DELETE /resources/:resource_id | 仅 version |
| 覆盖日容量 | PUT /resources/:resource_id/capacity | date, capacity_hours |
| 添加任务分配 | POST /allocations | task_id, resource_id, hours_per_day |
| 删除任务分配 | DELETE /allocations/:allocation_id | 仅 version |

资源类型为 person/equipment. 人员资源必须绑定项目成员, 项目内同一成员仅一份资源档案; 设备不绑定用户. 日容量 0-24 小时, 分配量 0.01-24 小时, 均最多两位小数. 分配作用于任务的每个实际排程工作日. 里程碑和汇总任务不接受资源分配. 单日容量覆盖优先于默认日容量, 0 表示不可用. 设备超配按项目资源与日期汇总. 人员按 user_id 合并所有未结束项目的最新计划分配, 多个项目的日容量设置取保守最小值. 返回 resource_id, resource_name, date, planned_hours, capacity_hours, excess_hours; 共享人员另外返回 scope=shared_person, project_hours, other_project_hours. 仅返回当前项目资源和匿名工时汇总, 不暴露外部项目标识, 名称或任务. 这是计划负荷预测, 不是跨项目资源预留锁; 提交, 批准和进入执行时均重新检查当前共享负荷.

## 基线与变更

`POST /planning/submit {version, comment, change_id?}` 在 planning 或 execution 阶段提交, 要求任务可排程, 负责人有效且无资源超配. 执行阶段必须关联同项目最新已批准的 change_id, 审批时再次校验; 计划阶段允许不带 change_id. 提交瞬间冻结完整设计与计算结果, pending 期间锁定设计变更. 同一 plan_revision 只提交一次.

`POST /planning/baselines/:baseline_id/review {version, decision, comment}` 使用 approved/rejected, 拒绝理由必填. 审批只能决定状态及记录审批意见, 不得修改快照. 已审批基线没有编辑或删除接口. 拒绝后修改设计产生新修订再提交; 批准后也可修订, 但新设计必须重新批准才能成为执行基准.

`GET /planning/baselines/:baseline_id` 返回冻结 snapshot 与提交/审批元数据. `GET /planning/baselines/:baseline_id/diff` 返回 changed, baseline_revision, current_revision, changes. 每个变化包含 entity_type, entity_id, change_type(added/removed/modified), before, after. 任务实际进度不属于设计差异.

其他模块通过 `planning/execution-ready! [q project]` 检查最新设计修订已经批准, 且当前设计的 SHA-256 摘要仍匹配快照. 项目日期变更须在同一事务调用 `planning/project-dates-changing! [q project]`, 保证审批锁和计划修订一致. 会议行动项可在调用方事务使用 `planning/create-task-record! [q project actor fields]` 创建真实WBS任务并递增计划修订.

进度反馈包含 project_version, 按此版本倒序排列, 确保同秒多次反馈顺序稳定. 会议行动, URS追踪或任何工时单已经引用的任务不得删除.
