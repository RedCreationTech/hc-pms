# 工时,四算与收尾合同

所有路径以 `/api/pms/projects/:id` 为前缀. 复用实时JWT身份,功能权限与项目范围. 所有写命令包含当前项目 `version`,响应 `data.result` 与 `data.project_version`. 陈旧版本409,未授权403,所有写入与审计同事务.

## 工时

GET `/time-entries`: project:query,仅本人工时/待本人审核工时,管理员可见全部. 不附带成本金额.

POST `/time-entries`: project:edit,项目必须execution;字段task_id/work_date/hours/note/reviewer_id. 必须当前项目非汇总任务,实际日期不能晚于今天,审核人为另一个有效项目成员. 小时最多两位小数且必须恰好换成完整分钟. 整数分钟持久化,跨项目同人同日提交及批准工时总和不超过24小时. 事务预留容量,驳回释放.

POST `/time-entries/:entry_id/review`: time:approve,独立指定审核人,decision为approved/rejected,拒绝须reason. 已处理决定不能覆盖. 当前不提供批准工时的追溯更正或封期,需作为下一项财务规则扩展,不能以重新报工假装冲销.

GET `/finance` 读模型新增顶层只读派生键 `timesheet_review` (F04 工时审核闭环汇总, 免迁移/不构成门控): 由领域纯函数 `finance_time/timesheet-review-summary` 对整项目 `:time_entries` 逐条状态只读聚合, 与四算/封期口径正交. 输出 `available` (是否有工时单), `total`/`submitted`/`approved`/`rejected`/`corrected`/`processed` (=approved+rejected)/`correction-pending` (带 corrects_entry_id 且 status=submitted 的更正单数), `review-pct` (processed/total 四舍五入百分比, total=0 时为 0), 以及 `hours-total`/`hours-approved`/`hours-pending` 三档小时分布 (与状态分类正交, 各自可独立解读). 工时单无修订链, 故聚合全部工时单不取 latest. 前端项目费用页"实际工时"页签顶部渲染只读汇总面板 (待审核/已批准/已驳回/已更正/更正待审徽标 + 审核完成率 + 工时合计/已批准/待审核小时), 空态显示引导文案. 该面板仅呈现审核推进健康度, 不门控任何写操作.

## 四算

GET `/finance`: finance:query与项目阅读范围,返回cost_versions/time_entries/allocations/summary/summary_context. 普通项目成员无此权限不能查询金额. summary只比较同一期间及币种的最新已批准口径,缺失口径不填伪零. 当前margin字段为收入减成本的毛利金额,零收入也可计算负毛利;不伪称利润率或税后净利.

POST `/cost-versions`: finance:edit,kind为estimate/budget/actual/settlement;字段period(YYYY-MM),currency(CNY/USD/EUR/GBP/HKD),name,revenue,reviewer_id. 版本号按项目/口径/期间递增. 金额最多两位小数,按整数最小货币单位存储,不做隐式换汇.

POST `/cost-versions/:cost_id/entries`: category(material/labor/manufacturing/travel/other/change_loss),label,amount,可选source_ref. 相同版本同来源唯一,允许负数调整及明确零费用确认. 只有draft可编辑. DELETE `/cost-versions/:cost_id/entries/:entry_id` 仅删除草稿中非分摊生成项.

POST版本路径的 `/submit` 冻结快照; `/review` 需finance:approve且指定非提交者,字段decision/reason; `/revise` 字段reviewer_id,从approved/rejected复制新草稿保留旧版; `/cancel` 字段reason,仅draft/rejected可取消. 不直接覆盖已批准账目. 通用项目审计仅保留财务事件类型/对象/状态,审批意见保存在有财务授权的成本模型.

