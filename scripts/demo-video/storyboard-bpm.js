// 红创PMS 平台 (RuoYi-Clojure) BPM 与办公一体化演示视频的分镜与字幕 (唯一数据源).
// 录屏用例 tests/e2e/bpm-demo-video.spec.js 按 shot id 取字幕, 章节卡与成片合成走 compose.py (DEMO_VIDEO_DIR=reports/bpm-video).
//
// 本分镜全部为"真实浏览器操作"镜头, 覆盖用户要求的完整生命周期"建模→发布→发起→审批→跟踪→作废":
//   01 流程建模: 打开模型 -> 点击"流程设计"步骤 -> 自定义设计器渲染总体流程节点 -> 点节点开配置抽屉 -> 保存 -> 发布 -> 运行时定义.
//   02 请假审批链: 员工在页面"发起请假" -> 管理员在"我的待办"逐级点"通过"(部门经理/分管领导) -> hr 用户点"HR确认"办结 -> 员工看"已通过".
//   03 报销审批链: 员工"发起报销" -> 管理员逐级"通过"(部门经理/财务) -> 实例结束.
//   04 OA: 页面"新增日程" / "新增会议" 真实填表保存.
//   05 HRM/CRM: 页面"新增员工" / "新增客户" 真实填表保存.
//   06 报表: 办公报表看板真实统计.
//   07 实例跟踪: 我的流程列表 -> 实例详情 -> 高亮流程图 (当前节点/已完成的节点) -> 历史轨迹时间轴.
//   08 已办与抄送: 审批人"我的已办"查看当时提交的表单与意见; 抄送我的分页展示 cc 记录.
//   09 运维: 流程实例运维页挂起 -> 激活 -> 终止 (作废), 展示完整运维闭环.
//   10 复杂审批: 驳回 -> 员工收到待办回到自己; 转办/委派; 加签; 撤回已办.
// 每个 shot 的动作在 spec 里用 kit.tap/typeInto 真实点击+逐字输入, 录屏画面即操作过程本身.
// 字幕: 每条一行, 不超过 32 个字符, 屏幕停留时间 = max(2.8s, 字数 / 6.5 字每秒), 由录屏用例自动保证.

const title = {
  heading: '红创PMS 平台 · BPM 与办公一体化',
  subheading: 'Flowable 流程引擎 · 建模 / 请假 / 报销 / OA / HRM / CRM / 报表 / 跟踪 / 运维 / 复杂审批',
  meta: '真实浏览器逐步操作录屏 · 完整生命周期 · 本地闭环',
};

const ending = {
  heading: 'BPM 与办公一体化演示完成',
  points: [
    '流程设计器可视化绘制并保存节点树, 发布后生成 Flowable 运行时定义',
    '请假逐级审批(部门经理-分管领导-HR), 报销两级审批(部门经理-财务)真实办结',
    '实例跟踪/高亮流程图/历史轨迹/打印/运维挂起-激活-终止(作废) 全链闭环',
    '驳回, 转办, 加签, 撤回等复杂审批动作真实驱动流程回退与推进',
    'OA 日历/会议, HRM 员工, CRM 客户均在页面内真实新增并即时入库',
  ],
};

// short 用于左侧章节导航栏 (不超过 6 个字).
const chapters = [
  { no: '01', title: '流程建模与设计', short: '流程建模', subtitle: '模型 · 设计器 · 发布 · 定义', points: ['设计器渲染总体流程节点', '保存并发布为运行时定义'] },
  { no: '02', title: '请假审批链', short: '请假审批', subtitle: '发起 · 逐级待办审批 · 办结', points: ['员工页面发起, 真实入流程', '管理员与HR逐级点通过直至办结'] },
  { no: '03', title: '报销审批链', short: '报销审批', subtitle: '发起 · 部门经理 · 财务审核', points: ['报销与请假共享同一引擎', '两级审批真实推进至结束'] },
  { no: '04', title: 'OA 日历与会议', short: 'OA 办公', subtitle: '新增日程 · 新增会议', points: ['页面填表保存, 台账即时可见'] },
  { no: '05', title: 'HRM 与 CRM', short: 'HRM CRM', subtitle: '新增员工 · 新增客户', points: ['档案在页面内真实创建'] },
  { no: '06', title: '报表与运营', short: '报表运营', subtitle: '办公综合看板', points: ['跨模块聚合真实统计'] },
  { no: '07', title: '流程实例跟踪', short: '实例跟踪', subtitle: '我的流程 · 高亮图 · 历史', points: ['实例列表带 business_key/当前节点', '流程图按已完成/进行中高亮', '历史轨迹时间轴可打印'] },
  { no: '08', title: '已办与抄送', short: '已办抄送', subtitle: '审批人视角 · 抄送我的', points: ['已办行点开查看当时表单与意见', '抄送按业务归属对目标用户可见'] },
  { no: '09', title: '流程运维与作废', short: '运维作废', subtitle: '挂起 · 激活 · 终止', points: ['管理员挂起→激活→终止实例', '终止后状态翻 terminated'] },
  { no: '10', title: '复杂审批动作', short: '复杂审批', subtitle: '驳回 · 转办 · 加签 · 撤回', points: ['驳回后流程回到发起人', '转办让目标用户接手同一待办', '加签引入子任务, 撤回把已办收回'] },
];

