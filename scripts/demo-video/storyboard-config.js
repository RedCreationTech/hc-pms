// 红创PMS "组织, 菜单与流程的灵活配置" 演示视频的分镜与字幕 (唯一数据源).
// 录屏用例 tests/e2e/config-demo-video.spec.js 按 shot id 取字幕; render_cards.js / compose.py 通过
// DEMO_STORYBOARD=scripts/demo-video/storyboard-config.js 与 DEMO_VIDEO_DIR=reports/config-video 读取本文件,
// docs/pms/17-config-video-storyboard.md 由 storyboard_md.js 生成.
// 字幕: 每条一行, 不超过 32 个字符, 屏幕停留时间 = max(2.8s, 字数 / 6.5 字每秒), 由录屏用例自动保证.

const title = {
  heading: '红创PMS 组织 · 菜单 · 流程',
  subheading: '一家公司的多个部门 · 灵活配置演示',
  meta: '部门与负责人 · 角色菜单与数据权限 · 多级审批策略 · 真实系统录屏',
};

const ending = {
  heading: '按组织灵活配置, 权限在后端生效',
  points: [
    '部门树与负责人, 角色的菜单/按钮/数据权限, 审批策略均可在界面调整, 无需改代码',
    '每个接口都校验实时权限, 越权访问返回 403; 提交人不能审批自己的申请',
    '设计与验证: docs/pms/16-permission-org-workflow.md · docs/pms/verification.md',
  ],
};

// 各录像账号在画面左下角的标签.
const accounts = {
  admin: '系统管理员账号',
  mgr: '研发一部经理账号',
  pm: '项目经理账号',
  fin: '财务审批人账号',
};

const doc = {
  path: 'docs/pms/17-config-video-storyboard.md',
  title: '红创PMS 组织, 菜单与流程配置演示视频: 分镜与字幕',
  intro: '本文件由 `scripts/demo-video/storyboard_md.js` 从 `scripts/demo-video/storyboard-config.js` 生成 (`DEMO_STORYBOARD=scripts/demo-video/storyboard-config.js`), 请修改数据源后重新生成, 不要直接编辑. 视频在一个全新的演示库上, 以一家公司的多个部门为例, 依次演示: 搭建部门与负责人, 用角色配置菜单/按钮/数据权限并看到权限效果, 在 "审批策略" 中配置多级审批并由不同部门的审批人逐级处理. 录屏由 `tests/e2e/config-demo-video.spec.js` 在真实系统上自动完成; 设计见 [16 权限, 组织与审批流程整改计划](16-permission-org-workflow.md).',
  make: [
    '在全新的隔离后端 (新 SQLite 库, 迁移自动建表与种子数据) 上执行:',
    '',
    '```bash',
    'DEMO_STORYBOARD=scripts/demo-video/storyboard-config.js node scripts/demo-video/storyboard_md.js',
    'CONFIG_DEMO_VIDEO=1 BASE_URL=http://127.0.0.1:3300 npx playwright test tests/e2e/config-demo-video.spec.js --project=chromium',
    'DEMO_STORYBOARD=scripts/demo-video/storyboard-config.js DEMO_VIDEO_DIR=reports/config-video node scripts/demo-video/render_cards.js',
    'DEMO_VIDEO_DIR=reports/config-video DEMO_VIDEO_NAME=hc-pms-config-demo python3 scripts/demo-video/compose.py',
    '```',
    '',
    '产物在 `reports/config-video/` (不入库): `hc-pms-config-demo.mp4` 成片, `hc-pms-config-demo.srt` 字幕, `timeline.json`, `edl.json`, `music.wav`. 画面, 字幕对齐, 快进标记与配乐规则同 [完整流程演示视频](15-demo-video-storyboard.md).',
  ],
};

// short 用于左侧章节导航栏 (不超过 6 个字).
const chapters = [
  { no: '01', title: '组织架构', short: '组织架构', subtitle: '部门树 · 部门负责人 · 员工归属', points: ['多级部门, 同级不重名, 有下级或有人员不能删除', '负责人从用户中选择, 供审批规则使用'] },
  { no: '02', title: '角色与菜单', short: '角色菜单', subtitle: '菜单与按钮权限 · 数据权限 · 分配用户', points: ['角色决定能看到哪些菜单和按钮', '数据权限决定能看到哪些部门的数据'] },
  { no: '03', title: '权限效果', short: '权限效果', subtitle: '部门经理视角 · 403 · 实时收回', points: ['只看到授权菜单与本部门数据', '管理员收回菜单, 切换页面后立即生效'] },
  { no: '04', title: '审批策略', short: '审批策略', subtitle: '按组织配置多级审批', points: ['部门负责人 / 角色 / 项目经理 / 指定用户', '或签与会签, 金额条件, 发布即生效'] },
  { no: '05', title: '逐级审批', short: '逐级审批', subtitle: '提交 · 部门负责人 · 财务 · 事业部负责人', points: ['审批人按策略自动确定, 提交人不能自审', '末级通过后业务自动生效'] },
];