POST `/cost-versions/:cost_id/allocate`: amount/from_date/to_date/idempotency_key/label. 仅当前费用期间内已批准工时参与,按任务分钟权重用最大余数法精确分摊,差额确定地分给最高余数任务,总额守恒. 没有批准工时409. 输入工时ID/金额/版本/输出结果与SHA256持久化,重复幂等键同内容返回旧结果,不同内容409. 生成的labor条目不可单独删除. 当前是单项目费用池到任务的分配,不声称完成跨项目共享研发池或人工费率有效期核算.

GET `/finance` 读模型新增顶层只读派生键 `cost_review` (F06 四算版本审批闭环汇总, 免迁移/不构成门控): 由领域纯函数 `finance/cost-review-summary` 对整项目 `:cost_versions` 逐条状态只读聚合, 与四算拉通/封期口径正交. 输出 `available` (是否有费用版本), `total`/`draft`/`submitted`/`approved`/`rejected`/`cancelled`/`processed` (=approved+rejected)/`pending` (=draft+submitted), `review-pct` (processed/total 四舍五入百分比, total=0 时为 0), 以及 `by-kind` 向量按 estimate/budget/actual/settlement 四口径各给出 `total`/`approved`/`pending` 分布. 状态互斥故每个版本恰落入一个状态桶; 版本修订另建新 id 因此按全部版本聚合不取 latest. 前端项目费用页"成本与分摊"页签顶部渲染只读汇总面板 (版本总数/已批准/审批中/草稿/已驳回/已取消徽标 + 已作决定/待处理/审批完成率 + 概算-预算-核算-决算各口径总·批准·待标签), 空态显示引导文案. 该面板仅呈现独立审批推进健康度, 不门控任何写操作.

GET `/finance` 读模型新增顶层只读派生键 `allocation_review` (F05 研发费用分摊闭环汇总, 免迁移/不构成门控): 由领域纯函数 `finance/allocation-review-summary` 对整项目 `:allocations` (全部已固化费用分摊批次) 只读聚合, 与逐批 `allocate` 写路径, 四算审批闭环 (`cost_review`), 工时审核闭环 (`timesheet_review`) 及封期口径正交. 输出 `available` (是否有分摊批次), `total` (批次数), `pool-amount` (冻结费用池金额合计, 规范化两位小数字符串), `allocated-amount` (实际摊出金额合计), `entry-count` (生成的正额人工成本条目数), `zero-task-count` (因工时占比过小舍入为 0 未生成成本条目的任务数), `task-count` (覆盖的去重任务数), `conserved` (布尔, 冻结池总额是否恰等于摊出总额, 最大余数法保证恒真). 空批次时 `available=false`/`total=0`/`pool-amount`=`allocated-amount`="0.00"/`conserved=true`. 前端项目费用页"成本与分摊"页签在成本明细与费用分摊记录之间渲染只读汇总面板 "研发费用分摊闭环汇总" (分摊批次 geekblue / 覆盖任务 purple / 生成成本条目 green / 舍入未摊任务 orange 仅 `pos?` 时渲染 / 总额守恒 green 或 总额异常 red 徽标 + 一行"冻结费用池合计 X 元, 实际摊出 Y 元, 生成 N 条人工成本, 覆盖 M 个任务"及舍入零头提示), 无批次时空态提示"尚无费用分摊批次...". 该面板仅呈现分摊固化与总额守恒健康度, 不门控任何写操作.

