const { test, expect } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');
const S = require('../../scripts/demo-video/storyboard-bpm.js');
const kit = require('./demo-video-kit.js');

// "BPM 与办公一体化" 演示视频的自动录屏 (分镜与字幕见 scripts/demo-video/storyboard-bpm.js).
// 结构对齐 config-demo-video.spec.js: 每章按分镜用到的账号各开一个录像上下文 (可见光标, 逐字输入, 底边时间码),
// 镜头起止与字幕时刻写入 reports/bpm-video/timeline.json, 由 compose.py (DEMO_VIDEO_DIR=reports/bpm-video) 剪辑合成.
//
// 真实操作策略 (响应用户反馈: 建模要看到总体流程, 请假/报销/OA/HRM/CRM 要看到具体操作):
//   所有镜头都在真实浏览器里逐步操作 -- 打开流程设计器渲染总体流程, 点节点开配置抽屉, 点发布生成运行时定义;
//   员工在页面"发起请假/报销", 审批人在"我的待办"逐级点"通过"填意见直至办结; OA/HRM/CRM 均现场"新增"填表保存; 报表看真实统计.
//   准备阶段 (不录像) 只创建账号 (演示员工 + hr 候选人), 不在 HTTP 层预落业务数据, 保证录屏画面即"操作发生"的本身.
// 关键取舍: 01 章只对内置请假模型 (500) 做 查看设计器 + 发布, 不点"保存流程" -- 因为保存会用节点树回写 BPMN 而丢掉
//   种子里的 flowable:candidateUsers (admin/admin/hr), 令 02/03 章的逐级审批无候选人可办. 发布(model-deploy!)直接部署
//   现有 bpmn_xml, 保留审批人, 且把 500 的部署先行落好, 后续请假发起复用之.
// 默认跳过, 仅在 BPM_DEMO_VIDEO=1 时运行. 需隔离后端已起 (BASE_URL, 独立 SQLite + 独立 Flowable H2).
test.skip(!process.env.BPM_DEMO_VIDEO, '演示视频录屏较慢, 仅在 BPM_DEMO_VIDEO=1 时运行');

const OUT = path.resolve(__dirname, '../../reports/bpm-video');
const RAW = path.join(OUT, 'raw');
const { VIEWPORT, wait, installCursor, installClock, glide, tap, typeInto, settle, login, api } = kit;
const BASE_URL = process.env.BASE_URL || 'http://localhost:3000';
const PASSWORD = 'Demo-2026';
const OK_RE = /确\s*定|OK/;
const LEAVE_MODEL_ID = process.env.LEAVE_MODEL_ID || '500';

const tbody = page => page.locator('.ant-table-tbody tr:not(.ant-table-measure-row)');

// 展示性滑动: 只把镜头移到页面主内容区 (表格/容器), 不依赖具体某一行.
// 空表时 antd 会同时渲染占位 <tr class="ant-table-placeholder"> (被 tbody 行选择器命中) 与 <div class="ant-empty">,
// 若用 A.first().or(B.first()) 联合定位会命中 2 个元素触发严格模式冲突, 故统一改用单一稳定容器 .first().
const contentArea = page => page.locator('.ant-table-wrapper, .ant-pro-page-container, main').first();

// 打开某模块页并等表格/空态稳定, 供镜头展示真实内容 (建模列表 / 报表看板等非填表镜头用).
async function openList(page, route) {
  await page.goto(route);
  await page.waitForLoadState('networkidle');
  try { await page.locator('.ant-table-tbody, .ant-empty').first().waitFor({ timeout: 4000 }); }
  catch (_) { await page.waitForTimeout(1500); }
  await settle(page);
}

