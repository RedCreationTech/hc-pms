// 红创PMS 平台 (RuoYi-Clojure) BPM 与办公一体化演示视频的分镜与字幕 (唯一数据源).
// 录屏用例 tests/e2e/bpm-demo-video.spec.js 按 shot id 取字幕, 章节卡与成片合成走 compose.py (DEMO_VIDEO_DIR=reports/bpm-video).
// 覆盖: 流程引擎与模型部署 · 请假审批链 · 报销审批链 · OA 日历与会议 · HRM 员工 · CRM 客户 · 报表与运营分析.
// 字幕: 每条一行, 不超过 32 个字符, 屏幕停留时间 = max(2.8s, 字数 / 6.5 字每秒), 由录屏用例自动保证.

const title = {
  heading: '红创PMS 平台 · BPM 与办公一体化',
  subheading: 'Flowable 流程引擎 · 请假 / 报销 / OA / HRM / CRM / 报表',
  meta: '真实系统录屏 · 本地闭环 · 与 PMS 工程链并行呈现',
};

const ending = {
  heading: 'BPM 与办公一体化演示完成',
  points: [
    'Flowable 8.0 引擎以 H2 独立存储, business-key 关联业务记录',
    '个人办理类接口按任务归属授权, 流程管理类接口按 bpm:* 权限',
    '办公模块 (请假/报销/OA/HRM/CRM/报表) 与 PMS 工程链共享同一 RuoYi 平台',
  ],
};

// short 用于左侧章节导航栏 (不超过 6 个字).
const chapters = [
  { no: '01', title: '流程引擎与建模', short: '流程建模', subtitle: 'BPM 模型 · 定义 · 部署', points: ['BPMN 设计器绘制流程', '模型发布后进入运行时定义'] },
  { no: '02', title: '请假审批链', short: '请假审批', subtitle: '发起 · 待办 · 审批 · 已办 · 抄送', points: ['员工自助发起, 上级审批', '抄送我的独立视图'] },
  { no: '03', title: '报销审批链', short: '报销审批', subtitle: '发起 · 财务审批 · 归档', points: ['财务独立审批, 与请假链解耦'] },
  { no: '04', title: 'OA 日历与会议', short: 'OA 会议', subtitle: '日历视图 · 会议安排 · 参与人', points: ['OA 日历承载个人与团队日程', '会议室与参与人绑定'] },
  { no: '05', title: 'HRM 与 CRM', short: 'HRM CRM', subtitle: '员工档案 · 客户档案 · 联系人', points: ['员工自助与 HR 管理双视图', '客户与联系人分层管理'] },
  { no: '06', title: '报表与运营', short: '报表运营', subtitle: '综合看板 · 分组统计 · 导出', points: ['报表模块承载跨域聚合视图'] },
];

// who: admin = 系统管理员, employee = 普通员工 (持 common 角色, 走自助发起 / 待办 / 抄送). screen/action 仅用于分镜文档.
const shots = [
  { id: '01-1', chapter: '01', who: 'admin', screen: '办公 > 流程管理 > 流程模型', action: '打开流程模型列表, 展示已导入的请假/报销/OA 模型',
    captions: ['BPM 模型列表: 请假, 报销, OA 等内置模板', '每个模型带分类, 表单与版本'] },
  { id: '01-2', chapter: '01', who: 'admin', screen: '流程模型 > 编辑 (BPMN 设计器)', action: '打开请假流程的 BPMN 设计器, 展示节点连线',
    captions: ['BPMN 设计器可视化绘制流程', '节点, 网关, 会签与候选人绑定'] },
  { id: '01-3', chapter: '01', who: 'admin', screen: '流程定义', action: '部署后查看运行时流程定义列表',
    captions: ['模型部署后进入运行时定义', '定义携带版本号与业务键映射'] },

  { id: '02-1', chapter: '02', who: 'employee', screen: '办公 > 我的流程 > 发起流程 > 请假', action: '员工登录, 打开请假发起表单填写起止与理由',
    captions: ['员工自助发起请假流程', '填写起止日期, 类型与理由'] },
  { id: '02-2', chapter: '02', who: 'admin', screen: '办公 > 我的待办', action: '切换到上级/管理员, 待办列表出现该请假',
    captions: ['审批人在"我的待办"看到待办', '待办按任务归属自动路由'] },
  { id: '02-3', chapter: '02', who: 'admin', screen: '我的待办 > 请假任务办理', action: '打开审批任务, 填写意见并同意',
    captions: ['填写审批意见, 同意推进流程', '拒绝, 转办, 加签在同一入口'] },
  { id: '02-4', chapter: '02', who: 'employee', screen: '办公 > 我的已办 / 我的流程', action: '员工回到自助视图查看已办与流程实例状态',
    captions: ['已办列表记录走过的每个任务节点', '我的流程看当前实例处于哪一步'] },
  { id: '02-5', chapter: '02', who: 'employee', screen: '办公 > 抄送我的', action: '查看被抄送的流程实例',
    captions: ['抄送我的独立视图, 只读展示', '授权来自抄送归属, 不依赖角色'] },

  { id: '03-1', chapter: '03', who: 'employee', screen: '办公 > 发起流程 > 报销', action: '员工发起报销单并上传金额明细',
    captions: ['报销与请假共享同一 BPM 引擎', '表单结构化字段: 类别, 金额, 附件'] },
  { id: '03-2', chapter: '03', who: 'admin', screen: '我的待办 > 报销任务', action: '财务/管理员在待办中审批通过',
    captions: ['财务独立审批, 与请假链解耦', '审批通过后实例进入结束节点'] },

  { id: '04-1', chapter: '04', who: 'admin', screen: '办公 > OA 日历', action: '在月视图中新建个人日程并显示提醒',
    captions: ['OA 日历承载个人与团队日程', '月/周/日三种视图共享同一存储'] },
  { id: '04-2', chapter: '04', who: 'admin', screen: '办公 > OA 会议 > 新建会议', action: '安排会议, 绑定会议室与参与人',
    captions: ['会议安排: 主题, 时间, 会议室, 参与人', '会议冲突检测与提醒'] },

  { id: '05-1', chapter: '05', who: 'admin', screen: '办公 > HRM 员工', action: '展示员工列表, 打开档案查看部门与岗位',
    captions: ['HRM 员工档案与部门, 岗位联动', '自助视图与 HR 管理视图分离'] },
  { id: '05-2', chapter: '05', who: 'admin', screen: '办公 > CRM 客户', action: '展示客户列表, 查看联系人跟进',
    captions: ['CRM 客户与联系人分层管理', '跟进记录承载销售过程'] },

  { id: '06-1', chapter: '06', who: 'admin', screen: '办公 > 报表', action: '展示综合看板与分组统计图',
    captions: ['报表模块承载跨域聚合视图', '分组维度: 部门, 时间, 类别'] },
];

const byId = Object.fromEntries(shots.map(s => [s.id, s]));

// 字幕最短停留: 2.8 秒或按 6.5 字每秒.
const minDuration = text => Math.max(2.8, [...text].length / 6.5);

module.exports = { title, ending, chapters, shots, byId, minDuration };