GET `/finance` 读模型新增顶层只读派生键 `cost_margin` (F08 成本毛利看板, 免迁移/不构成门控): 由领域纯函数 `finance/margin-summary` 对整项目 `:cost_versions` 只读派生, 与逐条 `margin` 金额字段, 四算审批闭环 (`cost_review`), 四算拉通比较 (`four_count`) 及封期口径正交. 概算-预算-核算-决算四口径各取"最新已批准版本"(`status=approved` 且 `version_no` 最大者), 输出顶层 `available` (是否有任一已批准版本), `comparable` (已批准各口径币种去重后是否唯一, 不一致时前端提示不可横向比较), 以及 `by-kind` 向量按 estimate/budget/actual/settlement 各给一元素: 有最新已批准版本时 `present=true` 并回显 `version_no`/`period`/`currency`/`name` 与规范化金额 `revenue`(收入)/`cost`(成本)/`margin`(毛利=收入-成本, 沿用逐条版本既有口径), 附 `computable` (收入为正才可计算利润率) 与 `margin-pct` (毛利率百分比 = round(100×margin/revenue), `computable=false` 时为 null); 无已批准版本时该元素仅 `present=false`. 关键区别于既有 `margin` 金额字段: 本看板补齐"利润率百分比"并对零或负收入显式标注 `computable=false`/`margin-pct=null`, 不以伪利润率或伪零误导. 前端项目费用页"成本与分摊"页签在审批闭环汇总与四算比较之间渲染只读面板 "成本毛利看板" (各口径一行"收入/成本/毛利 + 毛利率 N% green 或 red 徽标", 零或负收入显示 orange "不可算 (零或负收入)" 徽标, 无已批准版本口径灰显"尚无已批准版本", 空态整体引导文案, 币种不一致时 red 徽标提示). 该面板仅呈现各口径毛利健康度, 不落库不投递不门控任何写操作.

## 承诺成本与预算控制 (H12)

承诺是"已签合同/已下订单但尚未实际发生"的占用, 与实际费用分列, 转实付时按释放金额从承诺扣除并计入实际, 不双计. 预算控制规则按基线口径(estimate或budget)对占用率设阈值, 提交承诺时评估并门控.

GET `/finance` 在四算之外追加 `commitments`(承诺台账列表) 与 `budget_control`(以budget基线、本次追加0占用评估的当前快照, 字段comparable/currency/budget/consumed_minor/remaining_minor/ratio_pct/triggered/decision). 金额均以整数最小货币单位存储并回显两位小数字符串, 不做隐式换汇.

POST `/commitments`: finance:edit, 登记草稿. 字段kind(contract/purchase/labor/other),code(必填唯一编号),supplier,currency,base_currency(缺省CNY),gross(>0,最多两位小数),exchange_rate(同币种时强制为1),description,reviewer_id(须为另一有效项目成员). 本位金额base=gross×exchange_rate(HALF_UP到最小单位). 状态draft.

PUT `/commitments/:commitment_id`: 仅draft可改, 字段同登记(不含code). 提交后不可回改.

POST `/commitments/:commitment_id/submit`: 字段baseline(estimate/budget,缺省budget),reason,override_block(boolean). 提交前用 `budget/evaluate` 计算占用率 = round(100×(已承诺 + 本次base) / 已批准基线总额). 命中action=block的启用规则且未勾选override_block时返回409并给出占用率与触发规则, 承诺保持draft; 携带override_block=true可强制放行进入submitted, 放行理由写入control_note. 基线不可比(无已批准基线或总额为0)时不门控直接进入submitted. submitted/approved状态的承诺计入"已承诺"占用, released/cancelled不再计入.

POST `/commitments/:commitment_id/review`: finance:approve,指定非提交者的独立审批人,decision为approved/rejected,拒绝须reason. 决定不可覆盖.

POST `/commitments/:commitment_id/release`: 部分或全部转实付,字段amount(>0,不超过剩余). 释放额累加到released并回写实际口径, 剩余=base−released守恒, 全部释放后状态转released, 部分释放保持approved.

POST `/commitments/:commitment_id/cancel`: 字段reason, 仅draft/submitted/approved可取消, 转cancelled并释放占用.

POST `/budget-rules`: finance:approve,新增或更新预算控制规则. 字段baseline(estimate/budget),threshold_pct(5–500),action(warn/require_approval/block),note. 带project_scoped时绑定本项目(project_id非空), 否则为系统默认(project_id为空). 系统内置默认规则: budget warn@80、budget block@100. GET `/finance` 的规则台账同时展示系统默认与项目层规则.

POST `/budget-rules/:rule_id/disable`: finance:approve,停用规则(enabled=0),字段note. 停用不删除历史, 只退出后续评估.