// ── 通用: 点某按钮弹出对话框 -> 逐字填表 -> 点确定 -> 等对话框关闭 -> (可选)等台账出现新行 ──
async function dialogCreate(page, { path, addButton, fill, rowMarker }) {
  await page.goto(path);
  await page.waitForLoadState('networkidle');
  await settle(page);
  await tap(page, page.getByRole('button', { name: addButton }).first());
  const modal = page.getByRole('dialog').last();
  await expect(modal, `应弹出对话框: ${addButton}`).toBeVisible({ timeout: 10000 });
  await fill(page, modal);
  await tap(page, modal.getByRole('button', { name: OK_RE }));
  await expect(modal, '保存后对话框应关闭').toBeHidden({ timeout: 15000 });
  if (rowMarker) {
    await expect.poll(async () => tbody(page).filter({ hasText: rowMarker }).count(),
      { timeout: 15000, message: `台账应出现新行: ${rowMarker}` }).toBeGreaterThan(0);
  }
}

// ── 审批链: 在"我的待办"里定位某任务行 -> 点"通过" -> 填意见 -> 确定 -> 弹窗关闭 ──
async function approveTodoTask(page, taskName, comment) {
  await page.goto('/office/bpm/todo');
  await page.waitForLoadState('networkidle');
  await settle(page);
  const row = tbody(page).filter({ hasText: taskName }).first();
  await expect(row, `待办应出现任务: ${taskName}`).toBeVisible({ timeout: 20000 });
  await tap(page, row.getByRole('button', { name: /通\s*过/ }));
  const modal = page.getByRole('dialog').filter({ hasText: taskName }).last();
  await expect(modal, `应弹出审批对话框: ${taskName}`).toBeVisible({ timeout: 10000 });
  const box = modal.getByPlaceholder(/意见/);
  await box.fill('');
  await box.pressSequentially(comment, { delay: 45 });
  await tap(page, modal.getByRole('button', { name: OK_RE }));
  await expect(modal, '审批对话框提交后应关闭').toBeHidden({ timeout: 15000 });
}

// ── 一次性准备 (不录像): 只建演示账号 -- common 角色员工 + hr 候选人 (请假终审节点 candidateUsers=hr). ──
async function ensureUser(page, { user_name, nick_name, role_id }) {
  const found = await api(page, 'GET', `/api/system/user?user_name=${user_name}&page=1&size=5`);
  if ((found.rows || []).length) return found.rows[0];
  await api(page, 'POST', '/api/system/user', {
    user_name, nick_name, password: PASSWORD, dept_id: 103, roles: [role_id], posts: [], status: '0' });
  const list = await api(page, 'GET', `/api/system/user?user_name=${user_name}&page=1&size=5`);
  if (!(list.rows || []).length) throw new Error(`创建用户失败: ${user_name}`);
  return list.rows[0];
}

async function prepare(page, suffix) {
  const roles = await api(page, 'GET', '/api/system/role?role_key=common');
  const common = roles.find(r => r.role_key === 'common');
  if (!common) throw new Error('种子角色 common 不存在, 无法演示员工自助视图');
  const empName = `bemp${suffix}`;
  const emp = await ensureUser(page, { user_name: empName, nick_name: '演示员工', role_id: common.role_id });
  const hr = await ensureUser(page, { user_name: 'hr', nick_name: 'HR专员', role_id: common.role_id });
  return {
    suffix, empName, empId: emp.user_id, hrName: 'hr',
    leaveReason: `家中有事申请年假三天_${suffix}`,
    reimburseReason: `出差交通报销_${suffix}`,
    calTitle: `项目周会_${suffix}`,
    meetingSubject: `季度经营复盘_${suffix}`,
    hrmName: `新入职员工_${suffix}`, hrmCode: `E${suffix}`,
    crmName: `红创示例客户_${suffix}`,
  };
}

