# 计划工作台接口合同

所有路径位于 `/api/pms/projects/:id`. 请求使用 JSON, 返回标准 `code/msg/data` 信封和真实 HTTP 状态. 所有写操作携带当前项目 `version`, 返回 `data = {result, project_version}`. 乐观锁冲突返回 409, 未授权返回 403, 跨项目对象不可读取或绑定.

## 读取与权限

`GET /planning` 返回 `project_version`, `plan_revision`, `plan_status`, `calendar`, `tasks`, `dependencies`, `resources`, `capacities`, `allocations`, `schedule`, `overallocations`, `baselines`, `feedback`, `current_user_id`, `approved_changes`(仅当前项目最新已批准变更的 id/title); 增量6 起另含 `stages` / `stage_weight_source` / `stage_weights` / `progress_rollup`, `plan_conflicts`, `earned_value`, `performance_variances` (H06 绩效偏差只读派生, 见下文 "计划网络, 挣值与扫描"), `progress_history`, `reschedules`, `reminders` (见下文 "计划网络, 挣值与扫描").

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
| 反馈实际进度 | POST /tasks/:task_id/feedback | status, percent_complete, remaining_days, comment, actual_start?, actual_end? |

任务类型为 `summary/task/milestone`. 普通任务工期为 1-3650 个工作日, 汇总任务与里程碑输入工期为 0. 父任务只能是汇总任务, 禁止跨项目或循环父子关系. 汇总任务的排程由子任务汇总. 开始日期表示最早允许开始日期, 未提供时取项目开始日期; 两者均缺失时拒绝保存. 负责人是当前项目有效成员. 草稿允许暂缺负责人, 计划提交时所有叶任务必须有负责人.

任务编号在项目内唯一. 已有子级, 依赖, 分配或实际反馈的任务不能直接删除. 实际反馈仅在执行阶段允许, 状态为 `in_progress/blocked/done`, 百分比不可倒退, `done` 必须为 100% 且剩余工期为 0. 修改计划不会覆盖已上报进度. 工时单由财务模块管理, 本接口不采集工时. `actual_start` / `actual_end` 为 ISO 日期: 首次反馈未填实际开始时记为当天, 两者都不能晚于当天; `actual_end` 只有 `done` 状态可填 (未填时记为当天) 且不早于实际开始; 一经记录随任务与反馈明细返回 (`tasks[].actual_start/actual_end`, `feedback[].actual_start/actual_end`), 后续反馈不改写已记录的实际开始.

依赖类型支持 FS, SS, FF, SF. 使用工作日索引和半开区间 `[开始, 开始+工期)` 计算约束, lag_days 为 -3650 至 3650 的整数工作日. FS 表示后项开始不早于前项完成边界加滞后; SS/FF/SF 分别连接开始-开始, 完成-完成, 开始-完成. 前项和后项必须是不同的非汇总任务, 禁止重复边或依赖环.

日历 `working_days` 使用 1-7 表示周一至周日, 至少一个; `holidays` 和 `extra_workdays` 是 ISO 日期数组, 额外工作日优先. `hours_per_day` 为 0.01-24. 默认周一至周五, 每天 8 小时.

`schedule.tasks` 含 task_id, name, task_type, start_date, end_date, duration_days, total_float, critical, working_dates. end_date 为最后实际占用工作日, 里程碑为其发生日. critical_path 是所有零总时差叶任务的拓扑序, 可能包含并行的多条关键支路. 项目级还有 start_date, end_date, working_days. 以100年计算窗口和每项目1000项任务限制保护计算边界.

`tasks[].scope_*` 与 `scope_coverage` (H03 范围基线覆盖性审查) 读取时按需求 satisfies 追踪链派生, 不落库, 不构成任何门控: 叶节点定义为非汇总任务 (`task` / `milestone`), 汇总节点标 `scope_leaf=false` 且不进入覆盖分母; 每条指向该任务的 `trace` (target_kind=task) 计入 `scope_trace_count`, 其中 `relation=satisfies` 计入 `scope_satisfies_count`, `relation=verifies` 计入 `scope_verifies_count`; 只要 `scope_satisfies_count>0` 即 `scope_covered=true` (verifies 只标注不计覆盖). 项目级 `scope_coverage` 返回 `total-leaves / covered-leaves / uncovered-leaves / coverage-pct (covered/total 四舍五入整数百分比, 无叶节点时为 0) / uncovered-codes (未覆盖叶的 wbs_code 数组)`. 覆盖性审查仅作只读提示: 存在未覆盖叶时计划仍可正常保存与提交冻结, 冻结后覆盖情况随追踪链读取时重算.

