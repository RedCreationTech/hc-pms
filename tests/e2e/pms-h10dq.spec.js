const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H10 延伸: DQ 质量检查闭环汇总只读派生洞察 (免迁移, 无新命令/新kind/新路由/不构成门控).
// 真实HTTP建一个交付件文档 -> 登记 4 条 DQ 关键任务覆盖 draft/ready/in_review/approved 四态 ->
//   "DQ与局部暂停"页签只读"质量检查闭环汇总"面板按最新状态聚合: DQ 总数 4 / 已签认 25% (1/4) / 必需项全通过 3 /
//   签认审批中 1 / 待提交 1 / 草稿 1; 独立质量审批人在第二真实上下文对其中一条签认通过 (approved 由 in_review 翻入);
//   真实HTTP GET /governance 回显 dq_summary 各字段与面板一致; 只读派生不改变任何逐条 DQ 状态.
//   新项目无 DQ -> 面板空态"暂无 DQ 关键任务"且 GET 回显 available=false/total=0/closure-pct=0.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h10dq');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
// shared/panel 渲染 <section>, 按面板标题(heading)定位到具体面板, 避免与下方 DQ 台账表文本串台.
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
const base = id => `/api/pms/projects/${id}`;

async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

async function api(page, method, url, data, expected = 200) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data });
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

async function command(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  const result = await api(page, 'POST', base(id) + '/governance' + suffix, { ...data, version: project.version });
  return result.result;
}

