// 红创PMS 平台 (RuoYi-Clojure) BPMN 建模能力演示视频的独立分镜与字幕 (唯一数据源).
// 录屏用例 tests/e2e/bpmn-capabilities-video.spec.js 按 shot id 取字幕; 章节卡与成片合成走 compose.py
//   (DEMO_STORYBOARD=scripts/demo-video/storyboard-bpmn.js, DEMO_VIDEO_DIR=reports/bpmn-video).
//
// 定位: 这是一支"BPMN 建模能力片" (全新独立), 聚焦流程设计器本身, 与已有的"BPM 与办公一体化"业务生命周期演示片互补, 不重复.
// 覆盖口径 (用户确认): 类别全覆盖 -- 每个能力类别都在真实浏览器里点一遍代表性操作, 而非逐下拉项穷举.
//   01 能力总览: 一个模型汇聚全部节点类型 (开始/审批/办理/抄送/条件/并行/包容/延迟/触发/子流程/结束) + "+" 节点面板十种类型.
//   02 审批人配置抽屉: 审批类型, 14 种候选人策略, 5 种多人审批(会签)方式, 驳回处理, 超时处理, 候选人为空策略, 操作按钮, 字段权限.
//   03 办理人与抄送抽屉: TRANSACTOR 角色候选人, COPY 自动知会.
//   04 网关与条件: CONDITION 条件表达式路由, 追加条件(添加条件), PARALLEL 并行分支, INCLUSIVE 包容分支.
//   05 延迟器 / 触发器 / 子流程 三类节点抽屉.
//   06 追踪与发起可见: 发布运行定义 -> 发起页右侧只读流程图预览 (完整审批链 + 审批人昵称).
// 每个 shot 的动作在 spec 里用 kit.glide/tap 真实点击 (开抽屉, 滚动, 唤面板), 录屏画面即操作过程本身.
// 字幕: 每条一行, 不超过 32 个字符, 屏幕停留时间 = max(2.8s, 字数 / 6.5 字每秒), 由录屏用例自动保证.

const title = {
  heading: '红创PMS 平台 · BPMN 建模能力全景',
  subheading: 'Flowable 流程设计器 · 节点类型 / 审批人策略 / 会签 / 网关 / 延迟 / 触发 / 子流程 / 追踪',
  meta: '真实浏览器逐步操作录屏 · 类别全覆盖 · 本地闭环',
};

const ending = {
  heading: 'BPMN 建模能力全景演示完成',
  points: [
    '一个模型汇聚全部 11 类流程节点, "+" 面板可添加 10 种节点类型',
    '审批人配置抽屉一览: 14 种候选人策略与 5 种多人审批(会签)方式',
    '驳回处理, 超时提醒, 候选人为空转交, 操作按钮与表单字段权限逐类可配',
    '条件/并行/包容三类网关真实渲染, 条件规则可编辑并支持追加条件',
    '延迟器/触发器/子流程节点独立配置, 发布后运行时定义即时生成',
    '发起弹窗常驻只读流程图, 完整审批链与审批人昵称实时解析可见',
  ],
};

// short 用于左侧章节导航栏 (不超过 6 个字).
const chapters = [
  { no: '01', title: 'BPMN 能力总览', short: '能力总览', subtitle: '节点类型全景 · "+" 添加面板', points: ['一个模型汇聚全部节点类型', '点加号唤出十种节点面板'] },
  { no: '02', title: '审批人配置', short: '审批人配置', subtitle: '候选人策略 · 会签 · 门控', points: ['14 种候选人策略一览', '会签方式/驳回/超时/操作按钮'] },
  { no: '03', title: '办理人与抄送', short: '办理抄送', subtitle: 'TRANSACTOR · COPY', points: ['办理人角色候选任一推进', '抄送自动知会且不阻塞'] },
  { no: '04', title: '网关与条件', short: '网关条件', subtitle: '条件 · 并行 · 包容', points: ['条件按表达式路由并可追加', '并行/包容分支真实渲染'] },
  { no: '05', title: '延迟 触发 子流程', short: '延时触发', subtitle: 'DELAY · TRIGGER · CHILD', points: ['边界定时器等待后继续', '触发外部动作与调用子流程'] },
  { no: '06', title: '追踪与发起可见', short: '发起可见', subtitle: '发布定义 · 只读流程图', points: ['发布后运行时定义即时生成', '发起弹窗预览完整审批链'] },
];