// who: admin 系统管理员, mgr 研发一部经理 (也是研发一部负责人), pm 项目经理, fin 财务审批人.
const shots = [
  { id: '01-1', chapter: '01', who: 'admin', screen: '系统管理 > 部门管理', action: '查看部门树, 在智能装备事业部下新建测试部',
    captions: ['一家公司的多个部门, 在"部门管理"中维护', '事业部下设研发一部, 现在再新建一个测试部', '同一上级下部门不能重名, 层级路径自动维护'] },
  { id: '01-2', chapter: '01', who: 'admin', screen: '部门管理 > 修改研发一部', action: '把研发一部的负责人设为周经理',
    captions: ['部门负责人从用户中选择, 不再是自由文本', '审批规则里的"部门负责人"就指向这里'] },
  { id: '01-3', chapter: '01', who: 'admin', screen: '系统管理 > 用户管理', action: '在左侧组织树点选部门, 查看各部门人员',
    captions: ['员工按部门归属, 点选组织树即可筛选', '研发一部: 周经理, 项目经理孙工与工程师'] },

  { id: '02-1', chapter: '02', who: 'admin', screen: '系统管理 > 角色管理 > 新增', action: '新建部门经理角色',
    captions: ['为部门经理新建一个角色'] },
  { id: '02-2', chapter: '02', who: 'admin', screen: '角色管理 > 更多 > 分配权限', action: '勾选用户管理 (取消用户删除按钮), 部门管理与项目管理',
    captions: ['勾选用户管理, 其下按钮权限随之联动勾选', '展开后取消"用户删除", 再勾选部门管理', '最后勾选项目管理, 用于处理项目审批'] },
  { id: '02-3', chapter: '02', who: 'admin', screen: '角色管理 > 更多 > 数据权限', action: '选择本部门及以下数据权限',
    captions: ['数据权限选择"本部门及以下"', '五种范围可选, 多个角色时取并集'] },
  { id: '02-4', chapter: '02', who: 'admin', screen: '角色管理 > 更多 > 分配用户', action: '添加用户页签勾选周经理并授权',
    captions: ['把周经理加入部门经理角色'] },

  { id: '03-1', chapter: '03', who: 'mgr', screen: '周经理账号 > 用户管理', action: '侧边栏只有授权菜单, 用户列表只有本部门人员',
    captions: ['周经理登录: 侧边栏只出现授权的菜单', '用户列表只有研发一部的人员', '没有删除权限, 界面上也不出现删除按钮'] },
  { id: '03-2', chapter: '03', who: 'mgr', screen: '地址栏直接访问角色管理', action: '未授权页面显示 403',
    captions: ['直接输入角色管理的地址, 显示无权访问', '接口同样校验权限, 越权请求返回 403'] },
  { id: '03-3', chapter: '03', who: 'admin', screen: '角色管理 > 分配权限', action: '取消勾选部门管理并保存',
    captions: ['管理员收回部门经理的"部门管理"菜单'] },
  { id: '03-4', chapter: '03', who: 'mgr', screen: '周经理账号 > 切换页面', action: '侧边栏不再显示部门管理, 访问显示 403',
    captions: ['周经理切换页面, 权限随即刷新', '部门管理从菜单消失, 无需重新登录'] },

  { id: '04-1', chapter: '04', who: 'admin', screen: '项目管理 > 模板与规则 > 审批策略', action: '查看内置示例, 新建费用版本审批策略',
    captions: ['审批流程在"审批策略"中按组织配置', '为"费用版本"新建三级审批'] },
  { id: '04-2', chapter: '04', who: 'admin', screen: '审批策略编辑器', action: '逐级选择审批人规则与金额条件, 保存并发布',
    captions: ['第一级: 项目所属部门的负责人', '第二级: 财务角色, 任一人通过即可', '第三级: 金额达到 10 万元时, 由事业部负责人审批', '保存为草稿后发布, 对新提交立即生效'] },

  { id: '05-1', chapter: '05', who: 'pm', screen: '项目经理账号 > 项目中心 > 项目费用', action: '提交 15 万元预算版本, 查看审批进度',
    captions: ['项目经理孙工提交 15 万元的预算版本', '系统按策略自动确定三级审批人', '研发一部负责人 -> 财务 -> 事业部负责人'] },
  { id: '05-2', chapter: '05', who: 'mgr', screen: '周经理账号 > 项目管理 > 我的待办', action: '在逐级审批中通过第一级',
    captions: ['周经理的"我的待办"出现第一级审批', '查看逐级进度, 填写意见后通过'] },
  { id: '05-3', chapter: '05', who: 'fin', screen: '财务账号 > 项目管理 > 我的待办', action: '财务审批人通过第二级',
    captions: ['轮到财务: 非项目成员也可只读查看资料', '财务核对金额后通过, 进入第三级'] },
  { id: '05-4', chapter: '05', who: 'pm', screen: '项目经理账号 > 项目费用', action: '事业部负责人批准后, 查看三级全部通过与版本已批准',
    captions: ['事业部负责人批准后, 三级全部通过', '预算版本自动生效, 全程留痕可追溯'] },
];

const byId = Object.fromEntries(shots.map(s => [s.id, s]));

// 字幕最短停留: 2.8 秒或按 6.5 字每秒.
const minDuration = text => Math.max(2.8, [...text].length / 6.5);

module.exports = { title, ending, chapters, shots, byId, minDuration, accounts, doc };