评估决策decision为ok/warn/require_approval/block四档, 由触发规则中的最高severity决定; warn与require_approval不阻断提交(block以外), 仅在面板提示. 本轮为本地预算占用门控与承诺-实际分离, 不宣称完成税额/收入确认/多币种对账/封期(见H13)或与外部ERP总账同步.

## 会计期间封期与费用版本门控 (H13a)

封期复用平台级 `period-lock` 配置 (config kind, 见平台配置合同), 不新增表或迁移. 锁定即 `POST /config/period-lock` 携带 period(YYYY-MM) 与 reason, 记录状态直接为 locked; 解锁即 `POST /config/period-lock/:config_id/retire` 携带 reason, 保留完整历史不物理删除. 工时提交/更正早已按同一 `period-lock` 门控 (`finance_time/period-open!`), 本轮把同一口径扩展到成本版本.

成本版本携带 `period` 字段. 领域层 `finance_cost/period-open!` 在写事务内以 `config/published-by-code q "period-lock" period` 检查该会计期间是否处于 locked, 命中则返回 409 "期间 X 已封账, 不能再新建或变更该期间的费用版本". 门控覆盖六条成本写路径: create (新建版本), add-entry (增明细), delete-entry (删明细), submit (提交审批), revise (新修订), cancel (放弃版本). 独立审批 `review!` 有意不受封期门控: 成本金额在提交时已冻结, 封期只锁"变更口径"不锁"已提交待审记录的裁决", 与工时封期口径一致, 保证审批链不因封期而卡死.

GET `/finance` 读模型在四算/承诺之外追加 `locked_periods` (当前处于 locked 状态的 period-lock 编码列表), 供前端成本版本台账在"期间"列对已封账期间渲染红色"已封账"徽标并在面板顶部展示警告横幅. 界面只呈现封账状态, 锁定/解锁操作仍统一在平台配置页完成, 不在项目费用页重复设置入口.

本轮为本地会计期间封账对成本版本写路径的门控与界面可见性, 不宣称完成封期与结算/税额/汇率的联动. 工时封期门控早已存在, 承诺封期门控见下节 H13c, 至此工时/费用/承诺三类写路径统一到同一平台级 `period-lock` 门控口径.

## 承诺成本纳入会计期间封期 (H13c)

承诺原先不携带会计期间, 无法参与封期. 本轮新增迁移为 `pms_cost_commitment` 增加可空 `period VARCHAR(7)` 列 (SQLite/MySQL 双份, `.up.sql` 每条语句后带 `--;;`), 并按 `created_at` 回填历史承诺的所属月份 (SQLite `substr(created_at,1,7)`, MySQL `DATE_FORMAT(created_at,'%Y-%m')`), 使存量数据落入正确期间. 登记承诺时 `period` 为可选字段: 留空默认当前月 `(subs (str (LocalDate/now)) 0 7)`, 填写则强制 YYYY-MM 格式并 `YearMonth/parse` 校验, 与成本版本同口径.

领域层 `finance_commitment/period-open!` 复用 `config/published-by-code q "period-lock" period`, 命中 locked 期间返回 409 "期间 X 已封账, 不能再登记或变更该期间的承诺". 门控覆盖五条承诺写路径: create (登记, 检查新期间), update-draft (草稿修改, 同时检查原期间与新期间), submit (提交评估前), release (转实付), cancel (取消). 独立审批 `review!` 有意不受封期门控: 承诺金额与预算占用在提交时已冻结, 封期只锁"变更口径"不锁"已提交待审记录的裁决", 与成本版本/工时口径一致, 保证审批链不因封期卡死.

GET `/finance` 读模型的 `locked_periods` 同时供承诺台账复用: 前端在"期间"列对已封账期间渲染红色"已封账"徽标, 面板顶部展示警告横幅, 登记/修改表单增加可选"会计期间"输入 (留空默认当前月/保持原期间). 界面只呈现封账状态, 锁定/解锁操作仍统一在平台配置页完成.

