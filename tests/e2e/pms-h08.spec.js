const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H08 风险超阈值自动升级: 界面登记 5x5 重大风险 -> 评分自动触发升级, 台账显示"待升级确认 / steering" ->
// 未经独立确认自行缓解被 409 门控, 登记人自确认被 403 -> 由登记人之外的独立质量审批人"确认升级处置" ->
// 徽标转为"升级已确认", 此后缓解门控解除(真实HTTP 200). 双真实浏览器上下文.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h08');
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

// 断言 200 并返回信封 data (命令类写操作的数据在 data.result 下).
async function mutateData(page, id, suffix, data = {}) {
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

async function riskRow(page, id, rid) {
  const gov = await api(page, 'GET', base(id) + '/governance');
  return gov.risks.find(r => r.id === rid);
}

test.describe('H08 风险超阈值自动升级浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('重大风险自动升级 -> 自行缓解被门控 -> 独立审批人确认 -> 门控解除', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    // 合成一个只读且具备质量审批权限的独立审批人和独立用户, 不改动任何系统内置账号.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `风险升级审批${suffix}`, role_key: `rsk_esc_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `rsk_esc_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const approverName = `rsk_${suffix}`;
    const approverPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '风险升级独立审批人', password: approverPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'H08 E2E合成独立审批人' });
    const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H08-${suffix}`, name: `风险升级门控验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });

    // 证据文档: 供缓解门控解除后实际记录措施时引用.
    const evidence = (await mutateData(page, id, '/governance/documents',
      { code: `ESC-${suffix}`, title: '备选供应商资质审核', filename: 'vendor.txt', content: '备选供应商资质与来料检验记录已归档.' })).result;

    // 界面登记 5x5 重大风险 (评分25 >= 阈值16, 应自动升级到 steering 层待确认).
    const title = `关键交付物料断供-${suffix}`;
    await open(page, id, '需求与治理', '风险与问题');
    await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
    const riskForm = modal(page, '登记项目风险');
    await riskForm.locator('#title').fill(title);
    await riskForm.locator('#probability').fill('5');
    await riskForm.locator('#impact').fill('5');
    await choose(page, riskForm, 'owner_id', 'admin');
    await riskForm.locator('#mitigation').fill('启动备选供应商并加严来料检验.');
    await riskForm.locator('#due_date').fill('2026-10-10');
    const created = await save(page, '登记项目风险');
    const rid = created.result.id;

    // 服务端读模型: 自动升级已触发, 门控生效.
    expect(created.result.score, '评分=概率x影响').toBe(25);
    expect(created.result.escalated, '超阈值自动标记升级').toBe(true);
    expect(created.result.escalation_state).toBe('pending');
    expect(created.result.escalation_level).toBe('steering');

    // 截图1: 台账"超阈值升级"列显示"待升级确认 / steering".
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, title)).toBeVisible();
    await expect(row(page, title).getByText('待升级确认 / steering')).toBeVisible();
    // 登记人本人看不到"确认升级处置"入口.
    await expect(row(page, title).getByRole('button', { name: '确认升级处置', exact: true })).toHaveCount(0);
    await shot(page, 'h08-1-pending-escalation.png');

    // 门控验证(真实HTTP): 未经独立确认自行缓解 -> 409; 登记人自确认升级 -> 403.
    const blockedMitigate = await mutateCode(page, id, `/governance/risks/${rid}/mitigate`,
      { mitigation: '已联系备选供应商', evidence_ids: [evidence.id] });
    expect(blockedMitigate.code, '升级未确认前不得自行缓解').toBe(409);
    const selfAck = await mutateCode(page, id, `/governance/risks/${rid}/escalate`,
      { decision: 'approved', note: '登记人自确认' });
    expect(selfAck.code, '登记人不得自确认升级').toBe(403);

    // 独立审批人第二浏览器上下文登录, 亲自确认升级处置.
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const approver = await context.newPage();
    approver.on('pageerror', error => errors.push(error.message));
    await login(approver, approverName, approverPwd);
    await open(approver, id, '需求与治理', '风险与问题');
    // 截图2: 审批人视角出现"确认升级处置"入口.
    await expect(row(approver, title).getByRole('button', { name: '确认升级处置', exact: true })).toBeVisible();
    await shot(approver, 'h08-2-approver-ack.png');
    await row(approver, title).getByRole('button', { name: '确认升级处置', exact: true }).click();
    const ackForm = modal(approver, '确认风险升级');
    await choose(approver, ackForm, 'decision', '确认升级并责成处置');
    await ackForm.locator('#note').fill('管理层责成启动备选供应商并加严来料检验, 同意按升级处置.');
    await save(approver, '确认风险升级');

    // 徽标翻转为"升级已确认".
    const acked = await riskRow(page, id, rid);
    expect(acked.escalation_state, '升级已由独立审批人确认').toBe('acknowledged');
    expect(acked.escalation_ack_by, '确认人为独立审批人').toBe(approverId);
    await open(approver, id, '需求与治理', '风险与问题');
    await expect(row(approver, title).getByText('升级已确认')).toBeVisible();
    await expect(row(approver, title).getByRole('button', { name: '确认升级处置', exact: true })).toHaveCount(0);
    await shot(approver, 'h08-3-acknowledged.png');

    // 门控解除(真实HTTP): 确认后可记录缓解措施并成功.
    const released = await mutateCode(page, id, `/governance/risks/${rid}/mitigate`,
      { mitigation: '已联系备选供应商并完成来料加严检验', evidence_ids: [evidence.id] });
    expect(released.code, '升级已确认后应可缓解').toBe(200);
    expect(released.data.result.status).toBe('mitigated');
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, title).getByText('升级已确认')).toBeVisible();
    await shot(page, 'h08-4-mitigated.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
    await context.close();
  });
});