`tasks[].baseline_*` 与 `baseline_variance` (H04 任务级基线进度偏差) 读取时派生, 不落库, 不构成任何门控: 纯函数 `planning.baseline/variance` 取 `baselines` 中最新一条 `status=approved` 的基线, 解析其冻结 `snapshot_json` 得到基线排程, 与当前 `snapshot.schedule` 逐任务比较. 无已批准基线时 `baseline_variance` 返回 `{available:false}` 且逐任务不带 `baseline_*` 键 (台账显"无基线", 面板显"尚无已批准计划基线"). 有已批准基线时: 当前任务在基线中存在则按完成日日历天差 `date-slip` (正=延后) 给出 `baseline_state` (`behind` 延后 / `ahead` 提前 / `on_baseline` 持平) 与 `baseline_start_variance` / `baseline_finish_variance` (整数日历天, 正表示晚于基线); 基线中不存在的当前任务 `baseline_state=added` 且两个偏差为 `null`. 项目级 `baseline_variance` 汇总返回 `available / baseline_id / baseline_revision / total / on-baseline / behind / ahead / added / worst-finish-slip (所有非空完成日偏差的最大值, 下限 0)`. 键名不带尾随 `?` 以免 JSON 序列化为字面键. 偏差只读呈现: 既不阻断任务编辑, 也不阻断提交冻结; 重排或改工期使当前排程偏离已批准基线时偏差立即可见, 而基线本身不被改写.

`schedule_sensitivity` (H04 关键路径敏感度 / 总时差分布) 由纯函数 `planning.schedule/float-sensitivity` 对 CPM 排程结果 `schedule.tasks` 的每任务 `total_float` 读取时派生, 不落库, 不构成任何门控 (既不阻断保存/提交/冻结, 也不改变排程计算): 只统计非汇总叶任务 (`task_type` 非 `summary`, 与 `critical_path_staffing` / `resources/allocate!` 分母口径一致), 按总时差分三档 —— 关键 (total_float = 0), 近关键 (0 < total_float <= band), 宽松 (total_float > band). 带宽 `band` 随项目工作日跨度自适应: `band = max(near-critical-floor, round(near-critical-ratio * span))`, 其中 `near-critical-ratio = 0.1`, `near-critical-floor = 2` 个工作日 (防止短项目带宽退化为 0), `span` 取 `schedule.working_days`. 返回 `available (是否存在至少一个叶任务) / band (近关键带宽天数) / span (项目工作日跨度) / leaf-count (非汇总叶任务数) / critical-count / near-critical-count / comfortable-count (三档计数, 满足 leaf-count = critical + near + comfortable 的分区不变量) / min-near-float (近关键档最小总时差, 无近关键任务时为 nil) / near-critical-tasks (近关键任务清单按时差升序, 每项含 task_id/wbs_code/name/total_float/duration_days)`. 无任何任务时 `available=false`, `leaf-count` 与三档计数为 0, `near-critical-tasks` 为空数组, 面板显"尚未排程 (需任务设置工期与依赖后计算总时差)". 键名不带尾随 `?` 以免 JSON 序列化为字面键. 界面在"资源与日历"页签以彩色标签呈现三档分布与自适应带宽, 并列出"滑移即上关键链"的近关键任务清单, 是对既有 `critical_path` (仅暴露零浮动关键任务) 的只读延伸: 敏感度看"关键链之外还有谁快变关键".

