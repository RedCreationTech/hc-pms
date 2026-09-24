// 红创PMS 完整流程演示视频的分镜与字幕 (唯一数据源).
// 录屏用例 tests/e2e/pms-demo-video.spec.js 按 shot id 取字幕, 章节卡/背景渲染与成片合成也读这里,
// docs/pms/15-demo-video-storyboard.md 由 storyboard_md.js 从本文件生成, 避免三处文案漂移.
// 字幕: 每条一行, 不超过 32 个字符, 屏幕停留时间 = max(2.8s, 字数 / 6.5 字每秒), 由录屏用例自动保证.

const title = {
  heading: '红创PMS 订单项目管理系统',
  subheading: '从平台模板到正式关闭 · 完整业务流程演示',
  meta: '同一个设备订单项目 · 真实系统录屏 · 本地闭环',
};

const ending = {
  heading: '本地业务闭环演示完成',
  points: [
    '外部系统 (OA / CRM / ERP / MES 等) 未接入, 接口运维如实显示未配置',
    '待业务批准的口径 (Gate 编号, 编码规则, 权重, 费率) 使用工程默认值',
    '剧本: docs/pms/14-demo-script.md  ·  矩阵: docs/pms/11-feature-acceptance-matrix.md',
  ],
};

// short 用于左侧章节导航栏 (不超过 6 个字).
const chapters = [
  { no: '01', title: '平台配置', short: '平台配置', subtitle: '模板与规则', points: ['项目模板按版本发布', '编码规则 / 研发费用池 / 工时封期'] },
  { no: '02', title: '立项与项目网络', short: '立项建网', subtitle: '创建项目 · 应用模板 · 团队任命', points: ['一键生成主项目 / 子项目 / 单机结构', '8 个 Gate 模板与收尾清单'] },
  { no: '03', title: '需求与治理', short: '需求治理', subtitle: '章程 · URS · 证据文件 · 干系人 · DQ', points: ['提交人不能自审, 独立审核人批准', '证据文件按 SHA256 固化'] },
  { no: '04', title: '计划编制与基线', short: '计划基线', subtitle: '派生主 / 子 / 单机计划 · 冲突 · 权重 · 基线', points: ['按模板阶段层级自动派生', '主子约束冲突自动定位'] },
  { no: '05', title: '进入执行', short: '进入执行', subtitle: '交付要求 · 执行准入 · 启动会 · 风险', points: ['章程 / 基线 / Gate 全部满足才能执行'] },
  { no: '06', title: '备料齐套与装配', short: '齐套装配', subtitle: '备料 · BOM · 齐套 Gate · 装配步骤 · 交接', points: ['齐套 Gate 未通过时阻断装配开工', '交接检查项可例外放行并留痕'] },
  { no: '07', title: '质量试验与发运', short: '试验发运', subtitle: 'SIT · FAT · 发货前条件 · 放行 · 签收', points: ['发货前须入库确认', '客户签收由独立账号验证'] },
  { no: '08', title: '交底与现场', short: '交底现场', subtitle: '交底 · 现场任务 · 工勘 · SAT', points: ['交底完成自动下发四项现场任务'] },
  { no: '09', title: '进度监控与挣值', short: '进度挣值', subtitle: '实际日期 · 工时 · 定时扫描 · 挣值 · 提醒', points: ['PV / EV / AC, SPI / CPI 与完工预测', '逾期对象自动进入我的待办'] },
  { no: '10', title: '财务与看板', short: '财务看板', subtitle: '四算拉通 · 过程看板 · 组合看板 · 检索', points: ['概算 / 预算 / 核算 / 决算独立审批'] },
  { no: '11', title: '收尾与关闭', short: '收尾关闭', subtitle: 'SAT 确认 · 收尾清单 · 关闭审批 · 归档', points: ['结项前置检查全部通过才能关闭', '关闭后只读, 重开需独立批准'] },
];