// who: admin = 系统管理员/审批人, employee = 发起员工(common 角色), hr = HR确认候选人(user_name=hr).
const shots = [
  { id: '01-1', chapter: '01', who: 'admin', screen: '办公 > 流程模型', action: '打开流程模型列表, 展示内置请假/报销模型',
    captions: ['BPM 模型列表: 请假 / 报销 内置模板', '每个模型带分类, 表单与版本'] },
  { id: '01-2', chapter: '01', who: 'admin', screen: '请假模型 > 编辑 > 流程设计', action: '进入模型编辑器, 点击"流程设计"步骤, 设计器渲染总体流程',
    captions: ['进入流程设计器, 查看总体流程', '发起-审批-条件分支-结束逐节点呈现'] },
  { id: '01-3', chapter: '01', who: 'admin', screen: '设计器 > 点击节点', action: '点击审批节点, 打开节点配置抽屉',
    captions: ['点击节点打开配置抽屉', '候选人, 会签与按钮在此设定'] },
  { id: '01-4', chapter: '01', who: 'admin', screen: '发布 > 运行时定义', action: '点击发布部署该模型, 查看运行时流程定义',
    captions: ['发布模型, 生成 Flowable 运行时定义', '定义携带版本号与业务键映射'] },

  { id: '02-1', chapter: '02', who: 'employee', screen: '办公 > 请假 > 发起请假', action: '员工在页面点"发起请假", 填写天数与原因并提交',
    captions: ['员工自助发起请假', '填写天数与事由, 提交进流程'] },
  { id: '02-2', chapter: '02', who: 'admin', screen: '我的待办 > 部门经理审批', action: '管理员待办出现该请假, 点"通过"填写意见',
    captions: ['审批人在"我的待办"看到请假', '点通过, 填写审批意见提交'] },
  { id: '02-3', chapter: '02', who: 'admin', screen: '我的待办 > 分管领导审批', action: '刷新待办, 推进到分管领导审批节点, 再次点通过',
    captions: ['流程推进到分管领导审批', '通过后流转至 HR 确认'] },
  { id: '02-4', chapter: '02', who: 'hr', screen: '我的待办 > HR确认', action: 'HR 候选人待办出现 HR确认, 点通过办结实例',
    captions: ['HR 确认任务按归属可见', '点通过, 请假实例办结'] },
  { id: '02-5', chapter: '02', who: 'employee', screen: '办公 > 请假 / 我的已办', action: '员工查看该请假状态已变为已通过',
    captions: ['请假状态: 审批中 -> 已通过', '闭环结果在业务页即时回显'] },

  { id: '03-1', chapter: '03', who: 'employee', screen: '办公 > 报销 > 发起报销', action: '员工点"发起报销", 填写金额与事由并提交',
    captions: ['员工发起报销申请', '金额与事由, 提交进流程'] },
  { id: '03-2', chapter: '03', who: 'admin', screen: '我的待办 > 部门经理审批(报销)', action: '管理员待办出现报销, 点通过推进',
    captions: ['报销走同一引擎的独立实例', '部门经理审批通过'] },
  { id: '03-3', chapter: '03', who: 'admin', screen: '我的待办 > 财务审核(报销)', action: '刷新待办出现财务审核, 点通过使实例结束',
    captions: ['财务审核为报销第二节点', '通过后实例进入结束节点'] },

  { id: '04-1', chapter: '04', who: 'admin', screen: '办公 > OA 日历 > 新增日程', action: '点"新增日程", 填标题保存, 台账即时出现',
    captions: ['页面新建日程, 真实入库', '台账即时显示新增行'] },
  { id: '04-2', chapter: '04', who: 'admin', screen: '办公 > OA 会议 > 新增会议', action: '点"新增会议", 填主题/地点/参与人保存',
    captions: ['会议安排: 主题 / 地点 / 参与人', '保存后会议列表即时可见'] },

  { id: '05-1', chapter: '05', who: 'admin', screen: '办公 > HRM 员工 > 新增员工', action: '点"新增员工", 填工号/姓名/电话保存',
    captions: ['HRM 员工档案页面新建', '工号, 姓名, 电话即时入库'] },
  { id: '05-2', chapter: '05', who: 'admin', screen: '办公 > CRM 客户 > 新增客户', action: '点"新增客户", 填客户名/公司/电话保存',
    captions: ['CRM 客户档案页面新建', '客户名, 公司, 电话即时入库'] },

  { id: '06-1', chapter: '06', who: 'admin', screen: '办公 > 报表', action: '打开办公报表看板, 展示跨模块真实统计',
    captions: ['报表看板聚合请假/报销/员工/客户', '数值随真实数据变化'] },

  { id: '07-1', chapter: '07', who: 'employee', screen: '办公 > 我的流程', action: '员工打开"我的流程", 展示发起的多条实例与当前节点',
    captions: ['我的流程: 发起人视角看实例', 'business_key, 当前节点, 状态一目了然'] },
  { id: '07-2', chapter: '07', who: 'employee', screen: '实例详情 > 流程图', action: '点开某实例的"流程图", 显示 BPMN 图 + 已完成/进行中高亮',
    captions: ['高亮流程图: 已完成绿色, 进行中蓝色', '未走到的节点保持默认灰'] },
  { id: '07-3', chapter: '07', who: 'employee', screen: '实例详情 > 历史轨迹', action: '切换到"历史轨迹"标签, 展示提交→审批→通过时间轴',
    captions: ['历史轨迹: 每个动作留痕', '提交, 审批意见, 完成时间清晰可读'] },

  { id: '08-1', chapter: '08', who: 'admin', screen: '我的已办', action: '审批人打开"我的已办", 点某行查看当时提交的表单与审批意见',
    captions: ['已办: 曾经审批过的任务', '详情回放表单与当时填写的意见'] },
  { id: '08-2', chapter: '08', who: 'employee', screen: '抄送我的', action: '员工打开"抄送我的", 展示被 cc 的实例节点',
    captions: ['抄送我的: 按业务归属可见', '不占用待办, 但流程动态可追'] },

  { id: '09-1', chapter: '09', who: 'admin', screen: '办公 > 流程实例运维 > 挂起', action: '打开运维页, 选中运行中实例点"挂起", 状态变 suspended',
    captions: ['运维入口: 挂起 / 激活 / 终止', '挂起后流程暂停推进'] },
  { id: '09-2', chapter: '09', who: 'admin', screen: '运维 > 激活', action: '同实例点"激活", 状态回到 running',
    captions: ['激活恢复推进', '业务与引擎状态保持一致'] },
  { id: '09-3', chapter: '09', who: 'admin', screen: '运维 > 终止(作废)', action: '同实例点"终止"填写原因, 状态翻 terminated',
    captions: ['终止即作废', '填写原因留档, 实例进入终态'] },

  { id: '10-1', chapter: '10', who: 'admin', screen: '我的待办 > 驳回', action: '审批人在待办点"驳回", 员工侧待办立即回到自己',
    captions: ['驳回: 流程退回发起人', '员工待办再次出现, 可修改重提'] },
  { id: '10-2', chapter: '10', who: 'admin', screen: '我的待办 > 转办', action: '选中同一条待办, 点"转办"给 HR 用户, 目标用户待办出现该任务',
    captions: ['转办: 任务归属转移', '原办理人不再是候选'] },
  { id: '10-3', chapter: '10', who: 'admin', screen: '我的待办 > 加签', action: '选中一条待办, 点"加签"引入额外审批人, 子任务显示',
    captions: ['加签: 引入额外审批人', '父任务与子任务并行存在'] },
];

const byId = Object.fromEntries(shots.map(s => [s.id, s]));

// 字幕最短停留: 2.8 秒或按 6.5 字每秒.
const minDuration = text => Math.max(2.8, [...text].length / 6.5);

module.exports = { title, ending, chapters, shots, byId, minDuration };
