const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H09 高影响变更自动升级审批: 界面登记量化影响达阈值(工期>=10天)的变更 -> 提交后自动触发变更控制升级(待独立确认, level ccb) ->
// 指定审核人在未确认前直接批准被 409 门控; 登记人/提交人本人自确认升级被 403; 由登记人之外的独立审批人"确认升级处置" ->
// 徽标转为"升级已确认", 此后批准门控解除(真实批准 200 -> approved). 全程真实点击 + 真实 HTTP 门控, 双浏览器上下文.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h09esc');
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

// 只取返回信封 code, 不做 200 断言, 用于验证门控(409/403)与解除门控(200).
async function callCode(page, method, url, data) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data });
  return response.json();
}

async function mutateCode(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  return callCode(page, 'POST', base(id) + suffix, { ...data, version: project.version });
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

// 门控保存: 点击保存后预期命中 409/403, 弹窗不关闭且内联告警可见; 返回响应信封.
async function saveExpectGate(page, title, expectedCode) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: 期望门控码`).toBe(expectedCode);
  await expect(form).toBeVisible();
  await expect(form.locator('[role="alert"]')).toBeVisible();
  return body;
}

async function dismiss(page, title) {
  await modal(page, title).getByRole('button', { name: /返\s*回/ }).click();
  await expect(modal(page, title)).toBeHidden();
  await page.waitForLoadState('networkidle');
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function changeRow(page, id, rid) {
  const gov = await api(page, 'GET', base(id) + '/governance');
  return gov.changes.find(c => c.id === rid);
}

async function fillChange(page, { title, days, cost }) {
  await drawer(page).getByRole('button', { name: '提出项目变更', exact: true }).click();
  const form = modal(page, '提出项目变更');
  await form.locator('#title').fill(title);
  await form.locator('#reason').fill('合同范围调整');
  await form.locator('#scope_impact').fill('新增两台设备');
  await form.locator('#schedule_impact').fill('工期相应延长');
  await form.locator('#cost_impact').fill('需要重新估价');
  await form.locator('#quality_impact').fill('追加联调测试');
  await form.locator('#resource_impact').fill('追加一名工程师');
  if (days != null) await form.locator('#schedule_impact_days').fill(String(days));
  if (cost != null) await form.locator('#cost_impact_amount').fill(String(cost));
}

test.describe('H09 高影响变更自动升级审批浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('高影响变更提交即自动升级 -> 批准命中409门控 -> 独立确认 -> 批准成功', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    // 合成只读且具备质量审批权限的独立审批人 (与登记人/提交人 admin 分离), 不改动任何系统内置账号.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `变更升级审批${suffix}`, role_key: `chg_esc_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `chg_esc_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const approverName = `chg_${suffix}`;
    const approverPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '变更升级独立审批人', password: approverPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'H09 E2E合成独立审批人' });
    const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H09ESC-${suffix}`, name: `变更升级门控验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });

    // 1) 界面登记高影响变更 (工期12天 >= 10 阈值), 保存前截图.
    const title = `重大范围变更-${suffix}`;
    await open(page, id, '需求与治理', '变更控制');
    await fillChange(page, { title, days: 12 });
    await shot(page, 'h09esc-1-dialog-quantify.png');
    const created = await save(page, '提出项目变更');
    const rid = created.result.id;
    expect(created.result.schedule_impact_days).toBe(12);
    expect((await changeRow(page, id, rid)).change_high_impact, '高影响经读模型派生').toBe(true);
    expect(created.result.escalated, '草稿阶段尚未升级').toBeFalsy();

    // 2) 界面提交独立审批 (选择独立审批人为审核人) -> 提交即自动触发变更控制升级 pending.
    await drawer(page).locator('tbody tr:visible').filter({ hasText: title }).first()
      .getByRole('button', { name: '提交审批', exact: true }).click();
    const submitForm = modal(page, '提交独立审批');
    await choose(page, submitForm, 'reviewer_id', '变更升级独立审批人');
    await save(page, '提交独立审批');

    const submitted = await changeRow(page, id, rid);
    expect(submitted.status, '提交后进入评审').toBe('in_review');
    expect(submitted.escalated, '高影响变更提交即自动升级').toBe(true);
    expect(submitted.escalation_state).toBe('pending');
    expect(submitted.escalation_level).toBe('ccb');

    // 3) 台账"变更控制升级"列显示"待独立确认"; 登记人 admin 看不到"确认升级处置"入口.
    await open(page, id, '需求与治理', '变更控制');
    await expect(row(page, title).getByText('待独立确认')).toBeVisible();
    await expect(row(page, title).getByRole('button', { name: '确认升级处置', exact: true })).toHaveCount(0);
    await shot(page, 'h09esc-2-pending-escalation.png');

    // 门控验证 (真实HTTP): 登记人/提交人 admin 自确认升级 -> 403.
    const selfAck = await mutateCode(page, id, `/governance/changes/${rid}/escalation`, { decision: 'approved', note: '登记人自确认' });
    expect(selfAck.code, '登记人/提交人不得自确认升级').toBe(403);

    // 4) 独立审批人第二上下文登录, 先尝试直接批准 -> 命中409升级门控 (弹窗内联告警不关闭).
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const approver = await context.newPage();
    approver.on('pageerror', error => errors.push(error.message));
    await login(approver, approverName, approverPwd);
    await open(approver, id, '需求与治理', '变更控制');
    await expect(row(approver, title).getByRole('button', { name: '确认升级处置', exact: true })).toBeVisible();
    await row(approver, title).getByRole('button', { name: '批准', exact: true }).click();
    const decisionForm = modal(approver, '批准评审');
    await decisionForm.locator('#reason').fill('试图未确认直接批准');
    await saveExpectGate(approver, '批准评审', 409);
    await expect(decisionForm.getByText(/尚未完成变更控制升级独立确认/)).toBeVisible();
    await shot(approver, 'h09esc-3-approve-gated-409.png');
    await dismiss(approver, '批准评审');

    // 5) 审批人确认升级处置 -> acknowledged; 徽标翻转"升级已确认".
    await row(approver, title).getByRole('button', { name: '确认升级处置', exact: true }).click();
    const ackForm = modal(approver, '确认变更升级');
    await choose(approver, ackForm, 'decision', '确认升级并责成处置');
    await ackForm.locator('#note').fill('变更控制委员会确认升级成立, 责成补充影响评估与回退方案后放行.');
    await save(approver, '确认变更升级');
    const acked = await changeRow(page, id, rid);
    expect(acked.escalation_state, '升级已由独立审批人确认').toBe('acknowledged');
    expect(acked.escalation_ack_by, '确认人为独立审批人').toBe(approverId);
    await open(approver, id, '需求与治理', '变更控制');
    await expect(row(approver, title).getByText('升级已确认')).toBeVisible();
    await expect(row(approver, title).getByRole('button', { name: '确认升级处置', exact: true })).toHaveCount(0);
    await shot(approver, 'h09esc-4-acknowledged.png');

    // 6) 门控解除 -> 审批人批准成功 -> approved.
    await row(approver, title).getByRole('button', { name: '批准', exact: true }).click();
    const approveForm = modal(approver, '批准评审');
    await approveForm.locator('#reason').fill('升级已确认, 独立批准.');
    await save(approver, '批准评审');
    const final = await changeRow(page, id, rid);
    expect(final.status, '确认后批准成功').toBe('approved');
    await open(approver, id, '需求与治理', '变更控制');
    await expect(row(approver, title).getByText('升级已确认')).toBeVisible();
    await shot(approver, 'h09esc-5-approved.png');

    await context.close();
    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