// who: admin = 项目经理 (admin), reviewer = 独立审核人. screen/action 仅用于分镜文档.
const shots = [
  { id: '01-1', chapter: '01', who: 'admin', screen: '项目管理 > 模板与规则', action: '浏览内置模板目录与已发布的单机设备订单模板, 切换编码规则页签',
    captions: ['平台在"模板与规则"维护可复用的项目模板', '单机设备订单模板: 8 个阶段, 三层结构, 8 个 Gate', '模板按版本发布, 已建项目保留当时的快照', '编码规则, 研发费用池与工时封期也在这里配置'] },

  { id: '02-1', chapter: '02', who: 'admin', screen: '项目管理 > 项目中心 > 创建项目', action: '逐字输入编号, 名称, 部门与计划起止日期后保存',
    captions: ['项目经理在"项目中心"新建设备订单项目', '填写编号, 名称, 部门与计划起止日期'] },
  { id: '02-2', chapter: '02', who: 'admin', screen: '项目详情 > 项目概况 > 应用项目模板', action: '选择单机设备订单项目模板并应用, 查看结构树',
    captions: ['一键应用"单机设备订单项目"模板', '自动生成主项目, 主机单元, 主机#1 与附件单元', '同时生成 8 个 Gate 模板, 计划容器与收尾清单'] },
  { id: '02-3', chapter: '02', who: 'admin', screen: '项目概况 > 项目团队; 需求与治理 > 成员任命', action: '把独立审核人加入团队, 签发成员任命书',
    captions: ['维护项目团队, 加入独立审核人', '签发成员任命书, 团队快照以 SHA256 固化'] },

  { id: '03-1', chapter: '03', who: 'admin', screen: '需求与治理 > 章程', action: '编制项目章程并提交独立审批',
    captions: ['编制项目章程: 目标, 范围与成功标准', '提交给独立审核人, 提交人不能自己批准'] },
  { id: '03-2', chapter: '03', who: 'reviewer', screen: '审核人账号 > 需求与治理 > 章程', action: '审核人填写意见并批准章程',
    captions: ['独立审核人在自己的账号中批准章程'] },
  { id: '03-3', chapter: '03', who: 'admin', screen: '需求与治理 > URS与追踪', action: '新增 URS 需求并选择验证方式, 查看覆盖度',
    captions: ['登记 URS 用户需求并声明验证方式', '验证方式覆盖度随需求实时汇总'] },
  { id: '03-4', chapter: '03', who: 'admin', screen: '需求与治理 > 证据版本', action: '上传真实 PDF (机密, 类别 设计) 并在线预览',
    captions: ['上传真实 PDF 证据文件, 密级设为"机密"', '文件按 SHA256 内容寻址存储, 版本不可变', '预览前服务端复核摘要, 显示"一致"'] },
  { id: '03-5', chapter: '03', who: 'admin', screen: 'URS与追踪 > 建立需求追踪; 干系人与沟通', action: '建立 URS-01 到验收证据的追踪, 登记干系人',
    captions: ['建立需求追踪: URS-01 关联验收证据', '识别干系人: 客户, 质量与供应链'] },
  { id: '03-6', chapter: '03', who: 'admin', screen: '需求与治理 > DQ与局部暂停', action: '建立 DQ 关键任务, 逐项检查后提交签认',
    captions: ['DQ 关键任务: 逐项检查后提交签认', '审核人签认后, 交付件更新会自动提示失效'] },

  { id: '04-1', chapter: '04', who: 'admin', screen: '计划与执行 > 进度卷积 > 派生主/子/单机计划', action: '一键派生并查看 WBS 表的来源列',
    captions: ['按模板阶段层级一键派生主, 子, 单机计划', '15 个任务自动串联, 来源标记"模板派生"'] },
  { id: '04-2', chapter: '04', who: 'admin', screen: 'WBS与排程 > 编辑; 进度卷积 > 覆盖阶段权重', action: '拉长单机装配工期, 查看主子冲突, 覆盖阶段权重',
    captions: ['单机装配工期拉长到 60 个工作日', '系统自动定位主子约束冲突与延误天数', '按项目实际覆盖阶段权重, 合计须为 100%'] },
  { id: '04-3', chapter: '04', who: 'admin', screen: '需求与治理 > Gate评审', action: '查看需求确认 / 主机汇总 / 附件汇总 Gate 通过',
    captions: ['需求确认与设计汇总 Gate 逐项绑定证据', '由独立审核人批准, 进展卡显示"已通过"'] },
  { id: '04-4', chapter: '04', who: 'admin', screen: '项目概况 > 确认立项 / 进入计划; 审批与基线', action: '推进生命周期并提交计划审批',
    captions: ['确认立项, 进入计划阶段', '提交计划基线, 待审期间设计锁定'] },
  { id: '04-5', chapter: '04', who: 'reviewer', screen: '审核人账号 > 计划与执行 > 审批与基线', action: '审核人批准计划基线',
    captions: ['审核人批准计划基线, 快照冻结不可修改'] },

  { id: '05-1', chapter: '05', who: 'admin', screen: '工程交付 > 配置交付要求', action: '配置工勘次数, 发货前条件, 交底与现场时限',
    captions: ['执行前配置交付要求与试验类别', '工勘 1 次, 发货前须入库, 交底 2 天, 现场滞后 1 天'] },
  { id: '05-2', chapter: '05', who: 'admin', screen: '项目概况 > 进入执行', action: '进入执行阶段',
    captions: ['章程, 基线与执行准入 Gate 全部满足', '项目进入执行阶段'] },
  { id: '05-3', chapter: '05', who: 'admin', screen: '需求与治理 > 会议行动 > 登记项目会议', action: '登记启动会, 绑定会前资料与计划基线',
    captions: ['启动会必须绑定会前资料与已批准基线'] },
  { id: '05-4', chapter: '05', who: 'admin', screen: '需求与治理 > 风险与问题', action: '登记长周期件交期风险',
    captions: ['登记风险: 概率 x 影响评分与应对策略', '超过阈值的风险会自动升级待确认'] },

  { id: '06-1', chapter: '06', who: 'admin', screen: '工程交付 > 备料与BOM > 新建备料申请', action: '发起长周期物料备料申请',
    captions: ['发起长周期物料备料, 关联单机, 任务与需求'] },
  { id: '06-2', chapter: '06', who: 'admin', screen: '工程交付 > 备料与BOM', action: '审批与 BOM 冻结后查看齐套率与缺件清单',
    captions: ['BOM 冻结后登记齐套: 主机#1 缺 PLC 控制器', '齐套率按单机, 子项目逐层卷积, 缺件可展开'] },
  { id: '06-3', chapter: '06', who: 'admin', screen: '工程交付 > 装配交检 > 登记开工', action: '齐套 Gate 未通过时尝试开工被拒',
    captions: ['零件齐套 Gate 未通过, 装配开工被系统阻断'] },
  { id: '06-4', chapter: '06', who: 'admin', screen: '工程交付 > 装配交检 > 登记步骤', action: '补齐并通过齐套 Gate 后登记装配步骤',
    captions: ['补齐缺件, 齐套 Gate 放行后开工', '逐步登记上岛, 装配, 单机交检, 连线交检'] },
  { id: '06-5', chapter: '06', who: 'admin', screen: '需求与治理 > Gate评审 > 装配与测试交接Gate', action: '填写检查, AT-4 例外放行并说明',
    captions: ['装配与测试交接 Gate: 逐项填写检查结果', '测试工装未到位: 经说明后例外放行', '例外项单独标注, 审核人批准后解除 SIT 阻断'] },

  { id: '07-1', chapter: '07', who: 'admin', screen: '工程交付 > 质量试验 > 建立质量试验', action: '建立 SIT 试验与验收标准',
    captions: ['建立 SIT 试验: 验收标准关联装配与需求'] },
  { id: '07-2', chapter: '07', who: 'admin', screen: '工程交付 > 质量试验', action: '查看 SIT / FAT 已批准',
    captions: ['SIT, FAT 逐项登记实测结果与证据', '由独立审核人批准, 失败项会自动生成问题'] },
  { id: '07-3', chapter: '07', who: 'admin', screen: '工程交付 > 发运签收', action: '未入库确认时申请放行被拒, 再确认发货前条件',
    captions: ['发货前须入库确认: 未确认时放行被拒绝', '确认 WMS 入库单后, 才能申请放行'] },
  { id: '07-4', chapter: '07', who: 'admin', screen: '工程交付 > 发运签收', action: '查看放行, 发运与签收结果',
    captions: ['FAT 确认 Gate 通过, 放行, 发运', '客户签收由审核人验证: 完整接受'] },

  { id: '08-1', chapter: '08', who: 'admin', screen: '工程交付 > 工勘与现场 > 完成交底', action: '完成发运后自动生成的交底任务',
    captions: ['发运后自动生成交底, 截止为发运日 + 2 天', '完成交底, 自动下发四项现场任务'] },
  { id: '08-2', chapter: '08', who: 'admin', screen: '工勘与现场 > 现场任务', action: '开始并完成现场定位, 其余按序完成',
    captions: ['现场定位, 安装, 调试, SAT 按顺序执行', '前序任务未完成, 后续任务不能开始'] },
  { id: '08-3', chapter: '08', who: 'admin', screen: '工勘与现场 > 登记工勘任务', action: '登记工勘并提交确认',
    captions: ['登记现场工勘并提交确认', '审核人批准后, 收尾阻塞自动解除'] },
  { id: '08-4', chapter: '08', who: 'admin', screen: '工程交付 > 质量试验', action: '查看 SAT 已批准',
    captions: ['SIT, FAT 通过且签收后完成 SAT, 交付链闭环'] },

  { id: '09-1', chapter: '09', who: 'admin', screen: '计划与执行 > WBS与排程 > 反馈进度', action: '反馈完成并填写实际开始与完成日期',
    captions: ['任务反馈实际开始与完成日期', '实际日期不能晚于今天, 已确认进度不可回退'] },
  { id: '09-2', chapter: '09', who: 'admin', screen: '实际工时 > 提交实际工时', action: '提交工时并指定审核人',
    captions: ['提交实际工时, 绑定任务并指定审核人'] },
  { id: '09-3', chapter: '09', who: 'reviewer', screen: '审核人账号 > 实际工时', action: '审核人批准工时',
    captions: ['审核人批准工时, 计入挣值 AC 与费用分摊'] },
  { id: '09-4', chapter: '09', who: 'admin', screen: '系统监控 > 定时任务', action: '对 PMS进度扫描 执行一次',
    captions: ['系统每天 06:00 自动扫描进度与逾期', '也可以在定时任务中立即执行一次'] },
  { id: '09-5', chapter: '09', who: 'admin', screen: '计划与执行 > 进度卷积', action: '生成进度快照, 查看挣值与完工预测',
    captions: ['挣值面板: PV, EV, AC 与 SPI, CPI', '按阶段分组, 给出完工预测与趋势快照'] },
  { id: '09-6', chapter: '09', who: 'reviewer', screen: '审核人账号 > 我的待办', action: '查看待审批事项与系统提醒',
    captions: ['审核人的"我的待办": 待审批与逾期提醒', '逾期对象不再逾期时, 提醒自动关闭'] },

  { id: '10-1', chapter: '10', who: 'admin', screen: '项目费用', action: '查看四算版本与差异',
    captions: ['概算, 预算, 核算, 决算各自独立审批', '四算拉通对比, 差异一目了然'] },
  { id: '10-2', chapter: '10', who: 'admin', screen: '项目详情 > 过程看板', action: '查看过程指标',
    captions: ['过程看板: 需求, 文档, 齐套, 试验与问题'] },
  { id: '10-3', chapter: '10', who: 'admin', screen: '项目管理 > 项目组合看板', action: '展开项目行查看结构节点',
    captions: ['项目组合看板: 进度, SPI/CPI, 齐套, 成本', '展开项目, 下钻到子项目与单机'] },
  { id: '10-4', chapter: '10', who: 'admin', screen: '项目管理 > 全局检索; 经营目标看板', action: '输入关键字检索, 查看经营目标',
    captions: ['全局检索: 项目, 需求, 文档一站式命中', '经营目标看板: 季度目标版本化与达成复算'] },
  { id: '10-5', chapter: '10', who: 'admin', screen: '项目详情 > 接口运维', action: '查看八套外部系统状态',
    captions: ['接口运维: 外部系统如实显示"未配置"', '本地闭环演示, 不以模拟冒充真实集成'] },

  { id: '11-1', chapter: '11', who: 'admin', screen: '需求与治理 > Gate评审; 项目概况 > 进入收尾', action: 'SAT 确认 Gate 通过, 8 个 Gate 全部通过后进入收尾',
    captions: ['SAT 确认 Gate 通过, 8 个 Gate 全部完成', '全部任务完成后, 项目进入收尾'] },
  { id: '11-2', chapter: '11', who: 'admin', screen: '结项与移交', action: '确认收尾清单, 登记移交事项与项目经验',
    captions: ['逐项确认收尾清单, 每项引用证据版本', '登记移交事项与项目经验, 服务下一个项目'] },
  { id: '11-3', chapter: '11', who: 'admin', screen: '结项与移交 > 提交关闭审批', action: '前置检查通过后提交关闭审批',
    captions: ['结项前置检查全部通过, 提交关闭审批'] },
  { id: '11-4', chapter: '11', who: 'reviewer', screen: '审核人账号 > 结项与移交', action: '审核人批准关闭',
    captions: ['审核人核对交付, 质量, 工时与决算后批准'] },
  { id: '11-5', chapter: '11', who: 'admin', screen: '项目概况 > 正式关闭', action: '正式关闭项目, 转为只读',
    captions: ['项目经理正式关闭项目', '项目转为只读归档, 重开需独立批准'] },
];

const byId = Object.fromEntries(shots.map(s => [s.id, s]));

// 字幕最短停留: 2.8 秒或按 6.5 字每秒.
const minDuration = text => Math.max(2.8, [...text].length / 6.5);

module.exports = { title, ending, chapters, shots, byId, minDuration };