async function tab(page, name) {
  await drawer(page).getByRole('tab', { name, exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function open(page, id, section, subsection) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  if (section) await tab(page, section);
  if (subsection) await tab(page, subsection);
}

async function shotCard(page, card, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 登记一条 DQ (带一个必需检查项 C-1 并绑定交付件), 返回记录.
async function registerDq(page, id, { code, title, owner_id, deliverable_ids }) {
  return command(page, id, '/dqs', { code, title, owner_id,
    checklist: [{ code: 'C-1', title: '关键检查项', required: true }], deliverable_ids });
}

// 逐项勾选必需检查项 (passed=true) -> 全部必需项通过后 DQ 进入 ready.
async function checkDq(page, id, rid) {
  return command(page, id, `/dqs/${rid}/checks`, { results: [{ code: 'C-1', passed: true, note: '检查通过' }] });
}

async function submitDq(page, id, rid, reviewer_id) {
  return command(page, id, `/dqs/${rid}/submit`, { reviewer_id });
}

test.describe('H10 延伸 DQ 质量检查闭环汇总只读洞察浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('四态 DQ -> 汇总面板聚合签认率与状态, 独立审批人签认后 approved+1 且只读不漂移', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    // 合成只读且具备质量审批权限的独立审批人 (不改任何内置账号); admin 是登记/提交人须避开自审门控.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    const suffix = serial();
    await api(page, 'POST', '/api/system/role', { role_name: `质量签认审批${suffix}`, role_key: `dq_sum_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `dq_sum_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const approverName = `dqsum_${suffix}`;
    const approverPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '质量闭环独立签认人', password: approverPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'H10 DQ 闭环汇总 E2E 合成独立签认人' });
    const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `DQSUM-${suffix}`, name: `DQ质量检查闭环汇总验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });

    // 建一个交付件文档, 四条 DQ 均绑定其最新版本 (不产生 stale/voided 噪声).
    const doc = await command(page, id, '/documents', { code: `DDOC-${suffix}`, title: `交付件-${suffix}`, filename: '交付.txt', content: '确定版本交付件\n' });

    const tDraft = `DQ草稿态-${suffix}`;
    const tReady = `DQ待提交态-${suffix}`;
    const tReview = `DQ审批中态-${suffix}`;
    const tApproved = `DQ签认中态-${suffix}`;
    const d1 = await registerDq(page, id, { code: `DQ1-${suffix}`, title: tDraft, owner_id: adminId, deliverable_ids: [doc.id] });
    const d2 = await registerDq(page, id, { code: `DQ2-${suffix}`, title: tReady, owner_id: adminId, deliverable_ids: [doc.id] });
    const d3 = await registerDq(page, id, { code: `DQ3-${suffix}`, title: tReview, owner_id: adminId, deliverable_ids: [doc.id] });
    const d4 = await registerDq(page, id, { code: `DQ4-${suffix}`, title: tApproved, owner_id: adminId, deliverable_ids: [doc.id] });

    // d2/d3/d4 勾选必需项 -> ready; d3 提交给独立审批人 -> in_review (approved 待第二上下文签认).
    await checkDq(page, id, d2.id);
    await checkDq(page, id, d3.id);
    await checkDq(page, id, d4.id);
    await submitDq(page, id, d3.id, approverId);
    await submitDq(page, id, d4.id, approverId);

    // 面板初态: 总数4, 已签认 0/4=0%, 必需项全通过3, 签认审批中2, 草稿1 (ready 已被 submit 的 d4 转走? 否: d2 仍 ready).
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    const sum = panel(page, 'DQ 质量检查闭环汇总');
    await expect(sum).toBeVisible();
    await expect(sum.getByText(/DQ 总数\s*4/)).toBeVisible();
    await expect(sum.getByText(/已签认\s*0%/)).toBeVisible();
    await expect(sum.getByText(/必需项全通过\s*3/)).toBeVisible();
    await expect(sum.getByText(/签认审批中\s*2/)).toBeVisible();
    await expect(sum.getByText(/待提交\s*1/)).toBeVisible();
    await expect(sum.getByText(/草稿\s*1/)).toBeVisible();
    await expect(sum.getByText(/已退回\s*[1-9]/)).toHaveCount(0);
    await shotCard(page, sum, 'h10dq-1-initial.png');

    // 真实HTTP读模型回显 dq_summary 初始聚合.
    let ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.dq_summary.available, '有 DQ -> available true').toBe(true);
    expect(ws.dq_summary.total, '分母=最新有效版本 DQ 数').toBe(4);
    expect(ws.dq_summary.approved, '初始无已签认').toBe(0);
    expect(ws.dq_summary['in-review'], '审批中 2 (d3,d4)').toBe(2);
    expect(ws.dq_summary.ready, '待提交 1 (d2)').toBe(1);
    expect(ws.dq_summary.draft, '草稿 1 (d1)').toBe(1);
    expect(ws.dq_summary.rejected, '已退回 0').toBe(0);
    expect(ws.dq_summary['required-met'], '必需项全通过 3 (d2,d3,d4)').toBe(3);
    expect(ws.dq_summary['closure-pct'], '签认率 0%').toBe(0);
    expect(ws.dq_summary.stale, '无交付件失效').toBe(0);
    expect(ws.dq_summary.voided, '无交付件作废').toBe(0);

    // 独立审批人在第二真实上下文对 d4 作出签认通过 -> approved+1, in_review-1.
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const approver = await context.newPage();
    approver.on('pageerror', error => errors.push(error.message));
    await login(approver, approverName, approverPwd);
    await command(approver, id, `/dqs/${d4.id}/decision`, { decision: 'approved', reason: '检查项与交付件齐备, 予以签认.' });
    await context.close();

    // 回到登记人视角重载: 已签认 1/4=25%, 审批中降为 1, 其余不变.
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(sum.getByText(/已签认\s*25%\s*\(1\/4\)/)).toBeVisible();
    await expect(sum.getByText(/签认审批中\s*1/)).toBeVisible();
    await expect(sum.getByText(/DQ 总数\s*4/)).toBeVisible();
    await expect(sum.getByText(/必需项全通过\s*3/)).toBeVisible();
    await shotCard(page, sum, 'h10dq-2-approved.png');

    // 真实HTTP读模型终态 + 只读派生不改变逐条 DQ 状态: d4 已签认, d1 仍草稿, d2 仍 ready, d3 仍审批中.
    ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.dq_summary.approved, '签认 1').toBe(1);
    expect(ws.dq_summary['in-review'], '审批中降为 1').toBe(1);
    expect(ws.dq_summary['closure-pct'], '签认率 25%').toBe(25);
    const s1 = ws.dqs.find(d => d.id === d1.id);
    const s2 = ws.dqs.find(d => d.id === d2.id);
    const s3 = ws.dqs.find(d => d.id === d3.id);
    const s4 = ws.dqs.find(d => d.id === d4.id);
    expect(s1.status, '只读派生不改草稿态').toBe('draft');
    expect(s2.status, '只读派生不改待提交态').toBe('ready');
    expect(s3.status, '只读派生不改审批中态').toBe('in_review');
    expect(s4.status, '签认后为 approved').toBe('approved');
    expect(s4.decided_by, '签认人为独立审批人').toBe(approverId);

    // 空态: 新项目无 DQ -> 面板"暂无 DQ 关键任务"且 GET 回显 available=false/total=0/closure-pct=0.
    const empty = await api(page, 'POST', '/api/pms/projects', { project_no: `DQSUME-${suffix}`, name: `DQ闭环汇总空态验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const eid = empty.project_id;
    await open(page, eid, '需求与治理', 'DQ与局部暂停');
    const sumEmpty = panel(page, 'DQ 质量检查闭环汇总');
    await expect(sumEmpty.getByText('暂无 DQ 关键任务, 建立 DQ 并逐项检查签认后可在此查看质量闭环概览.', { exact: true })).toBeVisible();
    await expect(sumEmpty.getByText(/DQ 总数/)).toHaveCount(0);
    await shotCard(page, sumEmpty, 'h10dq-3-empty.png');
    const wsE = await api(page, 'GET', base(eid) + '/governance');
    expect(wsE.dq_summary.available, '无 DQ -> available false').toBe(false);
    expect(wsE.dq_summary.total, '空态总数 0').toBe(0);
    expect(wsE.dq_summary['closure-pct'], '空态签认率 0').toBe(0);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