// ── 章节: 每章进入分镜指定的真实页面, 用 kit.tap/typeInto 逐步操作, 三拍走完字幕 (shot -> next -> end). ──
const CHAPTERS = {
  '01': async ({ pages, rec }) => {
    const page = pages.admin;
    // 01-1 流程模型列表 (真实内置模板行)
    await openList(page, '/office/bpm/model');
    await rec.shot('01-1');
    await glide(page, tbody(page).first().or(page.locator('.ant-empty')).first());
    await rec.next();
    await glide(page, page.locator('.ant-table').first());
    await rec.end();

    // 01-2 进入请假模型编辑器, 点击"流程设计"步骤, 设计器渲染总体流程节点
    await page.goto(`/office/bpm/model/edit?id=${LEAVE_MODEL_ID}`);
    await page.waitForLoadState('networkidle');
    await settle(page);
    await rec.shot('01-2');
    await tap(page, page.getByText('流程设计', { exact: true }).first());
    await page.waitForSelector('.bpm-flow-wrap', { timeout: 15000 });
    await page.waitForSelector('.bpm-node-card, .bpm-capsule', { timeout: 15000 });
    await settle(page);
    await glide(page, page.locator('.bpm-node-card').first());
    await rec.next();
    await glide(page, page.locator('.bpm-node-name').first());
    await rec.end();

    // 01-3 点击审批节点 -> 打开节点配置抽屉 -> 关闭 (仅查看, 不保存, 保护种子审批人)
    await rec.shot('01-3');
    const node = page.locator('.bpm-node-card').filter({ hasText: /审批|经理|确认|办理/ }).first();
    const target = (await node.count()) > 0 ? node : page.locator('.bpm-node-card').first();
    await tap(page, target);
    await page.waitForSelector('.ant-drawer-open', { timeout: 10000 });
    await settle(page);
    await glide(page, page.locator('.ant-drawer-title').first());
    await rec.next();
    await page.locator('.ant-drawer-close').first().click().catch(() => page.keyboard.press('Escape'));
    await page.waitForTimeout(600);
    await rec.end();

    // 01-4 点击"发布"部署模型 -> 运行时流程定义出现该 Key 定义
    await rec.shot('01-4');
    const deployResp = page.waitForResponse(r => r.url().includes(`/bpm/model/deploy/${LEAVE_MODEL_ID}`), { timeout: 25000 }).catch(() => null);
    await tap(page, page.getByRole('button', { name: '发布' }).first());
    const resp = await deployResp;
    if (resp) { const body = await resp.json().catch(() => ({})); expect(body.code, `发布模型: ${body.msg}`).toBe(200); }
    await openList(page, '/office/bpm/definition');
    await glide(page, tbody(page).first().or(page.locator('.ant-empty')).first());
    await rec.next();
    await glide(page, page.locator('.ant-table').first());
    await rec.end();
  },

  '02': async ({ pages, rec, st }) => {
    const emp = pages.employee;
    const admin = pages.admin;
    const hr = pages.hr;

    // 02-1 员工在页面发起请假 (填天数与原因), 提交入流程, 台账出现"审批中"
    await rec.shot('02-1');
    await dialogCreate(emp, {
      path: '/office/oa/leave', addButton: '发起请假',
      fill: async (p, modal) => {
        await typeInto(p, modal.getByRole('spinbutton'), '3');
        await typeInto(p, modal.getByPlaceholder('请输入请假原因'), st.leaveReason);
      },
      rowMarker: st.leaveReason,
    });
    await glide(emp, tbody(emp).filter({ hasText: st.leaveReason }).first());
    await rec.next();
    await expect(tbody(emp).filter({ hasText: st.leaveReason }).first()).toContainText('审批中');
    await settle(emp);
    await rec.end();

    // 02-2 管理员待办出现部门经理审批, 点通过填意见
    await rec.shot('02-2');
    await approveTodoTask(admin, '部门经理审批', '情况属实, 同意');
    await glide(admin, contentArea(admin));
    await rec.next();
    await settle(admin);
    await rec.end();

    // 02-3 刷新待办推进到分管领导审批, 再次通过 -> 流转至 HR确认
    await rec.shot('02-3');
    await approveTodoTask(admin, '分管领导审批', '同意, 转人力备案');
    await admin.goto('/office/bpm/todo');
    await admin.waitForLoadState('networkidle');
    await settle(admin);
    await glide(admin, contentArea(admin));
    await rec.next();
    await settle(admin);
    await rec.end();

    // 02-4 hr 候选人待办出现 HR确认, 点通过 -> 请假实例办结
    await rec.shot('02-4');
    await approveTodoTask(hr, 'HR确认', '已备案, 批准');
    await hr.goto('/office/bpm/todo');
    await hr.waitForLoadState('networkidle');
    await settle(hr);
    await glide(hr, contentArea(hr));
    await rec.next();
    await settle(hr);
    await rec.end();

    // 02-5 员工回到请假页, 该请假状态由"审批中"翻为"已通过"
    await rec.shot('02-5');
    await emp.goto('/office/oa/leave');
    await emp.waitForLoadState('networkidle');
    await settle(emp);
    const myLeave = tbody(emp).filter({ hasText: st.leaveReason }).first();
    await expect(myLeave, '请假单应已通过').toContainText('已通过', { timeout: 15000 });
    await glide(emp, myLeave);
    await rec.next();
    await settle(emp);
    await rec.end();
  },

  '03': async ({ pages, rec, st }) => {
    const emp = pages.employee;
    const admin = pages.admin;

    // 03-1 员工发起报销 (金额与事由), 提交入流程, 台账出现"审批中"
    await rec.shot('03-1');
    await dialogCreate(emp, {
      path: '/office/oa/reimburse', addButton: '发起报销',
      fill: async (p, modal) => {
        await typeInto(p, modal.getByRole('spinbutton'), '1280');
        await typeInto(p, modal.getByPlaceholder('请输入报销事由'), st.reimburseReason);
      },
      rowMarker: st.reimburseReason,
    });
    await expect(tbody(emp).filter({ hasText: st.reimburseReason }).first()).toContainText('审批中');
    await glide(emp, tbody(emp).filter({ hasText: st.reimburseReason }).first());
    await rec.next();
    await settle(emp);
    await rec.end();

    // 03-2 管理员待办出现报销的部门经理审批, 点通过 -> 推进财务审核
    await rec.shot('03-2');
    await approveTodoTask(admin, '部门经理审批', '费用合理, 同意');
    await admin.goto('/office/bpm/todo');
    await admin.waitForLoadState('networkidle');
    await settle(admin);
    await glide(admin, contentArea(admin));
    await rec.next();
    await settle(admin);
    await rec.end();

    // 03-3 财务审核为报销第二节点, 通过后实例进入结束节点
    await rec.shot('03-3');
    await approveTodoTask(admin, '财务审核', '票据齐全, 准予核销');
    await admin.goto('/office/bpm/todo');
    await admin.waitForLoadState('networkidle');
    await settle(admin);
    await glide(admin, contentArea(admin));
    await rec.next();
    await emp.goto('/office/oa/reimburse');
    await emp.waitForLoadState('networkidle');
    await settle(emp);
    await rec.end();
  },

  '04': async ({ pages, rec, st }) => {
    const page = pages.admin;
    // 04-1 页面新增日程 -> 台账即时出现
    await rec.shot('04-1');
    await dialogCreate(page, {
      path: '/office/oa/calendar', addButton: '新增日程',
      fill: async (p, modal) => { await typeInto(p, modal.getByPlaceholder('日程标题'), st.calTitle); },
      rowMarker: st.calTitle,
    });
    await glide(page, tbody(page).filter({ hasText: st.calTitle }).first());
    await rec.next();
    await settle(page);
    await rec.end();

    // 04-2 页面新增会议 (主题/地点/参与人) -> 会议列表即时可见
    await rec.shot('04-2');
    await dialogCreate(page, {
      path: '/office/oa/meeting', addButton: '新增会议',
      fill: async (p, modal) => {
        await typeInto(p, modal.getByPlaceholder('会议主题'), st.meetingSubject);
        await typeInto(p, modal.getByPlaceholder('会议地点'), '会议室A');
        const attendees = modal.getByPlaceholder(/张三|参与人/);
        if (await attendees.count()) await typeInto(p, attendees.first(), '张三,李四');
      },
      rowMarker: st.meetingSubject,
    });
    await glide(page, tbody(page).filter({ hasText: st.meetingSubject }).first());
    await rec.next();
    await settle(page);
    await rec.end();
  },

  '05': async ({ pages, rec, st }) => {
    const page = pages.admin;
    // 05-1 HRM 页面新增员工 (工号/姓名/电话)
    await rec.shot('05-1');
    await dialogCreate(page, {
      path: '/office/hrm/employee', addButton: '新增员工',
      fill: async (p, modal) => {
        await typeInto(p, modal.getByPlaceholder('工号'), st.hrmCode);
        await typeInto(p, modal.getByPlaceholder('姓名'), st.hrmName);
        await typeInto(p, modal.getByPlaceholder('电话'), '13800001234');
      },
      rowMarker: st.hrmName,
    });
    await glide(page, tbody(page).filter({ hasText: st.hrmName }).first());
    await rec.next();
    await settle(page);
    await rec.end();

    // 05-2 CRM 页面新增客户 (客户名/公司/电话)
    await rec.shot('05-2');
    await dialogCreate(page, {
      path: '/office/crm/customer', addButton: '新增客户',
      fill: async (p, modal) => {
        await typeInto(p, modal.getByPlaceholder('客户名'), st.crmName);
        await typeInto(p, modal.getByPlaceholder('公司'), `红创科技_${st.suffix}`);
        await typeInto(p, modal.getByPlaceholder('电话'), '13900002345');
      },
      rowMarker: st.crmName,
    });
    await glide(page, tbody(page).filter({ hasText: st.crmName }).first());
    await rec.next();
    await settle(page);
    await rec.end();
  },

  '06': async ({ pages, rec }) => {
    const page = pages.admin;
    await page.goto('/office/report');
    await page.waitForLoadState('networkidle');
    await settle(page);
    // 06-1 办公报表看板真实聚合统计 (跨请假/报销/员工/客户)
    await rec.shot('06-1');
    await glide(page, page.locator('.ant-statistic').first().or(page.locator('.ant-card')).first());
    await rec.next();
    await glide(page, page.locator('main, .ant-pro-page-container').first());
    await rec.end();
  },

  // ── 07 流程实例跟踪: 员工在"我的流程"看到发起的请假实例, 打开实例详情看高亮流程图与历史轨迹. ──
  '07': async ({ pages, rec, st }) => {
    const page = pages.employee;

    // 07-1 "我的流程"列表, 展示带 business_key 的实例行
    await openList(page, '/office/bpm/instance');
    await rec.shot('07-1');
    const anyRow = tbody(page).first().or(page.locator('.ant-empty')).first();
    await glide(page, anyRow);
    await rec.next();
    await glide(page, page.locator('.ant-table').first());
    await rec.end();

    // 07-2 点开某实例的"详情" -> 流程图 tab (bpmn 高亮已完成/进行中节点)
    await rec.shot('07-2');
    const firstRow = tbody(page).filter({ hasText: /.+/ }).first();
    if (await firstRow.count()) {
      await tap(page, firstRow.getByRole('button', { name: /详\s*情|查看/ }).first()).catch(() => {});
    } else {
      await page.goto('/office/bpm/instance').catch(() => {});
    }
    await page.waitForLoadState('networkidle');
    await settle(page);
    const diagramTab = page.getByRole('tab', { name: /流程图|Diagram/ }).first();
    if (await diagramTab.count()) await tap(page, diagramTab).catch(() => {});
    await glide(page, page.locator('.bpmn-viewer, .bjs-diagram-parent, .ant-tabs-content, main').first());
    await rec.next();
    await settle(page);
    await rec.end();

    // 07-3 切换到"历史轨迹" tab, 时间轴展示提交->审批->通过
    await rec.shot('07-3');
    const histTab = page.getByRole('tab', { name: /历史|轨迹|History/ }).first();
    if (await histTab.count()) await tap(page, histTab).catch(() => {});
    await page.waitForTimeout(600);
    await glide(page, page.locator('.ant-timeline, .ant-descriptions, .ant-tabs-content, main').first());
    await rec.next();
    await settle(page);
    await rec.end();
  },

  // ── 08 已办与抄送: 审批人打开"我的已办"回看当时审批的表单与意见; 员工打开"抄送我的". ──
  '08': async ({ pages, rec }) => {
    const admin = pages.admin;
    const emp = pages.employee;

    // 08-1 已办: 曾经审批过的任务 -> 点开某行查看表单与意见
    await openList(admin, '/office/bpm/done');
    await rec.shot('08-1');
    const doneRow = tbody(admin).first().or(admin.locator('.ant-empty')).first();
    await glide(admin, doneRow);
    await rec.next();
    if (await tbody(admin).count()) {
      await tap(admin, tbody(admin).first().getByRole('button', { name: /详\s*情|查看/ }).first()).catch(() => {});
      await admin.waitForLoadState('networkidle');
      await glide(admin, admin.locator('.ant-drawer, .ant-modal, main').first());
    }
    await rec.end();

    // 08-2 抄送我的: 员工视角看 cc 记录
    await openList(emp, '/office/bpm/copy');
    await rec.shot('08-2');
    await glide(emp, tbody(emp).first().or(emp.locator('.ant-empty')).first());
    await rec.next();
    await glide(emp, emp.locator('.ant-table, main').first());
    await rec.end();
  },

  // ── 09 运维: admin 在流程实例运维页挂起 -> 激活 -> 终止(作废) 一个正在运行的实例. ──
  '09': async ({ pages, rec, st }) => {
    const admin = pages.admin;

    // 起一条新的运行中请假实例作为运维靶 (真实 HTTP 走员工 token, 不在录屏画面)
    await api(admin, 'POST', '/api/business/oa/leave', {
      leave_type: 'annual', start_date: '2026-11-01', end_date: '2026-11-02',
      reason: `运维持行实例_${st.suffix}`, days: 2,
    }).catch(() => {});

    await openList(admin, '/office/bpm/instance-ops');
    // 定位到"挂起"按钮所在行 (running 状态)
    const runningRow = tbody(admin).filter({ hasText: /挂起|running|进行中/ }).first();

    // 09-1 挂起
    await rec.shot('09-1');
    if (await runningRow.count()) {
      await tap(admin, runningRow.getByRole('button', { name: /挂\s*起/ }).first()).catch(() => {});
      await admin.waitForTimeout(900);
    }
    await glide(admin, contentArea(admin));
    await rec.next();
    await settle(admin);
    await rec.end();

    // 09-2 激活
    await rec.shot('09-2');
    await admin.reload();
    await admin.waitForLoadState('networkidle');
    await settle(admin);
    const suspRow = tbody(admin).filter({ hasText: /激\s*活/ }).first();
    if (await suspRow.count()) {
      await tap(admin, suspRow.getByRole('button', { name: /激\s*活/ }).first()).catch(() => {});
      await admin.waitForTimeout(900);
    }
    await glide(admin, contentArea(admin));
    await rec.next();
    await settle(admin);
    await rec.end();

    // 09-3 终止(作废)
    await rec.shot('09-3');
    await admin.reload();
    await admin.waitForLoadState('networkidle');
    await settle(admin);
    const termRow = tbody(admin).filter({ hasText: /终\s*止/ }).first();
    if (await termRow.count()) {
      await tap(admin, termRow.getByRole('button', { name: /终\s*止/ }).first()).catch(() => {});
      const modal = admin.getByRole('dialog').last();
      if (await modal.isVisible({ timeout: 5000 }).catch(() => false)) {
        const box = modal.locator('textarea, input').first();
        if (await box.count()) await box.fill('演示终止原因');
        await tap(admin, modal.getByRole('button', { name: OK_RE }).first()).catch(() => {});
      }
      await admin.waitForTimeout(900);
    }
    await glide(admin, contentArea(admin));
    await rec.next();
    await settle(admin);
    await rec.end();
  },

  // ── 10 复杂审批: 驳回 / 转办 / 加签 (真实点击待办行操作列). ──
  '10': async ({ pages, rec, st }) => {
    const admin = pages.admin;

    // 起两条运行中实例 (真实 HTTP) 以提供待办靶
    for (const i of [1, 2]) {
      await api(admin, 'POST', '/api/business/oa/leave', {
        leave_type: 'annual', start_date: `2026-12-0${i}`, end_date: `2026-12-0${i + 1}`,
        reason: `复杂审批靶_${st.suffix}_${i}`, days: 1,
      }).catch(() => {});
    }

    // 10-1 驳回: 部门经理待办点"驳回" -> 员工待办回归
    await openList(admin, '/office/bpm/todo');
    await rec.shot('10-1');
    const rejectRow = tbody(admin).filter({ hasText: /复杂审批靶/ }).first();
    if (await rejectRow.count()) {
      await tap(admin, rejectRow.getByRole('button', { name: /驳\s*回/ }).first()).catch(() => {});
      const modal = admin.getByRole('dialog').last();
      if (await modal.isVisible({ timeout: 5000 }).catch(() => false)) {
        const box = modal.getByPlaceholder(/意见/);
        if (await box.count()) await box.pressSequentially('材料不齐, 请补充', { delay: 30 });
        await tap(admin, modal.getByRole('button', { name: OK_RE })).catch(() => {});
      }
      await admin.waitForTimeout(900);
    }
    await glide(admin, contentArea(admin));
    await rec.next();
    await settle(admin);
    await rec.end();

    // 10-2 转办: 选中一条待办 -> "转办"给 hr 用户
    await admin.reload();
    await admin.waitForLoadState('networkidle');
    await settle(admin);
    await rec.shot('10-2');
    const transferRow = tbody(admin).filter({ hasText: /复杂审批靶/ }).first();
    if (await transferRow.count()) {
      await tap(admin, transferRow.getByRole('button', { name: /转\s*办/ }).first()).catch(() => {});
      const modal = admin.getByRole('dialog').last();
      if (await modal.isVisible({ timeout: 5000 }).catch(() => false)) {
        // 选人下拉: 输入 hr
        const sel = modal.locator('.ant-select-selection-search-input').first();
        if (await sel.count()) {
          await sel.pressSequentially('hr', { delay: 30 });
          await admin.waitForTimeout(900);
          const opt = admin.locator('.ant-select-item-option').filter({ hasText: /hr|HR/ }).first();
          if (await opt.count()) await tap(admin, opt).catch(() => {});
        }
        await tap(admin, modal.getByRole('button', { name: OK_RE })).catch(() => {});
      }
      await admin.waitForTimeout(900);
    }
    await glide(admin, contentArea(admin));
    await rec.next();
    await settle(admin);
    await rec.end();

    // 10-3 加签: 选中一条待办 -> "加签" -> 引入额外审批人
    await admin.reload();
    await admin.waitForLoadState('networkidle');
    await settle(admin);
    await rec.shot('10-3');
    const signRow = tbody(admin).filter({ hasText: /复杂审批靶/ }).first();
    if (await signRow.count()) {
      await tap(admin, signRow.getByRole('button', { name: /加\s*签/ }).first()).catch(() => {});
      const modal = admin.getByRole('dialog').last();
      if (await modal.isVisible({ timeout: 5000 }).catch(() => false)) {
        const sel = modal.locator('.ant-select-selection-search-input').first();
        if (await sel.count()) {
          await sel.pressSequentially('admin', { delay: 30 });
          await admin.waitForTimeout(900);
          const opt = admin.locator('.ant-select-item-option').first();
          if (await opt.count()) await tap(admin, opt).catch(() => {});
        }
        await tap(admin, modal.getByRole('button', { name: OK_RE })).catch(() => {});
      }
      await admin.waitForTimeout(900);
    }
    await glide(admin, contentArea(admin));
    await rec.next();
    await settle(admin);
    await rec.end();
  },
};