`schedule_connectivity` (H04 计划网络连通性 / 依赖拓扑完整性) 由纯函数 `planning.schedule/network-connectivity` 对 `snapshot` 的 `tasks` 与 `dependencies` 读取时派生, 与 `schedule_sensitivity` (浮动分档) 和 `schedule_tightness` (整体松弛) 正交: 前两者看"总时差怎么分布", 本项看"依赖网络连没连起来" —— 未链接任务会被 CPM 当成同日开工, 平行未汇合的链段会割裂工期, 因此关键路径是否可信取决于拓扑是否完整. 同样只统计非汇总叶任务 (`task_type` 非 `summary`, 分母口径一致), 依赖视为无向边, 用并查集 (union-find) 对端点合并, 仅统计至少含一个叶任务的连通分量. 返回 `available (是否存在至少一个叶任务) / leaf-count / dependency-count (两端齐全的依赖条数) / linked-count (至少出现在一条依赖端点上的叶任务数) / linked-pct (round(100 * linked-count / leaf-count), 无叶任务时为 0) / unlinked-count / unlinked-tasks (既无前置又无后继的孤立叶任务清单按 wbs_code 升序, 每项含 task_id/wbs_code/name) / component-count (含叶任务的依赖链段数) / connectivity-level (定性档位)`. 档位优先级: 存在孤立任务 -> `unlinked`, 否则链段数 >= 2 -> `fragmented` (多段平行未汇合), 否则 -> `connected`; 无叶任务时 `connectivity-level` 为 nil. 无任何任务时 `available=false`, 各计数为 0, 清单为空数组, 面板显"尚未建任务 (创建任务后方可检查依赖网络是否连贯)". 键名不带尾随 `?` 以免 JSON 序列化为字面键. 只读呈现, 不构成门控 (既不阻断任务/依赖编辑, 也不改变排程计算). 界面在"资源与日历"页签以彩色标签呈现连通档位/已链接占比/链段数/未链接任务数, 并列出孤立任务清单.

`schedule_tightness` (H04 排程紧凑度 / 整体松弛余量) 由纯函数 `planning.schedule/float-tightness` 对同一 CPM 排程结果读取时派生, 与 `schedule_sensitivity` 正交互补: 敏感度逐任务看"谁快变关键", 紧凑度看"整个计划有多紧" —— 关键任务占比越高, 一处延误越易波及全局. 同样只统计非汇总叶任务 (`task_type` 非 `summary`, 分母口径一致), `total_float` 为整数工作日. 返回 `available (是否存在至少一个叶任务) / leaf-count / critical-count (总时差为 0 的叶任务数) / critical-pct (round(100 * critical-count / leaf-count), 无叶任务时为 0) / avg-float (全部叶任务总时差算术均值, 保留一位小数, 无叶任务时为 0) / min-float / max-float (叶任务总时差的最小/最大值, 无叶任务时为 nil) / span (项目工作日跨度) / tightness-level (按关键任务占比给的定性档位)`. 档位阈值: `critical-pct >= 50 -> very-tight`, `>= 25 -> tight`, `>= 10 -> moderate`, 否则 `loose` (无叶任务时 `tightness-level` 为 nil). 无任何任务时 `available=false`, `critical-pct` 与 `avg-float` 为 0, `min-float` / `max-float` / `tightness-level` 为 nil, 面板显"尚未排程 (需任务设置工期与依赖后计算总时差)". 键名不带尾随 `?` 以免 JSON 序列化为字面键. 只读派生, 不落库不投递, 不构成任何门控 (既不阻断保存/提交/冻结, 也不改变排程计算). 界面在"资源与日历"页签紧随敏感度面板, 以彩色标签呈现紧凑度档位、关键任务占比、平均/最小/最大总时差与"关键任务 / 叶任务"计数.

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

`overload_summary` (H05 资源超配项目级只读汇总) 由纯函数 `planning.capacity/overload-summary` 对上述逐日 `overallocations` 明细读取时聚合派生, 不落库, 不构成任何门控 (既不阻断保存/提交/冻结, 也不改变明细): 返回 `available (是否至少一条超配) / total-rows (逐日超配明细行数) / distinct-resources (涉及的去重资源数) / person-rows (人员日超配行数, 即 scope=shared_person 的行) / equipment-rows (设备日超配行数 = total-rows - person-rows) / worst-excess-hours (所有明细中单日最大超出工时) / peak-date (按日汇总超出工时最大的那一天, 无超配时为 null)`. 无超配时 `available=false` 且计数为 0, `peak-date` 为 `null`, 台账显"当前排程未发现资源超负荷". 键名不带尾随 `?` 以免 JSON 序列化为字面键. 界面在"资源与日历"页签顶部"资源负荷检查"面板以彩色标签呈现该概览, 其下逐日明细表口径不变.