// who: admin = 系统管理员 (唯一上下文, 全程可编辑模型并只读预览发起流程).
const shots = [
  { id: '01-1', chapter: '01', who: 'admin', screen: '流程模型 > 能力模型 > 流程设计', action: '进入富能力模型设计器, 渲染全部节点类型的总体流程',
    captions: ['一支视频看全 BPMN 建模能力', '一个模型汇聚全部节点类型'] },
  { id: '01-2', chapter: '01', who: 'admin', screen: '设计器 > 节点间加号', action: '点击节点连接处的加号, 唤出"在此添加节点"十种类型面板',
    captions: ['点节点间加号唤出节点面板', '十种类型随取随用: 审批到子流程'] },

  { id: '02-1', chapter: '02', who: 'admin', screen: '审批人节点 > 配置抽屉', action: '点审批人节点打开配置抽屉, 展示审批类型与 14 种候选人策略',
    captions: ['打开审批人节点配置抽屉', '十四种候选人策略一览'] },
  { id: '02-2', chapter: '02', who: 'admin', screen: '抽屉 > 多人审批方式', action: '抽屉内下滚到多人审批(会签)方式区, 展示 5 种方式',
    captions: ['五种会签: 依次/会签/比例/随机', '并行或任一即可推进'] },
  { id: '02-3', chapter: '02', who: 'admin', screen: '抽屉 > 超时/为空/按钮', action: '继续下滚展示超时处理, 候选人为空策略与操作按钮',
    captions: ['超时提醒, 候选为空转交管理员', '操作按钮开关逐项可配'] },

  { id: '03-1', chapter: '03', who: 'admin', screen: '办理人节点 > 配置抽屉', action: '点办理人节点打开抽屉, 展示角色候选与任一审批',
    captions: ['办理人节点: 按角色候选', '任一办理即推进流程'] },
  { id: '03-2', chapter: '03', who: 'admin', screen: '抄送节点 > 配置抽屉', action: '点抄送节点打开抽屉, 展示自动知会指定成员',
    captions: ['抄送节点自动知会指定成员', '抄送不阻塞流程推进'] },

  { id: '04-1', chapter: '04', who: 'admin', screen: '条件分支 > 条件标签', action: '点条件分支的条件标签打开条件规则编辑器',
    captions: ['条件分支按表达式路由', '金额阈值决定下一步走向'] },
  { id: '04-2', chapter: '04', who: 'admin', screen: '条件分支 > 添加条件', action: '点"添加条件"追加一条并列分支条件',
    captions: ['一条分支可追加多个条件', '默认流转兜住其余情况'] },
  { id: '04-3', chapter: '04', who: 'admin', screen: '并行/包容分支', action: '滑到并行分支与包容分支, 展示两类网关渲染',
    captions: ['并行分支同时激活多路', '包容分支命中多条件并行'] },

  { id: '05-1', chapter: '05', who: 'admin', screen: '延迟器 > 配置抽屉', action: '点延迟器节点打开抽屉, 展示边界定时器时长配置',
    captions: ['延迟器定时等待后继续', '此处设两小时边界定时器'] },
  { id: '05-2', chapter: '05', who: 'admin', screen: '触发器节点', action: '在干净设计器中滑到并展示触发器节点 (节点内调用外部 HTTP / 回调 / 更新表单)',
    captions: ['触发器在节点内调用外部动作', '支持回调与脚本通知'] },
  { id: '05-3', chapter: '05', who: 'admin', screen: '子流程 > 配置抽屉', action: '点子流程节点打开抽屉, 展示调用请假子流程与变量映射',
    captions: ['子流程调用独立请假流程', '主子流程变量双向映射'] },

  { id: '06-1', chapter: '06', who: 'admin', screen: '发布 > 发起流程页', action: '发布内置请假模型生成运行时定义, 进入发起流程页',
    captions: ['发布后运行时定义即时生成', '发起入口常驻只读流程图'] },
  { id: '06-2', chapter: '06', who: 'admin', screen: '发起请假 > 只读预览', action: '点"发起"打开弹窗, 右侧只读流程图预览完整审批链与审批人',
    captions: ['弹窗右侧预览完整审批链', '审批人按昵称实时解析展示'] },
];

const byId = Object.fromEntries(shots.map(s => [s.id, s]));

// 字幕最短停留: 2.8 秒或按 6.5 字每秒.
const minDuration = text => Math.max(2.8, [...text].length / 6.5);

module.exports = { title, ending, chapters, shots, byId, minDuration };
