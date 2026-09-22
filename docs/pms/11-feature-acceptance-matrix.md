# HC-PMS 功能与业务闭环验收矩阵

基线日期: 2026-09-22. 目标分为两层: 目标 1 对齐原 62 页 PMS 蓝图展示的全部功能, 目标 2 在其上补齐标准 PMS 的工程业务闭环. 本文中的标准是本工业订单PMS项目约定的工程验收基线, 不表示符合某项正式标准或已获得认证. AI 是项目附加能力, 单独列出.

来源为已核对的原蓝图文本及相关嵌入示意图, 页码采用 PPT 实际页序. 原件和逐页内部提炼不包含在本仓库, 本表只保存通用功能追踪. 页面为示意而没有字段/公式定义的内容仍需澄清. 原蓝图的历史工期与投入估算不作为本项目承诺.

## 状态, 批次和证据

- `verified`: 具有版本对应的完整验证记录. 历史 Batch 1 和本轮新增模块各有对应证据, 工程验证不表示生产或全部PPT能力验收.
- `implemented / local`: 本轮明确子能力已有可运行代码及 SQLite/MySQL 模块测试. local表示本地业务事实和工程边界, 不表示仅支持SQLite, 也不表示原始整行的所有规则或真实外部接口完成.
- `partial`: 原能力已有下述本地子集, 其余范围仍保留在本行验收要求和待办中.
- `planned`: 未有该业务闭环的实现与通过证据. 即使已有设计, 模板通用能力或开发 mock, 仍保持 planned.
- `planned / 待合同`: 外部连接需要接口字段, 认证, 责任人和测试环境. 这是依赖状态, 不允许用 mock 冒充真实集成通过.
- `planned / 待规则`: 原蓝图未给足口径或详细规则, 需形成批准的业务规则后验收.

批次: B1 项目中心, B2 范围/WBS/排程/资源容量/基线/Gate及成员撤销, B3 需求/文档/协作/风险问题/正式变更评审, B4 执行/工时/质量整改/交付验收/企业集成, B5 四算/经营及正式收尾/遗留移交/归档/经验复用, B6 AI, B7 生产验收. 跨批次整行在最后一项前提满足前均不能标 verified. 先交付独立子能力时, 应拆分行, 不把整个模块提前标完成.

现有证据索引:

| 证据 | 实际记录及范围 |
|---|---|
| V | [当前验证记录](verification.md): 提交cfe4b15的SQLite 62 tests / 423 assertions,MySQL 62 tests / 392 assertions通过, 本地浏览器8 passed, 生产前端/uberjar构建和独立启动检查通过; 生产业务/外部系统未验收 |
| V1 | [Batch 1 历史验证](verification-batch1.md): 原项目中心后端各10 tests / 75 assertions, 浏览器2 passed及当时CI结果; 历史记录不替代本轮验证 |
| T | [后端测试](../../test/clj/com/ruoyi/pms_test.clj): 各行注明具体 deftest 名称, 测试源码须结合 V 的通过记录使用 |
| U | [项目中心浏览器测试](../../tests/e2e/pms.spec.js)和[工作台浏览器测试](../../tests/e2e/pms-workbench.spec.js): 本地合计8 passed, 双用户独立操作项目/计划/治理/交付/工时财务/关闭与重开; 明细见V |
| P | [规划测试](../../test/clj/com/ruoyi/pms_planning_test.clj): 本轮 SQLite/MySQL 各11 tests / 84 assertions 已通过; WBS/排程/资源容量/基线和变更约束 |
| Q | [治理测试](../../test/clj/com/ruoyi/pms_governance_test.clj): 本轮 SQLite 25 tests / 288 assertions 通过 (在 A08 任命书与 H02 干系人/RACI/沟通计划基础上再新增 C05 文档批量下载, C04 密级/阶段/结构节点归集与最新版本归集视图, C06 文档独立发布审批不漂移, B05/C07 会议会前资料绑定, H01 章程初始预算校验与版本不可变各 1 例); MySQL 本轮未执行(本地无实例), 迁移双库文件已同步; 含真实 JWT 路由, 独立审批, 文本证据, CSV, 问题重开, 风险复审, 成员任命书, 干系人识别到沟通计划生成会议闭环, 文档密级归集与会前资料版本引用, 章程可选初始预算金额/币种规范化 |
| X | [交付测试](../../test/clj/com/ruoyi/pms_delivery_test.clj): 本轮 SQLite/MySQL 各8 tests / 45 assertions 通过; [完整场景](../../test/clj/com/ruoyi/pms_delivery_scenario.clj)通过公开服务完成物料到SIT/FAT/发运/SAT, 不直改状态 |
| F | [工时财务及生命周期测试](../../test/clj/com/ruoyi/pms_finance_test.clj)及[合同](contracts/finance-closure.md): 本轮 SQLite/MySQL 各11 tests / 52 assertions 通过; 原子审批/分摊/收尾/重开 |
| O | [集成运行时测试](../../test/clj/com/ruoyi/pms_ops_test.clj): 本轮 SQLite/MySQL 各6 tests / 33 assertions 通过; inbox/outbox和受控真实HTTP协议测试, 不等于任何实际企业系统适配器已接通 |
| C | [并发回归](../../test/clj/com/ruoyi/pms_concurrency_test.clj): SQLite 6 tests / 42 assertions,MySQL 6 tests / 11 assertions; 写锁等待,池连接恢复,同版本单一提交及审计故障回滚 |
| D | [业务蓝图](02-business-blueprint.md), [领域模型](05-domain-model.md), [数据库](06-database-design.md), [API](07-api-design.md), [路线](10-development-roadmap.md): 仅设计依据, 不能当作测试通过证据 |

planned 行的证据列使用 `待:` 表示尚缺的真实验收证据. partial 行把已经实现的本地子集和仍待完成的内容并列, 不抹去原目标. 后续验收包至少包含用例, 输入数据来源, 执行环境/版本, 结果, 失败恢复和业务签收人. 修改本表状态时同时更新 V 或对应批次验证记录.