`allocation_coverage` (H05 任务工时投入覆盖度) 由纯函数 `planning.capacity/allocation-coverage` 对 `tasks` 与 `allocations` 读取时聚合派生, 不落库, 不构成任何门控 (既不阻断保存/提交/冻结, 也不改变任何写路径): 分母只取可分配叶任务 (`task_type=task`), 汇总任务与里程碑不接受工时分配故不计入; 每个可分配任务只要存在至少一条工时分配即视为"已投入", 同任务多条分配只算一次. 返回 `available (是否存在至少一个可分配任务) / total-tasks (可分配任务总数) / with-allocations (已投入数) / without-allocations (未投入数) / coverage-pct (with/total 四舍五入整数百分比, 无可分配任务时为 0) / unallocated-tasks (未投入任务清单, 每项含 task_id/wbs_code/name)`. 无可分配任务时 `available=false` 且计数为 0, `unallocated-tasks` 为空数组, 面板显"尚无普通任务 (汇总与里程碑不计入投入覆盖)". 键名不带尾随 `?`. 界面在"资源与日历"页签以彩色标签呈现该概览并列出未投入工时的任务, 与超配汇总互补: 超配看"是否排太多", 投入覆盖看"是否还没排".

`critical_path_staffing` (H05 关键路径投入缺口) 由纯函数 `planning.capacity/critical-path-staffing` 对 `tasks`, `allocations` 与排程结果 `schedule.critical_path` 读取时聚合派生, 不落库, 不构成任何门控: 分母只取关键路径上的可分配叶任务 (`task_type=task`), 里程碑与汇总即使落在关键路径也不计入 (与 `allocation_coverage` 及 `resources/allocate!` 口径一致); 每个关键路径任务只要存在至少一条工时分配即视为"已投入". 返回 `available (关键路径上是否存在至少一个可分配任务) / critical-tasks (关键路径可分配任务数) / staffed (已投入数) / unstaffed (缺口数) / staffing-pct (staffed/critical-tasks 四舍五入整数百分比, 无任务时为 0) / unstaffed-tasks (缺口任务清单, 每项含 task_id/wbs_code/name)`. 关键路径无可分配任务时 `available=false` 且计数为 0, 面板显"关键路径上暂无可分配任务 (里程碑与汇总不计入)". 键名不带尾随 `?`. 界面在"资源与日历"页签以彩色标签呈现该概览并列出关键路径上未排工时的任务, 是 `allocation_coverage` 的优先级聚焦: 覆盖度看"全部任务谁还没排", 本项只看"关键路径上还没排的最高进度风险".

## 基线与变更

`POST /planning/submit {version, comment, change_id?}` 在 planning 或 execution 阶段提交, 要求任务可排程, 负责人有效且无资源超配. 执行阶段必须关联同项目最新已批准的 change_id, 审批时再次校验; 计划阶段允许不带 change_id. 提交瞬间冻结完整设计与计算结果, pending 期间锁定设计变更. 同一 plan_revision 只提交一次.

`POST /planning/baselines/:baseline_id/review {version, decision, comment}` 使用 approved/rejected, 拒绝理由必填. 审批只能决定状态及记录审批意见, 不得修改快照. 已审批基线没有编辑或删除接口. 拒绝后修改设计产生新修订再提交; 批准后也可修订, 但新设计必须重新批准才能成为执行基准.

`GET /planning/baselines/:baseline_id` 返回冻结 snapshot 与提交/审批元数据. `GET /planning/baselines/:baseline_id/diff` 返回 changed, baseline_revision, current_revision, changes. 每个变化包含 entity_type, entity_id, change_type(added/removed/modified), before, after. 任务实际进度不属于设计差异.

其他模块通过 `planning/execution-ready! [q project]` 检查最新设计修订已经批准, 且当前设计的 SHA-256 摘要仍匹配快照. 项目日期变更须在同一事务调用 `planning/project-dates-changing! [q project]`, 保证审批锁和计划修订一致. 会议行动项可在调用方事务使用 `planning/create-task-record! [q project actor fields]` 创建真实WBS任务并递增计划修订.

进度反馈包含 project_version, 按此版本倒序排列, 确保同秒多次反馈顺序稳定. 会议行动, URS追踪或任何工时单已经引用的任务不得删除.

## 计划网络, 挣值与扫描 (增量6)

| 操作 | 路径 | 请求字段, 均另外携带 version | 权限 |
| --- | --- | --- | --- |
| 派生主/子/单机计划 | POST /planning/derive | node_ids? (限定节点 id 数组), reason? | 项目编辑 |
| 重排节点计划 | POST /planning/nodes/:node_id/reschedule | start_date, reason (必填) | 项目编辑 |
| 覆盖阶段权重 | POST /planning/stage-weights | stages [{code, weight}] (覆盖模板全部阶段, 0-100 整数且合计 100), reason? | 项目编辑 |
| 生成当日快照与提醒 | POST /planning/snapshot | date? (默认当天) | 项目编辑 |
| 全量定时扫描 (平台级) | POST /api/pms/scan | date? | `pms:config:edit` (定时任务以 admin 身份调用) |