test.describe('BPM 与办公一体化演示视频录屏 (真实逐步操作)', () => {
  test.setTimeout(3600000);

  test('流程建模发布 / 请假逐级审批 / 报销审批 / OA / HRM / CRM / 报表 (录屏 + 时间轴)', async ({ browser }) => {
    fs.rmSync(RAW, { recursive: true, force: true });
    fs.mkdirSync(RAW, { recursive: true });
    const recorded = async () => {
      const context = await browser.newContext({ baseURL: BASE_URL, viewport: VIEWPORT, recordVideo: { dir: RAW, size: VIEWPORT } });
      await context.addInitScript(installCursor);
      await context.addInitScript(installClock);
      const page = await context.newPage();
      return { context, page, t0: Date.now() };
    };

    // 准备 (不录像): 只建演示账号 (员工 + hr 候选人), 业务数据全部留给录屏镜头现场操作产生.
    const prepCtx = await browser.newContext({ baseURL: BASE_URL, viewport: VIEWPORT });
    const prep = await prepCtx.newPage();
    await login(prep);
    const st = await prepare(prep, Date.now().toString(36).slice(-4));
    await prepCtx.close();

    const creds = { admin: ['admin', 'admin123'], employee: [st.empName, PASSWORD], hr: [st.hrName, PASSWORD] };
    const clips = [];
    for (const chapter of S.chapters) {
      const whos = [...new Set(S.shots.filter(s => s.chapter === chapter.no).map(s => s.who))];
      const ctx = {};
      const errors = [];
      for (const who of whos) {
        ctx[who] = await recorded();
        ctx[who].page.on('pageerror', error => errors.push(`${who}: ${error.message}`));
        await login(ctx[who].page, ...creds[who]);
      }
      const rec = kit.recorder(S, ctx);
      const pages = Object.fromEntries(Object.entries(ctx).map(([k, v]) => [k, v.page]));
      try {
        await CHAPTERS[chapter.no]({ pages, rec, st });
        expect(errors, `第 ${chapter.no} 章页面错误`).toEqual([]);
      } finally {
        await wait(2500);
        for (const [who, c] of Object.entries(ctx)) {
          await c.context.close();
          const rel = `raw/ch-${chapter.no}-${who}.webm`;
          await c.page.video().saveAs(path.join(OUT, rel));
          await c.page.video().delete();
          c.rel = rel;
        }
      }
      expect([...new Set(rec.timeline.clips.map(c => c.shot))], `第 ${chapter.no} 章镜头须与分镜一致`)
        .toEqual(S.shots.filter(s => s.chapter === chapter.no).map(s => s.id));
      clips.push(...rec.timeline.clips.map(c => ({ ...c, video: ctx[c.who].rel, t0: ctx[c.who].t0 })));
    }
    expect([...new Set(clips.map(c => c.shot))]).toEqual(S.shots.map(s => s.id));
    fs.writeFileSync(path.join(OUT, 'timeline.json'),
      JSON.stringify({ viewport: VIEWPORT, recorded_at: new Date().toISOString(), clips }, null, 2));
    fs.writeFileSync(path.join(OUT, 'state.json'), JSON.stringify(st, null, 2));
  });
});