本轮为本地承诺成本纳入会计期间封账的门控与界面可见性, 不宣称完成封期与结算/税额/汇率的联动, 也不承诺 MySQL 迁移回归与生产验收.

## 收尾和关闭

GET `/closure`: project:query,返回checks/handoffs/lessons/approval/reopen_request/blockers/ready/progress/lesson_summary/project_version. 不包含成本金额或批准快照正文. ready只表示业务材料齐备,最终closed仍须独立批准.

`progress` (E10 收尾清单闭环进度只读汇总, 免迁移): 由纯函数 `com.ruoyi.domain.pms.closure/progress-summary` 在读取 `overview` 时对已富化的 `items` (检查项与移交事项合并清单, 逐条带 `:kind` check/handoff, `:status` open/completed, `:required`, 可选 `:due_date`) 与 `approval`, `lessons` 聚合派生, 以顶层键 `progress` 挂到收尾读模型. 内层键连字符不带尾随 `?`, 不落库, 不新增 kind/命令/路由/表结构, 不构成任何门控 (进度高低不阻止任何检查项/移交的登记与完成, 也不影响 `submit!`/`review!` 的状态/阻塞/独立审批硬校验; 完成仍由 `complete-item!` 强制同项目真实文档版本证据 (`governance/evidence-version!`), 移交仍须指定接收人亲自确认, 关闭仍由 `submit!` 强制 closing 态 + 全部必需清单/移交完成 + 交付链齐套 + required Gate 通过 + 无 blocker + 已批准决算且无费用草稿/待审工时, 独立审批仍由 `review!` 经 `project:close` 强制). 收尾此前只有逐条 `checks`/`handoffs` 与 `blockers`/`ready` 布尔门控视图, 项目经理能逐条看到"某项是否完成/是否必需", 却无从一眼读出整个项目结项清单到底有几项, 几项待完成, 几项已完成, 其中几项必需未完成, 几项逾期未完成, 经验登记数, 关闭审批处于哪一状态, 以及结项闭环的整体进度. 本汇总返回 `available (是否存在任一件事项或已有审批记录) / total (检查项 + 移交总数) / checks (检查项数) / handoffs (移交数) / completed (已完成数 status=completed) / open (待完成数) / required (必需项数) / required-open (必需且未完成数) / overdue-open (待完成且 `due_date` 早于今天数) / lessons (经验登记数) / approval-state (关闭审批状态 none/submitted/approved/rejected) / closure-pct (= round(100 × completed / total), 无事项时 0) / by-kind[check(收尾检查)/handoff(遗留移交) 各 {kind, label, total, completed, completed-pct}]`. 无事项时 `available=false` 且计数为 0, 面板显"尚无收尾事项, 添加检查项或移交事项后跟踪结项进度". 前端在"结项与移交"页签新增只读"收尾闭环进度"面板 (彩色标签呈现收尾项/待完成/已完成/必需未完成/逾期未完成/经验/关闭审批状态/闭环率, 其下按类别完成度分布表), 与逐条 `checks`/`handoffs` 台账及 `blockers`/`ready` 门控视图同源互补, 正交.

POST `/closure/checks`: title,required(boolean). POST `/closure/handoffs`: title,owner_id,due_date,required. 至少一个必需清单,所有移交必须完成. POST对应集合的 `/:item_id/complete`: evidence_ref(同项目真实文档版本ID),comment. 移交必须由指定接收人亲自确认. POST `/closure/lessons`: title/category/content, 可选 applicable_stage 与 owner_id (H15a). `applicable_stage` 为受控枚举之一 (启动/规划/执行/监控/收尾/质量/交付/成本/风险/采购/干系人/沟通), 非枚举值命中 `rules/fail! 400`; `owner_id` 若提供须为有效项目成员或管理者 (`kernel/user!` 校验, 否则 400). 两字段均可选, 留空/空串存 null (零回归, 既有仅 title/category/content 的登记不受影响). `closure/lessons` 读模型 `SELECT l.*, u.nick_name AS owner_name ... LEFT JOIN sys_user u ON u.user_id=l.owner_id` 回显责任人昵称 (owner_id 为空时 owner_name 为 null).