派生 (B03) 以已实例化模板的阶段定义为准: 每个阶段声明 `levels` (`main` / `sub` / `machine` 的子集, 内置目录 S1 main, S2 main+sub, S3 sub+machine, S4 machine, S5 sub+machine, S6 main, S7 main+machine, S8 main; 旧快照未声明时按同一默认表) 与 `default_days` (派生任务工程默认工期, 未声明取 5). 主计划层在每个含 main 的阶段容器下建 `<阶段编码>-MAIN` 任务并按阶段顺序 FS 串联; 每个子项目/单机节点在其节点容器下按含该层级的阶段建 `<节点编码>-<阶段编码>` 任务并串联, 责任人默认项目经理, `source_type=derived`. 同 WBS 编号已存在则跳过 (幂等, 不改动已有任务与实际进度), 结果返回 `node_count / main_stage_count / derived_count / skipped_count / dependency_count / tasks`. 未实例化模板 409; `node_ids` 含主项目或他项目节点 400.

`plan_conflicts` (B03 主子约束冲突定位) 读取时派生不落库: 子项目/单机叶子任务的排程完成日晚于主计划同阶段窗口 (主计划层同阶段叶子任务的最晚排程完成日) 即为冲突, 返回 `task_id / wbs_code / name / node_id / node_code / stage / end_date / main_end_date / days_late`; 主计划该阶段没有任务时不判定. 节点重排后若晚于主计划窗口, 冲突会立即出现, 由项目经理调整主计划或重新协商.

重排 (H04) 只针对子项目/单机节点 (主项目 400), 作用于该节点含后代节点下全部未开始 (`todo`) 叶子任务: 按项目日历把最早开始日移到 `start_date`, 其余任务保持相同的工作日偏移; 节点下没有叶子任务或已有开始/完成任务时 409. 已批准基线不改写 (仍是原承诺), 计划设计修订递增, 执行期须再次提交基线并绑定已批准变更; 每次重排写入治理记录 `reschedule` (`code RS-<节点编码>-<序号>`, from_start / to_start / delta_working_days / task_count / task_ids / reason), 经 `reschedules` 返回.

阶段权重覆盖 (B02) 写入版本化治理记录 `stage-weights` (每次覆盖 revision 递增, 最新生效, 模板快照不变): `stages` 必须覆盖模板全部阶段编码, 编码不在模板内 / 重复 / 合计不为 100 均 400. 生效权重经 `stages[].weight` (`override=true` 标记) 与 `stage_weight_source` (`project_override` / `template` / `equal_weights`) 返回, 进度卷积 `progress_rollup.source` 同口径; 组合看板卡片同样按覆盖后的权重卷积. 业务口径批准前这只是本地规则, B02 状态不因此上行.

挣值 `earned_value` (H06) 以计划工作日为价值单位, 读取时按状态日期 (当天) 派生: 每个叶子任务 BAC = 计划工期, PV = 排程工作日中不晚于状态日期的个数 (上限 BAC), EV = BAC x 完成百分比, AC = 已批准工时分钟 / 60 / 日历每日工时; 项目级返回 `bac_days / pv_days / ev_days / ac_days / sv_days / cv_days / spi (EV/PV) / cpi (EV/AC) / eac_days (BAC/CPI) / etc_days / percent_complete / planned_finish / forecast_finish (状态日期 + ceil(剩余价值/SPI) 个工作日; 全部完成时取计划完工) / schedule_status (on_track 0.9-1.1, behind, ahead, no_baseline_yet) / cost_status (on_track, over, under, no_actuals)` 及按阶段 `stages[]` 与节点 `nodes[]` 的同组指标. 这不是财务挣值 (金额口径在增量7 费率之后), 也不替代业务批准的进度口径.