本轮 H02 增量后全新 CLI SQLite 集成运行 `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 已通过 67 tests / 486 assertions, 0 failures/errors (本地无 MySQL 实例, H02 的 MySQL 回归本轮未执行, 迁移双库文件已同步). 此前提交 `cfe4b15` 的 [CI运行35682432061](https://github.com/RedCreationTech/hc-pms/actions/runs/35682432061) 中 SQLite和MySQL后端均已通过当时全量PMS回归. 本地8个浏览器用例, 生产构建及独立启动也已通过; Linux CI浏览器8例5.1分钟首次通过,无重试/flaky,三项作业全success,最终记录见V. 本轮另在隔离 `:3100` 后端(独立空库全新迁移)跑通 H02 治理浏览器用例 `pms-h02.spec.js`(1 passed, 无未捕获JS错误), 覆盖界面登记干系人, RACI缺A冲突提示与沟通计划生成会议回写. 双库及浏览器通过不等于生产/UAT或所有复合条目已完成.

## A. 项目, 组织结构与立项

| ID | PPT 页码 | 功能 | 业务闭环验收 | 批次 | 当前状态 | 验证证据 |
|---|---|---|---|---|---|---|
| A01 | 5,12-13 | 本地项目台账和详情 | 人工创建草稿, 查询/分页/编辑后刷新持久化; 必填/日期/用户/部门校验及编号唯一 | B1 | verified | V+U; T: rest-contract-and-validation, invalid-inputs, structure-and-optimistic-lock |
| A02 | 4-5,12-13 | 两级项目与单机结构 | 自动创建 main, main下新增sub, sub下新增machine; 禁止跳级/跨项目挂接/同项目重码; 根名称编号随项目编辑同步 | B1 | verified | V+U; T: structure-and-optimistic-lock, project-root-stays-consistent |
| A03 | 4,12,17-18 | 基本团队与成员角色 | 添加有效用户, manager/editor/viewer角色调整, 不重复插入; viewer无写范围, 功能权限与项目范围相交 | B1 | verified | V+U; T: data-isolation-and-members |
| A04 | 3,10-11 | 合同/售前资料交接和产品线资料检查 | 按适用产品线校验应交文件, 缺资料退回, 完整资料随已批准立项申请关联项目 | B3+B4 | planned / 待合同 | 待: 不同产品线必交/缺交/重交案例, CRM/OA来源追溯 |
| A05 | 11 | 外部立项审批与订单建立后自动建项目 | 审批及订单建立事实到达后建项目/结构/基本团队; 重复消息不重复建, 缺字段隔离并可恢复 | B4 | planned / 待合同 | 待: OA/CRM/ERP真实回执, 重复/失败/补发合同测试 |
| A06 | 14-15 | 订单/订单行接收与物料匹配任务 | 订单导入生成PM待办, 在ERP完成匹配后回PMS确认并留来源; 不假设PMS已有ERP匹配算法 | B4 | planned / 待合同 | 待: 真实订单行, 任务下发/完成及错误物料案例 |
| A07 | 4,12-13 | 单元/产品线/单机的完整项目网络 | 按批准模板建适用阶段和结构, 多层下钻到任务, 每层业务类型和外部编码明确 | B2+B4 | planned / 待规则 | 待: 主计划到子项目展开案例, 模板差异与聚合对账 |
| A08 | 17 | 项目成员任命书 | 团队维护后生成受控任命书, 内容与当时团队快照一致, 再任命保留旧版 | B3 | implemented / local | Q; T: appointment-snapshot-matches-current-team-and-is-immutable, appointment-issues-are-controlled-and-isolated, appointment-http-contract-and-download; SQLite 13 tests/125 assertions + 浏览器1例通过 (签发/不可变版本/快照SHA/下载); 待: MySQL本轮回归未执行(本地无实例), 生产签章/外部文书模板 |
| A09 | 4,6 | 项目/计划/团队/文档/Gate模板与业务配置 | 模板按单元或机型版本化, 创建实例保留模板版本; 修改模板不追溯覆盖旧项目 | B2+B3 | planned | 待: 模板发布/实例化/版本升级用例 |
| A10 | 4,6 | 编码, 版本, 生命周期与对象收集规则 | 同类对象编码唯一, 版本规则明确, 关口按范围收集确定版本对象; 配置变更可审计 | B2+B3 | planned / 待规则 | 待: 编码碰撞/证据收集范围/配置版本用例 |
| A11 | 61; 边界见2 | 新产品/新技术/专题研发及部门事务项目 | 明确这些展示类别的独立流程/角色/交付物后配置, 覆盖立项到关闭; 不用订单模板假装替代研发内部流程 | B2-B5 | planned / 待规则 | 待: 缺失的研发内部业务规则与专项UAT清单 |

两级项目指合同主项目和业务单元子项目. machine 是交付/计划结构节点, 不是第三级组织项目. A02 的实现不能自动满足 A07 的完整模板网络, 也不能当作 A05 外部自动立项已完成.

## B. 生命周期, 计划与 Gate

| ID | PPT 页码 | 功能 | 业务闭环验收 | 批次 | 当前状态 | 验证证据 |
|---|---|---|---|---|---|---|
| B01 | 3-4,10 | 本地登记生命周期及取消 | draft->initiated->planning, 版本匹配; 禁止跳级, 取消需原因且终态只读; 缺少已批准基线或执行Gate时planning->execution返回409 | B1 | verified | V+U; T: lifecycle-and-audit |
| B02 | 3-5,10 | 全生命周期阶段与进度卷积 | 从设计准备到研发/齐套/装配/测试/发货/现场/SAT/收尾, 阶段/任务/项目状态分离, 按已批准权重逐级汇总可下钻 | B2+B4+B5 | partial / 待规则 | 本地execution/closing/closed及暂停恢复见F,X; 仍待多层权重卷积,全订单真实试点和完整网络对账 |
| B03 | 4-5,12-13,17-18 | 主/子/单机计划与WBS | 主计划分解到子计划和单机任务, 负责人/时间/前置关系/里程碑完整, 主子约束冲突可定位 | B2 | partial | P: wbs-isolation-and-cycle-guards,parallel-critical-path-summary-and-milestone; 已有项目内WBS, 尚未把任务映射成主/子/单机完整独立计划网络 |
| B04 | 17-18 | 主计划评审, 发布与同步 | 草稿提交后经批准形成不可变基线, 仅发布版同步ERP/CRM/研发计划; 部分失败可重试对账 | B2+B4 | partial / 待合同 | P: independent-approval-snapshot-and-revision; 本地基线冻结/独立批准, ERP/CRM/研发发布及回执仍待真实合同 |
| B05 | 17-18 | 项目启动会与行动项转计划 | 会议引用售前资料和主计划, 纪要形成行动项, 转任务保留来源且重复点击不重复建 | B3 | partial | Q: meeting-action-creates-one-real-task,meeting-can-reference-real-document-versions-as-pre-read-materials; 会议行动幂等生成真实任务; 会前资料已支持绑定项目内真实不可变文档版本(material_ids 0..50不重复, 非法404/400), 仍待把售前资料与主计划版本作为专门会前包与启动会强制关联及引用版本失效校验 |
| B06 | 4,19 | 需求确认Gate | 适用必检项和交付物版本齐备后提交, 有权评审人决定, 缺项/阻塞偏差不得通过 | B2+B3 | partial / 待规则 | Q: gate-uses-pinned-evidence-and-formal-waiver; 已有通用模板/证据/独立决定, 具体需求Gate必交规则及适用性待签收 |
| B07 | 20 | 工勘交付型任务 | 按项目适用性创建各次工勘, 责任/日期/交付物明确, 完成须证据; 不默认所有项目都强制三次 | B2+B3 | planned / 待规则 | 待: 不同项目适用模板和工勘交付验收 |
| B08 | 25 | DQ编制与确认关键任务 | 管理检查清单和确定版本交付件, 满足条件并签认才完成; 是否另设Gate由模板批准 | B2+B3 | planned / 待规则 | 待: DQ任务检查/确认/版本失效案例 |
| B09 | 26 | 主机汇总Gate | 按节点收集适用设计交付物, 逐项检查并形成评审结论, 汇总进展可下钻 | B2+B3 | planned | 待: 主机交付清单与批准快照 |
| B10 | 27 | 附件汇总Gate | 附件清单独立收集/验证, 缺件与豁免明确, 不用主机通过结果自动放行 | B2+B3 | planned | 待: 附件范围/缺交/豁免审批案例 |
| B11 | 4,29-30 | 零件齐套Gate | 以明确定义的齐套数据和允许例外作评审, 结论引用数据时点/工单/缺件处置 | B2+B4 | partial / 待规则 | X: material-freeze-and-kitting-enforce-real-prerequisites; 已有冻结BOM逐行齐套和缺件阻断, 企业齐套分母/替代料/工单数据源待定 |
| B12 | 4,36 | 装配与测试交接Gate | 装配执行/交检完成且测试条件齐备后交接, 接收责任人确认并保留例外 | B2+B4 | partial | X: full-delivery-requires-sat-and-independent-receipt; 已有装配开工/独立交检->试验前置, 详细交接项及部分接受策略仍待业务配置 |
| B13 | 4,39-40 | FAT确认Gate | FAT结果/报告和阻塞整改满足条件, 指定评审人确认后放行下游 | B2+B4 | partial | X: failed-test-creates-one-traceable-independent-remediation; 本地FAT准则/证据/独立批准及阻塞整改, 专用Gate模板待业务签收 |
| B14 | 4,46-47 | 项目交底Gate | 发货事实触发任务, 在配置期限内完成资料签交并将文件清单推CRM | B2+B4 | planned / 待合同 | 待: 发货到截止日计算, 版本签交/CRM回执 |
| B15 | 4,48-49 | SAT条件与SAT确认Gate | 现场任务和SAT执行证据齐备, 客户/质量确认及遗留问题安排完整后允许收尾 | B2+B4 | partial | X: full-delivery-requires-sat-and-independent-receipt; 实际接收和适用前序批准后SAT, 不冒称客户外部账号签章或CRM回执 |
| B16 | 4,51-52 | 项目/单机暂停与重启 | 记录范围和原状态, 冻结受影响任务, 恢复时校验条件与重排影响, 不误影响无关单机 | B2+B4 | partial / 待规则 | F: revoked-creator-and-paused-project-cannot-write; 整项目暂停/恢复已实现, 单机局部暂停和跨计划重排影响尚未实现 |
| B17 | 4,51-52 | 审批驱动项目/单机取消 | 已批准外部变更关联取消原因和受影响对象, 停止后续下发并完成外系统补偿/对账 | B2+B3+B4 | planned / 待合同 | 待: OA批准依据, 单机取消, 外发已执行补偿用例 |
| B18 | 4,28,51 | 计划/合同/客户交期变更与解冻 | 按来源发起, 记录前后值/需求/计划/成本影响, 审批后新基线, PMS/OA/CRM/ERP同步可对账 | B2+B3+B4+B5 | partial / 待合同 | P: execution-rebaseline-requires-approved-change; Q: change-review-lock-and-audit-rollback; 本地多维影响批准后关联新基线, 外部解冻/回执/成本联动仍待合同 |
| B19 | 4 | 日历与定时任务 | 工作日/节假日/时区版本明确, 计划计算/提醒/逾期/定时采集使用同一口径并可重试 | B2+B4 | partial / 待规则 | P: four-dependency-types-and-calendar; 已有工作日和例外日计算, 跨时区定时采集及提醒合同仍待完成 |

G1-G3 的业务编号映射尚未确认, 不在代码写死. 已核对总览中的 G4齐套/G5装配测试交接/G6 FAT确认/G7交底/G8 SAT确认可作为模板候选, 仍需业务签收适用范围. DQ在专页明确为关键任务, 不能因总览相邻排布断言其独立Gate编号. Testing/Delivery在V1用阶段, 任务和Gate表达, 不添加未经设计的同名项目状态.

## C. 需求, 文档, 协作, 问题与风险

| ID | PPT 页码 | 功能 | 业务闭环验收 | 批次 | 当前状态 | 验证证据 |
|---|---|---|---|---|---|---|
| C01 | 4,16 | URS条目手建/列表/分类 | 项目, 类别, 编号, 内容及必需/期望可维护, 项目内唯一, 修改形成版本 | B3 | implemented / local | Q: evidence-is-real-immutable-and-scoped; 手工登记和不可变URS修订, 具体字段见治理合同 |
| C02 | 16 | URS模板批量导入 | 整批预检并返回行级错误, 明确全部失败或部分成功策略, 重传不重复覆盖已批准版本 | B3 | implemented / local | Q: csv-preflight-and-import-are-all-or-nothing; 严格CSV表头,全行预检,整批事务及重复编号拒绝 |
| C03 | 4,16,61 | URS追踪矩阵与偏差校准 | 需求版本关联设计/测试/验证证据, 需求确认及设计完成时检查缺链和偏差, SIT/FAT/SAT可追踪 | B3+B4 | partial / 待规则 | Q: evidence-is-real-immutable-and-scoped,traceability-report-computes-per-version-link-gaps,workspace-traceability-reflects-real-requirement-traces; B: pms-c03.spec.js(界面登记需求/文档/追踪后打开URS与追踪页, 追踪矩阵逐需求显示设计满足数/验证证据数与缺链标签, 整链齐备与缺链汇总, 未追踪需求显示缺设计满足+缺验证证据, GET回显traceability/trace_summary); 已固定URS/任务/文档及试验链, 按最新版本自动检测缺设计满足/缺验证证据并汇总(修订产生新版本后须重新追踪), 覆盖率分母与SIT/FAT/SAT偏差级别仍待定义 |
| C04 | 4,58 | 按阶段/结构归集文档 | 立项/设计/DQ/制造/验证/过程文档分层管理, 文档编号/版本/状态/创建人/密级可追踪 | B3 | partial | Q: evidence-is-real-immutable-and-scoped,document-classification-stage-and-structure-are-traceable,document-collection-aggregates-latest-versions-only; B: pms-c04.spec.js(界面登记密级/阶段->台账显示密级与阶段列, 缺省内部, 非法密级400, 修订编号不可改),pms-c04b.spec.js(登记跨阶段/结构/密级文档->文档归集视图按最新版本聚合计数且修订不重复计, 密级过滤表格, GET回显document_collection); 实际UTF-8文本版本/SHA256/创建人可追踪, 密级(public/internal/confidential)/阶段/结构节点作为不可变归集字段随版本持久化并进入批量下载清单; 新增只读归集视图 document_collection 按每个编号最新版本聚合阶段/结构节点/密级并分层展示, 前端提供按密级客户端过滤; 二进制存储与多层下钻归集仍待实现 |
| C05 | 58 | 文档预览/下载/批量下载 | 每次访问和包内每文件检查权限/密级, 确定版本内容摘要一致, 无权附件不泄露 | B3 | partial | Q: authenticated-http-contract-and-isolation,document-batch-download-packages-authorized-versions-and-rejects-invalid; B: pms-c05.spec.js(界面批量下载触发ZIP并校验逐文件摘要); 单文本版本JWT预览/下载及授权已实现, 新增批量下载(1..50个同项目版本打包ZIP+MANIFEST逐文件SHA256校验, 跨项目/类型不符404, 无读取权403, 空/重复/超上限400); 密级过滤与二进制格式仍待实现 |
| C06 | 4,58 | 文档交付与审批归档 | 从编制到评审/发布/签发留版本轨迹, Gate引用不可变版本, 新版本不漂移旧决定 | B2+B3 | partial | Q: gate-uses-pinned-evidence-and-formal-waiver,document-release-requires-independent-approval-and-does-not-drift; B: pms-c06.spec.js(界面登记文档->提交发布选独立审核人->审核人批准发布->发布状态"已发布", 新修订回到"已登记"且旧批准版本不漂移); 已登记不可变证据并锁定Gate引用, 新增文档独立发布审批链(registered/rejected->in_review->approved/rejected, 复用reviewer!/decision-actor!职责分离, 提交与决定走latest!只能在最新版本推进, 批准记released_by, 新修订不漂移旧批准); 正式电子签章与外部文书模板仍待实现 |
| C07 | 4,17-18 | 会议管理与会后行动追踪 | 会前资料/参会人员/纪要, 行动项负责人和期限, 提醒/完成/转计划闭环 | B3 | partial | Q: meeting-action-creates-one-real-task,meeting-action-completion-verifies-independently-and-flags-overdue,meeting-can-reference-real-document-versions-as-pre-read-materials; B: pms-c07.spec.js(逾期标记->提交完成附证据与独立审批人->核验人独立关闭),pms-b05.spec.js(界面选择证据文档版本作为会前资料->读模型回显 material_ids->台账会前资料列计数->引用不存在/跨项目/非文档版本经真实HTTP 404, 未知字段400); 纪要/参与人/责任期限/任务来源/完成证据/独立核验/逾期计算已实现, 会前资料现可绑定项目内真实不可变文档版本(0..50不重复, 非法404/400, 留空[]), 自动到期提醒仍待补齐 |
| C08 | 4,37-40,48-49 | SIT/FAT/SAT偏差管理 | 分阶段记录发现/分级/责任/整改/验证/关闭, 阻塞偏差影响对应Gate, 复测保留历史 | B3+B4 | implemented / local | X: failed-test-creates-one-traceable-independent-remediation,actual-dates-and-test-sequence-cannot-be-fabricated; 本地准则失败自动blocker问题,复验留历史且独立关闭才放行 |
| C09 | 4,59 | 问题分类分级及处理关闭 | 提出人/责任人/严重度/目标日明确, 处理证据经验证再关闭, 超期可跟踪 | B3 | partial | Q: risk-becomes-one-issue-and-requires-independent-verification,closed-issue-reopens-only-through-independent-review,issue-reassign-changes-owner-with-audit-and-guards-membership; B: pms-c09.spec.js(界面转派->新责任人+原因留痕, 非成员/缺原因/越权/关闭后均被拒); 已有分级/期限/证据/验证/受控重开/责任人转派, 自动升级规则仍待实现 |
| C10 | 4,59 | 风险库, 预防措施与风险追踪 | 典型风险分类可复用, 识别后有责任/概率影响/措施/复审, 风险实现转问题保留关联 | B3 | partial / 待规则 | Q: risk-review-requires-evidence-and-future-followup; 已有评分/措施/复审到期/证据关闭/风险转问题, 典型风险库和自动升级规则仍待补齐 |
| C11 | 4,6-7 | 通知/预警/待办 | 项目事件产生授权收件人的待办或提醒, 可去重/已读/处理, 重试不重复通知且不暴露跨项目内容 | B2-B4 | planned / 待合同 | 待: 到期提醒/通知失败/撤权/外部消息回执 |

## D. 材料申请, 齐套与采购追踪

| ID | PPT 页码 | 功能 | 业务闭环验收 | 批次 | 当前状态 | 验证证据 |
|---|---|---|---|---|---|---|
| D01 | 21-22 | 原材料预投申请 | PMS任务进入申请, OA审批回写, 采购需求/计划关联, 供应商日期和采购进度可追溯 | B4 | partial / 待合同 | X: material-freeze-and-kitting-enforce-real-prerequisites; 本地raw_material申请与独立批准, OA/采购/供应商真实回执未接入 |
| D02 | 23-24 | 长周期物料预投 | 申请关联项目/单机/物料和交期, 审批后采购追踪, 延误提示并有责任处理 | B4 | partial / 待合同 | X: material-freeze-and-kitting-enforce-real-prerequisites; 本地long_lead申请/需用日期/冻结BOM, 采购交货与供应商进度仍待接口 |
| D03 | 31-32 | 包材申请 | 任务通知到填写/提交/审批状态/详情, 交付结果回到来源任务 | B4 | planned / 待规则 | 待: 包材字段/流程确认和申请完成用例 |
| D04 | 33,35 | 直发层流罩申请 | 由对应任务发起, OA审批与采购需求/订单关联, 状态/失败清楚回写 | B4 | planned / 待合同 | 待: 指定类别直发审批及采购回执 |
| D05 | 34-35 | 非层流罩直发申请 | 按物料编码关联采购订单, 检查既有发运工单, 合法时协调地址/采购发货状态/装配要求 | B4 | planned / 待合同 | 待: 无订单/已有工单/已发运冲突及多目标同步补偿 |
| D06 | 4,29-30 | 齐套率多层查看与缺件下钻 | 项目/单元/产品线/单机到计划/生产工单可逐层核对分子分母, 缺件清单可定位责任与交期 | B4 | partial / 待规则 | X: material-freeze-and-kitting-enforce-real-prerequisites; 已按冻结BOM齐套行数计算并展示缺件数量, 未完成主/子/单机多层卷积或替代料口径 |
| D07 | 3-4,7,10,21-24 | 采购分类追踪与供应商进度 | 长周期/关键件/外协/自制/采购/原材关注项与源工单/订单关联, 承诺和实际日期分离 | B4 | planned / 待合同 | 待: 各来源样例, 延期回写, 陈旧数据提示 |

## E. 装配, 测试, 发运, 现场与收尾

| ID | PPT 页码 | 功能 | 业务闭环验收 | 批次 | 当前状态 | 验证证据 |
|---|---|---|---|---|---|---|
| E01 | 3-4,7,36 | 装配任务下发与执行看板 | 下发批准计划, 跟踪上岛/装配/单机交检/连线交检/下岛/交接, 来源和更新时间清晰 | B4 | partial / 待合同 | X: full-delivery-requires-sat-and-independent-receipt; 本地装配开工/返工/独立交检, 上岛/连线/下岛明细及MES工单合同尚缺 |
| E02 | 37-38 | SIT计划/执行/报告/整改 | 查看测试详情与结果, 报告和整改可追溯到单机, 整改验证后完成 | B4 | partial / 待合同 | X: failed-test-creates-one-traceable-independent-remediation; 已有本地SIT准则/结果/整改/独立检验, 单机映射及MES真实回传未接入 |
| E03 | 39-40 | FAT计划/启动会/执行/报告/总结/整改 | FAT任务协同会议, 每次执行有证据, 总结行动有责任, 阻塞整改关闭后申请确认 | B3+B4 | partial / 待合同 | X: actual-dates-and-test-sequence-cannot-be-fabricated; FAT在适用SIT批准后执行, 整改/复验/检验可追踪, FAT专用启动会/总结包及MES合同待补 |
| E04 | 41-42 | 外向交货申请与发货前条件 | 从PMS发起OA流程, 核对适用FAT整改/入库/提货款条件, 回执完成任务并更新项目视图 | B4+B5 | partial / 待规则 | X: renewed-inspection-invalidates-previous-shipping-qualification; 已有本地发运独立放行和适用试验/阻塞问题检查, OA/入库/提货款条件未冒充完成 |
| E05 | 41,43 | 入库/装箱/装车/缺件与发货追踪 | 按项目和外部物料键定期采集清单及缺件状态, 展示版本/来源/截止时点, MES发货事实可追溯 | B4 | partial / 待合同 | X: actual-dates-and-test-sequence-cannot-be-fabricated; 已记录真实日期/物流号/版本证据/内部独立签收, 外部入库装箱清单/缺件补发及定时采集未接入 |
| E06 | 44-45 | 国际项目出口申请与交付物 | 国际项目触发出口表及审批资料, 国内项目不误触发, 审批结果和交付物回到项目 | B4 | planned / 待合同 | 待: 国际/国内适用性和OA审批闭环 |
| E07 | 46-47 | 发货后交底时限与文档同步 | 发货后在配置的2天期限内完成交底, 检查清单版本确认, 文件清单推CRM并留回执 | B4 | planned / 待规则 | 待: 截止期日历确认, 逾期/补交/回执用例 |
| E08 | 48-49 | 售后工单与现场任务下发 | 交底完成后按配置2天滞后启动定位/安装/调试/SAT任务, PMS下ERP并关联现场责任 | B4 | planned / 待规则 | 待: 滞后与截止期区别, 前置未完成/重排/下发重试 |
| E09 | 48-49 | 现场定位/安装/调试/SAT进度 | CRM回传各任务实际完成时间, 对应PMS任务更新, 乱序/重复不倒退, 现场验收证据可查 | B4 | partial / 待合同 | X: full-delivery-requires-sat-and-independent-receipt; 本地SAT实际结果/前序/证据已实现, 现场定位安装任务和CRM乱序回传仍待完成 |
| E10 | 50 | 项目收尾阶段 | SAT等前提满足后创建并关闭收尾活动, 交付物/遗留事项/责任清楚, 此阶段不下发ERP | B5 | partial / 待规则 | F: real-lifecycle-requires-all-evidence-and-independent-close; 本地清单/移交/签核/关闭已实现并调用交付阻塞, 完整业务模板仍待UAT |

交底的发货后2天是完成截止期, 现场任务的交底后2天是开始滞后期. 日历口径尚待确认, 不将二者合并成一个日期字段.

## F. 四算, 工时与经营分析

| ID | PPT 页码 | 功能 | 业务闭环验收 | 批次 | 当前状态 | 验证证据 |
|---|---|---|---|---|---|---|
| F01 | 3-5,53-55 | 项目概算获取与展示 | 按项目/产品线接收概算版本, 保留来源/币种/口径, 明细到汇总一致 | B5 | partial / 待合同 | F: costs-are-immutable-and-review-is-independent; 本地estimate版本与来源标识已实现, CRM概算未接入 |
| F02 | 3-5,53-55 | 项目预算获取与展示 | 已批准预算与概算分开, 版本/人工材料制造等类别明确, 变更不覆盖旧预算 | B5 | partial / 待规则 | F: costs-are-immutable-and-review-is-independent; 本地budget独立版本/批准/修订已实现, 企业预算科目及ERP合同待确认 |
| F03 | 3-4,53-56 | 售前/制造/现场等实际成本核算 | 源账项幂等接收, 项目和费用分类明确, 现场成本按ERP结果汇总, 调整有冲销或修订链 | B5 | partial / 待合同 | F: costs-are-immutable-and-review-is-independent; 本地actual版本及来源去重/调整链已实现, 原系统账项及财务对账未接入 |
| F04 | 4,53 | 研发工时报工与审核 | 人员/项目/期间/任务工时可提交审核, 撤销更正有版本, 已批准工时才参与正式分摊 | B4 | partial / 待规则 | F: day-cap-is-global-and-rejection-releases-reservation; 真实任务工时/指定独立审核/跨项目日上限已实现, 批准后更正和封期尚缺 |
| F05 | 53-56 | 研发费用按工时分摊 | 冻结费用池和工时, 按批准算法分摊, 舍入守恒, 零工时待处理, 直接人工不重计 | B5 | partial / 待规则 | F: allocation-is-idempotent-and-conserves-real-costs,exact-money-and-conservation; 单项目池按批准任务工时冻结分摊且守恒, 跨项目研发池和直接人工口径仍待财务确认 |
| F06 | 53-56 | 项目决算与四算拉通 | 结项时按收入/累计回款/开票等已批准口径形成决算版本, 可回查四算差异和来源 | B5 | partial / 待规则 | F: costs-are-immutable-and-review-is-independent; 本地settlement版本已实现, 权威收入/回款/开票封账口径仍待合同 |
| F07 | 4,7,54,57 | 变更损失 | 从经确认的分析来源接收损失数量/金额并关联变更, 可分产品线汇总, 不与成本账项重复计费 | B5 | partial / 待合同 | 本地成本分类支持change_loss, 但尚无BI损失入站和正式变更逐项关联算法, 不能视为损失分析全闭环 |
| F08 | 54-57 | 成本/毛利/净利和四算对比看板 | 明示期间/币种/税额/公式和版本, 零收入显示不可算, 汇总可下钻并仅财务授权可见 | B5 | partial / 待规则 | F: zero-revenue-profit-and-financial-audit-privacy; 同期间同币种四算和收入减成本已实现, 不做隐式换汇/税后净利/跨产品线完整经营分析 |
| F09 | 4-5,61 | 季度经营目标达成看板 | 目标来源/版本/周期和达成口径明确, 实际值可复算, 目标修订不改写历史 | B5 | planned / 待规则 | 待: 季度目标与事实数据来源, 汇总对账和权限验收 |

PPT中的财务表是设计示意. 字体较小或仅在原图显示而未核定的细项不作为已确认字段合同. F01-F09 均未接入真实企业财务数据. 本轮是有持久化和独立审批的本地账目子集; 五种币种可分别记录, 不能据此声称完成汇率换算, 多币种合并或完整经营分析.

## G. 看板, 基础能力与八系统集成

| ID | PPT 页码 | 功能 | 业务闭环验收 | 批次 | 当前状态 | 验证证据 |
|---|---|---|---|---|---|---|
| G01 | 4-5,60 | 基础项目驾驶舱 | 当前授权范围内total/active/overdue/draft与真实列表数据一致, 搜索/状态/详情URL可恢复 | B1 | verified | V+U; T: data-isolation-and-members |
| G02 | 4-5,60 | 完整多/单项目看板与渗透跟踪 | 从项目组合下钻主/子/单机到任务/证据, 展示进度/偏差/交付/齐套/测试/成本及数据时点 | B2-B5 | partial | 本地规划/治理/交付/财务工作台可追踪同项目对象, 主子单机跨层指标卷积和完整组合经营看板仍待实现 |
| G03 | 4-5,26-27,29-30,36-40 | 专项过程看板 | 主机/附件, 原材预投, 齐套, 装配, SIT/FAT, 偏差及交付物各视图可查同一事实并下钻 | B3-B5 | partial | X,Q: 本地齐套/装配/试验/问题/文档视图已存在, 原图所有专项过程看板及外部事实仍未齐备 |
| G04 | 4,6 | 本地身份, 功能权限与项目数据隔离 | 实时读取有效用户/权限, 未登录401/无权403, 列表详情统计同范围, 停用/撤权后新请求拒绝 | B1 | verified | V; T: rest-contract-and-validation, data-isolation-and-members, disabled-user-and-revoked-permission |
| G05 | 4,6 | 动态菜单接入 | 由模板权限菜单进入项目中心/驾驶舱, 操作同时经后端权限校验, 不硬编码全员可见 | B1 | verified | V+U; PMS菜单迁移往返和授权检查 |
| G06 | 6 | 本地业务审计与并发保护 | 每次聚合写入和事件同事务, 审计失败回滚, 聚合版本排序, 旧版本/并发冲突409, 未知异常安全500 | B1 | verified | V; T: audit-failure-rolls-back-entire-creation, lifecycle-and-audit, simultaneous-updates-have-one-winner, unexpected-errors-do-not-expose-internals |
| G07 | 4,7,11,17,28,46-49 | CRM适配器 | 对接订单/概算/交期/现场事实并接收发布计划和交底引用, 外部键/版本/回执可对账 | B4+B5 | planned / 待合同 | 待: 真实CRM合同与沙箱闭环, 幂等/乱序测试 |
| G08 | 4,7,11,21-24,28,31-35,41-45,51 | OA适配器与组织同步 | 组织/人员及各申请审批同步, PMS发起有业务键, OA真实结论才改变审批投影 | B4 | planned / 待合同 | 待: OA身份及审批回执, 停用人员和拒绝/撤回处理 |
| G09 | 4,7,14-15,17,21,41,47-49,53 | ERP/SAP适配器 | 外部项目结构/物料/采购/成本入站, 批准计划/现场任务出站, 字段权威矩阵明确 | B4+B5 | planned / 待合同 | 待: ERP沙箱, 项目结构/物料匹配/计划/财务各合同 |
| G10 | 4,7 | PLM适配器 | 设计任务下发, 进度反馈及交付物归档引用带版本, PMS不得覆盖PLM权威文件 | B4 | planned / 待合同 | 待: 设计任务到受控交付件真实往返 |
| G11 | 4,7,36-40,43 | MES适配器 | 接收批准的装配/测试任务, 回传工单/齐套/装配/测试/相关发货事实, 重试不重复下发 | B4 | planned / 待合同 | 待: MES工单/测试结构映射和执行回传 |
| G12 | 4,7,21-24,33-35 | SRM适配器 | 供应商承诺/交付进度与采购订单关联, 直发调整协调, 失败可补偿和人工处理 | B4 | planned / 待合同 | 待: SRM订单/日期/直发状态合同与对账 |
| G13 | 4-5,7,41-43 | 销服物料系统适配器 | 装箱/装车/缺件/相关入库信息回传并保留清单版本, 与MES发货来源冲突可解释 | B4 | planned / 待合同 | 待: 真实清单/缺件补发/每日更新与字段权威确认 |
| G14 | 4-5,7,54,57 | BI适配器 | 经确认的变更损失/分析数据入站, 授权指标按约定出站, 模型/口径和取数时点可追溯 | B5 | planned / 待合同 | 待: BI指标合同, 数据来源和财务对账 |
| G15 | 4,6-7 | API管理, 消息通知与集成运维 | 入出站认证/版本/限流, inbox/outbox去重, 重试/死信/重放和对账; 运维动作有权限审计 | B4+B7 | partial / 待合同 | O: inbox-is-monotonic-and-collisions-do-not-overwrite,mismatched-receipt-backoff-dead-letter-and-replay; 通用协议/重试/死信基础已有, 八套企业系统的认证映射及业务回执仍待真实验证 |
| G16 | 4,6 | 报表, 全局检索与文件预览等平台能力 | 报表指标定义/导出范围一致, 检索/预览先按项目和密级过滤, 结果可回到确定对象版本 | B3-B5 | planned | 待: 授权搜索/导出/预览及来源回链用例 |
| G17 | 6,8-9 | 私有部署, 存储/缓存/检索/对象存储/消息与运维 | 以当前模块化单体架构满足业务持久化/监控/恢复/隔离要求, 必要组件可部署验证, 不照搬参考微服务或集群数量 | B7 | planned | 待: 生产部署/容量/恢复验收; 本地构建通过仅见V |
| G18 | 4 | 客户端兼容性 | 在批准的桌面系统/浏览器矩阵验证核心操作, 兼容基线按实际AntD6/依赖确定, 不由旧示意版本推定支持 | B7 | planned / 待规则 | 待: 兼容矩阵和逐环境结果; V仅证明本地Chrome |

第7页的连接图可确认业务事件类别, 但其Excel图标未含可读取的完整接口字段附件. 不能把图中连接数当成已完成API数. 每个适配器须有真实测试环境记录后才可标 verified. 模板已有用户/BPM/日志等通用模块不等于PMS已接入相应业务审批或外部系统.

## H. 标准 PMS 工程闭环补全

本节为在原蓝图基础上的新增或强化验收项, 不伪称原PPT逐条已提出, 不指向未经验证的PPT页码. 与前述功能重叠时强调原图未给完整规则的闭环, 不重复计算交付数量.

| ID | 来源类型 | 新增/强化功能 | 业务闭环验收 | 批次 | 当前状态 | 验证证据 |
|---|---|---|---|---|---|---|
| H01 | 工程基线新增 | 项目章程, 正式批准与业务目标 | 记录目标/范围/成功标准/赞助人/授权PM/初始预算, 审批拒绝可修订, 批准后冻结章程版本 | B2 | partial | Q: charter-review-is-independent-and-versioned,charter-initial-budget-is-validated-and-versioned; B: pms-h01.spec.js(界面登记章程填金额并选币种->规范化回显"88.90 USD"->修订未选币种缺省"120000.50 CNY"->再修订取消预算显示"未设定", 台账三不可变版本预算不漂移, 真实HTTP超两位小数金额与非法币种400); 目标/范围/成功准则/赞助人和独立批准已实现, 新增可选初始预算(initial_budget 非负且规范化为两位小数, budget_currency 限 CNY/USD/EUR/GBP/HKD 缺省 CNY)随内容版本不可变冻结, 非法金额/负数/非法币种返回400, 修订不漂移旧值, 预算为章程专属字段变更体拒绝; 待补齐: 授权PM作为章程显式字段(现由项目 manager_id 与编辑权限隐含承载), 以及初始预算与批准后财务基线的正式对账关联 |
| H02 | 工程基线新增 | 干系人, RACI与沟通计划 | 识别利益相关者及职责, 明确知会/参与/批准关系, 沟通节奏可执行并有调整记录 | B2+B3 | implemented / local | Q: stakeholder-raci-conflict-and-comm-plan-loop,stakeholder-comm-plan-http-contract; 干系人登记+不可变修订(编号不可改), RACI按活动至多一个A且不得重复指派并输出缺A/缺R冲突, 沟通计划受控修订+由最新版本生成会议并回写last_meeting_id形成闭环; 经复用通用治理存储pms_gov_record(stakeholder/raci/comm-plan)与工作台"干系人与沟通"页签; 浏览器端到端(pms-h02.spec.js)已验证界面登记干系人, RACI缺A冲突提示与沟通计划生成会议回写; 待: MySQL本轮未执行(本地无实例), 外部通知/消息渠道自动提醒与按节奏定时派发未接通 |
| H03 | 工程基线强化 | 范围基线与WBS字典 | 可交付范围/排除项/验收准则映射到WBS叶节点, 覆盖性审查后冻结, 范围变更受控 | B2+B3 | partial | P: independent-approval-snapshot-and-revision; WBS设计冻结与变更已实现, 范围排除项和覆盖性审查尚未完整实现 |
| H04 | 工程基线强化 | 排程与关键路径 | 依赖/工作日历/持续时间计算一致, 检测循环, 展示关键路径/浮动和基线偏差, 重排不覆盖原承诺 | B2 | implemented / local | P: four-dependency-types-and-calendar,parallel-critical-path-summary-and-milestone; 明确四类依赖/工作日/环/浮动/关键路径/不可变基线 |
| H05 | 工程基线新增 | 资源容量, 技能与分派 | 资源需求到人员/角色/技能分派, 日历容量与任务投入匹配, 超配冲突提示并经协调解决 | B2 | partial | P: shared-person-capacity-preserves-other-project-privacy; 日容量和跨项目同人员超配保护已有, 技能匹配/替代人员/请假规则仍待补齐 |
| H06 | 工程基线强化 | 执行进展与预测 | 状态日期统一, 责任人提交完成量/实际时间/剩余估算, 审核后更新预测, 保留历史趋势和偏差措施 | B2+B4 | partial | P: rejection-and-progress-do-not-rewrite-baseline; 执行反馈/剩余工期/不可回退已有, 反馈独立审核/动态完工预测和完整趋势措施仍待补齐 |
| H07 | 工程基线新增 | 工时与资源成本闭环 | 工时审批关联任务及费率有效期, 拒绝/更正/封期可控, 工时不能双计入成本 | B4+B5 | partial | F: allocation-is-idempotent-and-conserves-real-costs; 工时批准到费用池分摊已有, 人员费率有效期/追溯更正/封期尚缺 |
| H08 | 工程基线强化 | 风险和问题的复审/升级/重开 | 风险评分口径和复审频率明确, 超阈值升级, 问题有验证人/关闭证据且可受控重开 | B3 | partial | Q: closed-issue-reopens-only-through-independent-review,risk-review-requires-evidence-and-future-followup; 独立重开/复审/证据关闭已有, 超阈值自动升级未实现 |
| H09 | 工程基线强化 | 变更控制委员会与影响决策 | 以B2计划基线为依赖, 对范围/工期/成本/质量/资源做影响分析, 有权批准或拒绝, 批准后同步基线和相关责任人 | B3-B5 | partial | Q: change-review-lock-and-audit-rollback; P: execution-rebaseline-requires-approved-change; 多维影响及独立批准关联执行期新基线已有, CCB多人表决/跨系统通知和财务自动应用未实现 |
| H10 | 工程基线强化 | 质量计划与验收准则 | 可交付物预先定义质量准则/方法/角色/证据, 检查不通过产生整改, 复验到签收可追溯 | B2+B3+B4 | partial | X: failed-test-creates-one-traceable-independent-remediation; 明确试验准则/证据/角色/失败整改已有, 全项目质量计划和企业适用模板仍待签收 |
| H11 | 工程基线新增 | 正式交付, 客户签收与遗留项 | 签发受控交付包, 记录接收/拒收/条件接受, 遗留项有责任/期限, 签收与项目关闭分离 | B3+B4 | partial | X: conditional-receipt-forces-service-resolution-before-acceptance; 接受/拒收/条件接受与售后独立关闭已实现, 受控交付包发布及客户外部签章未实现 |
| H12 | 工程基线新增 | 承诺成本, 预测完工成本与预算控制 | 已批准预算, 已承诺未发生费用, 实际和剩余预测区分; 阈值超支进入批准/调整流程 | B5 | planned | 待: 采购承诺到实际转化不双计/超支审批样例 |
| H13 | 工程基线强化 | 财务期间, 多币种与结算 | 明确税额/收入确认/汇率/封期, 开票/回款/付款/决算按授权源对账, 未结事项有后续责任 | B5 | planned | 待: 财务批准算法, 多币种/零收入/封期调整/结算对账 |
| H14 | 工程基线新增 | 收尾批准与项目归档 | 验收/成本/合同/未结问题/资料完整检查后正式关闭, 归档只读, 重开需批准并保留原关闭记录 | B5 | partial | F: approved-closure-snapshot-cannot-be-reused-after-change,closed-project-reopening-requires-new-independent-close; 已有快照/独立关闭/只读/受控重开, 全目标范围仍待UAT |
| H15 | 工程基线新增 | 经验教训与模板反馈 | 复盘问题/成功做法形成可检索知识, 标记适用场景和责任人, 经批准反馈模板或风险库 | B5 | partial | 本地收尾清单/遗留移交/经验记录已实现, 跨项目知识发布检索及经批准反馈模板/风险库尚缺 |
| H16 | 工程基线新增 | 项目成员撤销与权限生命周期 | 加入/转岗/离开/委派到期/归档权限完整处理, 撤权后新请求和证据链接不可越权, 保留历史署名 | B2+B3+B5 | partial | F: revoked-creator-and-paused-project-cannot-write,pending-financial-reviewer-cannot-be-removed; 本地撤销即时失权并保护待办职责, 自动到期/外部离职同步尚缺 |
| H17 | 工程基线强化 | 精细职责分离和受限字段 | PM/审批人/质量/财务/外部参与者的节点及字段范围明确, 不能自审或通过赋权越权批准 | B2+B3+B5 | partial | Q,F,X: 指定非提交人审批/项目范围/财务权限后端强制, 节点级字段密级及外部参与者完整角色矩阵仍待实现 |
| H18 | 工程基线新增 | 草稿清理与资料保留策略 | 不把取消当删除, 草稿清理需权限/引用校验, 正式历史按保留策略归档, 删除/恢复行为可审计 | B5+B7 | planned | 待: 有引用拒删/保留期/合法归档恢复用例 |
| H19 | 工程基线强化 | 非功能与运行就绪 | MySQL目标环境, 迁移升级/回退, 压测, 监控告警, 备份恢复与RPO/RTO演练通过 | B7 | partial | V: 全量PMS双库测试/SQLite迁移往返/生产构建与独立启动已通过; 生产目标环境升级/回退/容量/备份恢复与RPO/RTO演练仍待完成 |
| H20 | 工程基线新增 | UAT, 数据迁移与上线切换 | 业务代表按完整订单闭环签收, 历史数据迁移对账, 切换/回退/培训与支持责任明确 | B7 | planned | 待: 脱敏/授权真实试点, UAT签收和切换演练 |
| H21 | 项目附加设计 | AI问答, 摘要, 行动与变更建议 | 授权检索, 事实有确定版本证据, 写入仅生成待确认proposal并仍走业务权限/审批, 模型故障不影响核心业务 | B6 | planned | 待: [AI设计](09-ai-agent-design.md)的证据/越权/注入/重复确认评估, 不以聊天演示代替 |

## 本轮交付边界与剩余依赖

本轮已形成可运行的本地工业订单主链: 章程独立批准 -> WBS/资源/独立基线与Gate -> URS和真实文本版本 -> 冻结BOM及齐套 -> 装配交检 -> 适用SIT/FAT -> 发运与内部独立签收验证 -> SAT -> 工时/成本/正式收尾与受控重开. 当前主链是工程验收基线, 不等于本表每一条原PPT能力均已完成. 实际日期不可未来, 签收不早于发运, 前序试验的新版本会使旧下游资格失效.

仍需真正完成的范围包括:

- 真实外部接口: A04-A06, B04/B14/B17, D/E中的OA/采购/MES/现场回传, F的权威账项, G07-G14八套适配器. 需字段/认证/事件版本/权威来源/责任人/沙箱和对账样例. 通用HTTP运行时的本地协议测试不替代这些合同.
- 完整项目网络与业务模板: A07-A11, B02-B03/B07-B10/B16, 具体Gate适用编号和主子单机计划映射/进度卷积/局部暂停. 当前machine仍是结构节点, 不伪称第三级组织项目.
- 原图专项执行: 包材/分类型直发, 供应商采购进度, 上岛/连线/下岛, 国际出口, 交底截止期与现场滞后期. 齐套当前为整数计件的冻结BOM行数比例, 替代料/分批跨工单口径仍待确认.
- 平台与标准闭环扩展: 文档二进制/批量/密级及正式发布, RACI/技能/沟通计划, 完整追踪缺口与覆盖率, 自动提醒升级, 经验发布复用, 保留期策略.
- 财务与经营: 跨项目研发池, 有效期费率, 批准工时冲销/封期, 税额/收入确认/汇率/合并经营, 权威回款开票付款及季度目标. 当前同期间同币种比较和毛利金额不替代这些能力.
- AI仍是设计, 没有以聊天或规则替代真实模型证据评估. 新模块MySQL CI已通过, 生产迁移恢复, 容量, 正式UAT和上线切换仍须实际报告; 只采用 [验证记录](verification.md) 的最终结果.

## 澄清清单与放行边界

| 澄清项 | 已有依据 | 尚需决定/提供 | 影响行 |
|---|---|---|---|
| Gate编号与适用模板 | 需求/汇总/齐套/交接/FAT/交底/SAT有展示, DQ为关键任务 | G1-G3映射, 产品差异, 审批人/代理/豁免及证据失效规则 | A09-A10, B06-B15 |
| 项目网络与研发范围 | 两级项目/三类计划明确, 展示研发项目类别 | 单元与产品线编码映射, 子项目独立批准边界, 研发内部另文的具体流程 | A07,A11,B03 |
| 计划计算 | 有计划/前置任务/时间/进度与卷积概念 | 权重, 关键路径, 工作日历, 时间基准, 自动排程和重排策略 | B02-B04,B19,H04-H06 |
| 交底与现场2天 | 一个为截止期, 一个为开始滞后 | 自然日或工作日, 时区, 起算事件, 例外授权 | E07-E09 |
| URS追踪与偏差 | 条目/版本/设计及测试追踪有示意 | 完整追踪列定义, 验证方法, 覆盖率分母, 阻塞偏差级别 | C01-C03,C08 |
| 齐套与直发 | 多层齐套和两类直发流程已展示 | 计数分母, 替代料/分批齐套, 已发运冲突, 不同来源优先级 | D04-D07,E04-E05 |
| 财务 | 四算, 工时分摊, 毛利净利及变更损失有示意 | 字段合同, 税额/收入/汇率/期间, 费用池, 分摊/舍入/封期和净利公式 | F01-F09,H07,H12-H13 |
| 外部接口 | 可确认8系统角色及事件类别 | 真实接口清单附件, 字段/版本/认证/沙箱, 各字段owner, 重试补偿和对账服务等级 | A04-A06,G07-G15及相应业务行 |
| 组织与数据授权 | 基本团队和权限平台有展示 | 外协/客户参与方式, BU节点授权, 文档密级, 财务范围, 撤权与历史访问规则 | C05,G04,H16-H18 |
| 部署与兼容 | 原图示意私有部署/容器与旧客户端基线 | 本项目真实规模/可用性/浏览器矩阵, 数据保留, 上线环境和运维责任 | G17-G18,H19-H20 |

以上澄清项不取消任何已列目标功能. 可先完成不依赖外部合同的领域/界面/确定性测试, 但依赖未满足的整行保持 planned或partial, 不以推测默认值或mock返回推进为 verified. 目标1和目标2都需要逐行关闭验收证据; 当前已经交付的本地主链仍不是全部目标完成.