`lesson_summary` (H15 经验教训类别分布与作者覆盖度只读汇总, 免迁移): 由纯函数 `com.ruoyi.domain.pms.closure/lesson-summary` 在读取 `overview` 时对已登记经验 `lessons` (逐条带 `:category` 与 `:created_by`) 聚合派生, 以顶层键 `lesson_summary` 挂到收尾读模型. 内层键连字符不带尾随 `?`, 不落库, 不新增 kind/命令/路由/表结构, 不构成任何门控 (经验登记仍仅由 `create-lesson!` 强制 `title`/`category`(≤40)/`content` 必填, 分布/集中度高低不阻止任何经验的登记与读取). 收尾复盘此前在 `progress.lessons` 只有一个经验总数, 看不出经验集中在哪些分类, 覆盖了几类, 由多少人贡献. 本汇总返回 `available (是否有任一经验) / total (经验总数) / distinct-categories (已出现分类数) / categorized (已标注分类的经验数) / uncategorized (未标注分类的经验数) / dominant-category (最大分类名, 无则 null) / dominant-count (最大分类条数) / concentration-pct (= round(100 × dominant-count / total), 无经验时 0) / author-count (不同贡献人数) / top-author-count (最活跃作者条数) / by-category[逐类 {category, count}, 按条数降序, 同数按分类名升序]`. 无经验时 `available=false` 且计数为 0, `dominant-category` 为 null, 面板显"尚无项目经验, 登记后跟踪复盘分类分布与贡献覆盖". 前端在"结项与移交"页签新增只读"经验复盘分布"面板 (彩色标签呈现经验总数/分类覆盖/参与人数/最活跃作者/主导类别/分类集中度, 其下 antd Table 逐类展示经验类别/条数/占比进度条), 与 `progress` 收尾闭环进度面板及逐条经验台账同源互补, 正交. H15a 追加只读派生键 (同源对 `lessons` 逐条 `:applicable_stage`/`:owner_id` 聚合, 仍免迁移/不门控): `stages-declared (已标注适用场景的经验数) / distinct-stages (已出现的不同场景数) / by-stage[逐场景 {stage, count}, 按条数降序, 同数按场景名升序] / owner-assigned (已指定跟进责任人的经验数) / owner-coverage-pct (= round(100 × owner-assigned / total), 无经验时 0)`. 前端"经验复盘分布"面板据此新增紫色标签"适用场景覆盖 N 条 / K 类"与"责任人落地 N/total (P%)" (>=60% green 否则 gold), 其下追加按适用场景分布表 (适用场景/条数/占比进度条).

项目execution->closing要求叶级任务全部done. POST `/closure/submit` 需reviewer_id并且项目closing,清单/移交全部完成,交付链实际齐套/装配/适用试验/签收/售后已完成,required closure Gate通过且无blocker问题,已有批准决算且无费用草稿/待审工时.

POST `/closure/review`: project:close,指定独立审核人,decision/reason. 审批固定当前任务/计划/治理/交付/财务/收尾内容快照与SHA256. `/transition` 到closed时重新计算缺口及摘要;内容变化则必须重新提交. 归档时间持久化,终态只读,重开合同见integration.md. 暂停保留原状态;恢复还原已获准状态,新的跨项目资源冲突继续在计划工作台提示并待协调.

## 验证边界

真实SQLite/MySQL和浏览器的本轮结果以verification.md为准. 测试包括整数金额/余差守恒/零工时/独立审核/同源幂等/审计失败回滚/成员撤权/实际物料至签收至关闭/关闭快照失效/独立重开. 模块不代替ERP财务总账,不宣称已接收真实收入/回款/税额合同或生产客户数据.
