const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C07e 会议行动受控重开: 已关闭行动须凭"重开依据 + 真实证据 + 独立审批人"重新处理, 沿用问题受控重开闭环.
// 完整闭环: admin 提交完成 -> 独立审批人(第二真实上下文)批准关闭(closed) -> admin 申请重开(in_review, review_action=action_reopen,
// 界面"重开审批中"volcano 徽标) -> 审批人可"驳回重开并维持关闭"(回到 closed) 或再次申请后"批准重开并重新打开行动"(回到 open).
// 写门控事实(自审批 403, 未关闭不可重开)由后端 SQLite 用例覆盖; 浏览器侧核验界面可见的按钮/徽标/状态翻转与服务端回显.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c07e');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
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

async function mutate(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version });
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
}

async function save(page, title) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body.data;
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 抽屉内靠下的行动台账需先滚动进视口再截整页, 否则截图漏掉台账.
async function rowShot(page, text, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const target = row(page, text);
  await target.scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function actionRow(page, id, aid) {
  const gov = await api(page, 'GET', base(id) + '/governance');
  return gov.actions.find(a => a.id === aid);
}

// admin 提交行动完成(结果+证据+独立审批人), 供审批人核验.
async function submitComplete(page, id, action, evidenceCode, reviewerName) {
  await row(page, action.title).getByRole('button', { name: '提交完成', exact: true }).click();
  const form = modal(page, '提交行动完成');
  await form.locator('#result').fill('已按规范补全并附实测记录.');
  await choose(page, form, 'evidence_ids', evidenceCode);
  await choose(page, form, 'reviewer_id', reviewerName);
  await save(page, '提交行动完成');
}

// admin 申请重开(重开依据+证据+独立审批人).
async function submitReopen(page, id, action, evidenceCode, reviewerName) {
  await row(page, action.title).getByRole('button', { name: '申请重开', exact: true }).click();
  const form = modal(page, '申请行动重开');
  await form.locator('#reason').fill('复盘发现该行动结论遗漏关键缺陷, 需重新处理.');
  await choose(page, form, 'evidence_ids', evidenceCode);
  await choose(page, form, 'reviewer_id', reviewerName);
  await save(page, '申请行动重开');
}

test.describe('C07e 会议行动受控重开浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('关闭 -> 受控重开 -> 独立审批批准重开, 徽标与状态界面可见并服务端回显', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    // 合成独立审批人(含 pms:quality:approve 的最小角色 + 第二用户), 不改内置账号.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `重开审批${suffix}`, role_key: `ropen_ap_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `ropen_ap_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const approverName = `ropen_${suffix}`;
    const approverPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '行动重开独立审批人', password: approverPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C07e重开E2E合成审批人' });
    const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C07E-${suffix}`, name: `会议行动受控重开验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });

    const evidenceCode = `ROPEN-${suffix}`;
    const evidence = (await mutate(page, id, '/governance/documents',
      { code: evidenceCode, title: '重开实测记录', filename: 'reopen.txt', content: '实测记录: 复盘确认原结论存在遗漏, 需重开.' })).result;
    const meeting = (await mutate(page, id, '/governance/meetings',
      { title: '质量复盘会', held_on: '2026-09-10', minutes: '对已关闭行动复核闭环质量, 发现遗漏即受控重开.', attendee_ids: [adminId] })).result;
    const action = (await mutate(page, id, `/governance/meetings/${meeting.id}/actions`,
      { title: `补充联调报告-${suffix}`, owner_id: adminId, due_date: '2026-09-20' })).result;

    // 阶段1: admin 提交完成 -> 第二上下文审批人批准关闭 -> closed.
    await open(page, id, '需求与治理', '会议行动');
    await submitComplete(page, id, action, evidenceCode, approverName);
    expect((await actionRow(page, id, action.id)).status).toBe('in_review');

    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const approver = await context.newPage();
    approver.on('pageerror', error => errors.push(error.message));
    await login(approver, approverName, approverPwd);
    await open(approver, id, '需求与治理', '会议行动');
    await expect(row(approver, action.title).getByRole('button', { name: '批准关闭', exact: true })).toBeVisible();
    await row(approver, action.title).getByRole('button', { name: '批准关闭', exact: true }).click();
    const closeForm = modal(approver, '核验通过并关闭行动');
    await closeForm.locator('#reason').fill('已独立核对实测记录与证据版本, 同意关闭行动.');
    await save(approver, '核验通过并关闭行动');
    expect((await actionRow(page, id, action.id)).status).toBe('closed');

    // 阶段2: admin 申请重开 -> in_review + review_action=action_reopen, 台账"重开审批中"徽标界面可见.
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, action.title).getByRole('button', { name: '申请重开', exact: true })).toBeVisible();
    await submitReopen(page, id, action, evidenceCode, approverName);
    const reopened = await actionRow(page, id, action.id);
    expect(reopened.status).toBe('in_review');
    expect(reopened.review_action).toBe('action_reopen');
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, action.title).getByText('重开审批中', { exact: true })).toBeVisible();
    await rowShot(page, action.title, 'c07e-1-reviewing-badge.png');

    // 阶段3: 审批人"驳回重开并维持关闭" -> 回到 closed (演示两种裁决之一).
    await open(approver, id, '需求与治理', '会议行动');
    await expect(row(approver, action.title).getByRole('button', { name: '批准重开', exact: true })).toBeVisible();
    await row(approver, action.title).getByRole('button', { name: '驳回', exact: true }).click();
    const rejectForm = modal(approver, '驳回重开并维持关闭');
    await rejectForm.locator('#reason').fill('重开证据不足以推翻原核验结论, 维持关闭.');
    await save(approver, '驳回重开并维持关闭');
    const rejected = await actionRow(page, id, action.id);
    expect(rejected.status).toBe('closed');
    expect(rejected.review_action).toBeFalsy();

    // 阶段4: admin 再次申请重开 -> 审批人"批准重开并重新打开行动" -> open.
    await open(page, id, '需求与治理', '会议行动');
    await submitReopen(page, id, action, evidenceCode, approverName);
    expect((await actionRow(page, id, action.id)).review_action).toBe('action_reopen');

    await open(approver, id, '需求与治理', '会议行动');
    await row(approver, action.title).getByRole('button', { name: '批准重开', exact: true }).click();
    const approveForm = modal(approver, '批准重开并重新打开行动');
    await approveForm.locator('#reason').fill('缺陷复现确认, 批准重新打开行动跟进.');
    await shot(approver, 'c07e-2-approver-reopen.png');
    await save(approver, '批准重开并重新打开行动');

    const opened = await actionRow(page, id, action.id);
    expect(opened.status).toBe('open');
    expect(opened.review_action).toBeFalsy();
    expect(opened.reopen_decision).toBe('approved');

    // 服务端回显确认界面状态与存储一致.
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, action.title).getByText('待处理', { exact: false })).toBeVisible();
    await rowShot(page, action.title, 'c07e-3-reopened-open.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
    await context.close();
  });
});