绩效偏差 `performance_variances` (H06, 只读派生, 免迁移): 由纯函数 `planning.earned-value/performance-variances` 逐口径核验挣值指标, 对 `spi` 非空且 < 0.9 派生一条 `variance_kind = schedule` (状态 `behind`), 对 `cpi` 非空且 < 0.9 派生一条 `variance_kind = cost` (状态 `over`); 每条回显 `metric` (spi/cpi), `value`, `threshold` (0.9), `status_date`, 以及纠正措施聚合整数键 `variance_action_total` (为该偏差登记的措施数), `variance_action_open` (其中未闭环数, 排除 `closed`/`converted`) 与状态键 `variance_action_state` (`unimplemented` 零措施 / `in-progress` 有措施未全闭环 / `completed` 全闭环). 键名不带尾随 `?`. 登记纠正措施是 `POST /governance/actions/from-variance` 写命令 (见治理契约), 复用 `action` 治理 kind, 把 `variance_kind`/`variance_status_date` 写进 payload, 不新增表结构; 闭环完全复用既有行动链 `/actions/:rid/complete` 与 `/actions/:rid/verify`. 本项只读派生不构成门控 (偏差不阻断任何登记或流转).

偏差纠正措施闭环汇总 `variance_closure` (H06, 只读派生, 免迁移): 在 `performance_variances` 之上由纯函数 `planning.earned-value/variance-closure-summary` 跨全部挣值偏差聚合纠正措施的整体闭环健康度, 与逐偏差明细同源, 随 `read-plan` 以 `variance_closure` 键返回. 输出整数键 `variance_count` (偏差总数), `variance_with_action` (至少登记了一项措施的偏差数), `variance_closed` (其措施全部闭环的偏差数), `action_total` (带 `variance_kind` 标注的措施总数, 即由偏差登记的纠正措施, 不含普通会议行动), `action_closed` (其中 `closed`/`converted` 数), `action_open` (未闭环数) 与 `action_closure_pct` (`round(100 x action_closed / action_total)`, 分母为 0 时取 0). 键名不带尾随 `?`. 前端在计划与执行/进度卷积页挣值面板下方新增只读"偏差纠正措施闭环汇总"面板, 以指标卡展示上述字段, 闭环率按 100% (蓝) / 部分 (金) / 0% (红) 着色, 无偏差时提示"暂无绩效偏差". 该汇总纯读取时派生, 不写存储, 不新增 kind/命令/路由/表结构, 不构成任何门控.

快照与扫描 (B19/H06): `POST /planning/snapshot` 与 sys_job 9001 `com.ruoyi.task/pms-progress-scan` (cron `0 0 6 * * ?`, 迁移 202609240002 以正常状态 status=0 登记, 管理员可在系统监控/定时任务页暂停或立即执行一次) 同一实现: 对项目 (定时任务: 全部 `execution` 状态项目) 写入治理记录 `progress-snapshot` (code = 日期, 含挣值指标 / 卷积总进度 / 未关闭问题数 / 主子冲突数 / 叶子数; 同日已存在则覆盖更新, 历史保留, 经 `progress_history` 按日期升序返回) 与 `reminder` 记录: 扫描逾期对象 (排程完成日已过且未完成的叶子任务, 到期已过且未关闭的问题/行动, 截止已过的未完成交底, 计划开始已过的未开始现场任务), 同对象只保留一条未关闭提醒 (code `<对象类型>:<id>`, 含 owner_id / due_date / days_overdue / tab), 已有提醒刷新逾期天数, 对象不再逾期时自动关闭 (closed_reason 记录). 扫描在项目事务内执行但不递增项目聚合版本也不写 pms_event, 避免每日扫描让在编辑的用户遭遇版本冲突. 提醒经 `reminders` (未关闭) 返回, 并进入 `我的待办` 的 `reminders` 分组 (责任人或项目经理可见, `summary.reminders` 计数); 只写本地待办, 不投递外部消息 (B19 外部通知与跨时区口径仍待规则). 手动 `POST /api/pms/scan` 返回 `date / project_count / results[]`, 无 `pms:config:edit` 权限 403.

Gate 检查项例外放行 (B12/B15, 治理模块): `POST /governance/gates/:id/checks` 的每个检查项可带 `waived=true` 与 `waiver_reason` (必填, 最多 500 字): 例外项不能同时 `passed=true` (400), 例外项计入通过数并在 `gate_progress[].waived_checks` 单独标注; 提交与批准时的证据校验把 `passed` 或 `waived` 都视为通过, 但例外项缺少说明 409. 检查项目录 (assembly-test-handover AT-1..AT-4, sat-confirm SAT-1..SAT-5) 为工程默认, 企业口径待业务批准.

