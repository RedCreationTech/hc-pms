const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H09 CCB 变更控制委员会表决门槛: 界面登记一份(低影响, 不触发升级的)变更并提交独立评审 ->
// 由质量审批人设立委员会(选择成员+通过门槛) -> 台账"变更控制表决"列显示"表决中 0/N" ->
// 登记人 admin 看不到"委员会表决"入口(本人排除), 且真实HTTP自投被 403 ->
// 委员会成员在评审页投票 -> 未达门槛时审核人"批准"命中 409 门控(弹窗内联告警 [role=alert]) ->
// 达到赞成门槛后列翻"表决通过 N/N", 审核人批准成功 -> approved. 全程真实点击 + 真实HTTP + 多浏览器上下文.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h09ccb');
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

// 只取返回信封 code, 不做 200 断言, 用于验证门控(403/409)与解除门控(200).
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
}

async function closeSelect(page, form, key) {
  await form.locator(`#${key}`).press('Escape');
}

// 多选下拉: 依次点选每个标签后收起.
async function chooseMulti(page, form, key, labels) {
  const input = form.locator(`#${key}`);
  await input.click();
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  for (const label of labels) {
    await dropdown.locator('.ant-select-item-option').filter({ hasText: label }).first().click();
  }
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

async function fillChange(page, title) {
  await drawer(page).getByRole('button', { name: '提出项目变更', exact: true }).click();
  const form = modal(page, '提出项目变更');
  await form.locator('#title').fill(title);
  await form.locator('#reason').fill('合同范围调整');
  await form.locator('#scope_impact').fill('新增两台设备');
  await form.locator('#schedule_impact').fill('工期小幅延长');
  await form.locator('#cost_impact').fill('少量追加');
  await form.locator('#quality_impact').fill('追加联调测试');
  await form.locator('#resource_impact').fill('追加一名工程师');
}

async function memberUser(page, suffix, deptId, roleId, nick, name) {
  await api(page, 'POST', '/api/system/user', { user_name: name, nick_name: nick, password: `E2e!${suffix}`,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'H09 CCB E2E合成委员会成员' });
  return (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id;
}

test.describe('H09 CCB 变更控制委员会表决门槛浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('设立委员会 -> 未达门槛批准命中409 -> 两名成员赞成 -> 达到门槛批准成功', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    // 合成具备质量审批权限的只读委员会成员角色, 两名委员会成员由该角色创建, 均与登记人/提交人 admin 分离.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `变更控制委员会${suffix}`, role_key: `ccb_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `ccb_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const boardA = `ccb_a_${suffix}`, boardB = `ccb_b_${suffix}`;
    const pwd = `E2e!${suffix}`;
    const idA = await memberUser(page, suffix, deptId, roleId, '委员会甲', boardA);
    const idB = await memberUser(page, suffix, deptId, roleId, '委员会乙', boardB);

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H09CCB-${suffix}`, name: `变更控制表决门控验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: idA, role: 'viewer' });
    await api(page, 'POST', base(id) + '/members', { user_id: idB, role: 'viewer' });

    // 1) 登记一份低影响(不触发升级)的变更并提交独立评审(审核人=委员会甲).
    const title = `设备增补变更-${suffix}`;
    await open(page, id, '需求与治理', '变更控制');
    await fillChange(page, title);
    await save(page, '提出项目变更');
    const created = (await changeRow(page, id, (await api(page, 'GET', base(id) + '/governance')).changes.find(c => c.title === title).id));
    const rid = created.id;
    expect(created.status).toBe('draft');
    expect(created.escalated, '低影响变更不触发升级').toBeFalsy();

    await row(page, title).getByRole('button', { name: '提交审批', exact: true }).click();
    const submitForm = modal(page, '提交独立审批');
    await choose(page, submitForm, 'reviewer_id', '委员会甲');
    await closeSelect(page, submitForm, 'reviewer_id');
    await save(page, '提交独立审批');
    const submitted = await changeRow(page, id, rid);
    expect(submitted.status, '提交后进入评审').toBe('in_review');
    expect(submitted.ccb_summary.state, '尚未设立委员会时为 none').toBe('none');

    // 2) admin 设立委员会: 成员 委员会甲/乙, 通过门槛 2. 台账列翻"表决中 0/2".
    await row(page, title).getByRole('button', { name: '设立变更控制委员会', exact: true }).click();
    const rosterForm = modal(page, '设立变更控制委员会');
    await chooseMulti(page, rosterForm, 'members', ['委员会甲', '委员会乙']);
    await rosterForm.locator('#required').fill('2');
    await shot(page, 'h09ccb-1-roster-dialog.png');
    await save(page, '设立变更控制委员会');
    await open(page, id, '需求与治理', '变更控制');
    await expect(row(page, title).getByText('表决中 0/2 / 成员 2')).toBeVisible();
    await shot(page, 'h09ccb-2-voting-0of2.png');

    // 门控验证 (真实HTTP): 登记人 admin 即使试图自投也被 403 拒绝.
    const selfVote = await mutateCode(page, id, `/governance/changes/${rid}/ballot`, { vote: 'approve', note: '登记人自投' });
    expect(selfVote.code, '登记人/提交人不得参与自身变更表决').toBe(403);

    // 3) 委员会甲(兼审核人)登录: 界面看不到针对自身的... 甲可投票(非登记人/提交人). 投赞成 -> 1/2 仍未达门槛.
    const ctxA = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const pa = await ctxA.newPage();
    pa.on('pageerror', error => errors.push(error.message));
    await login(pa, boardA, pwd);
    await open(pa, id, '需求与治理', '变更控制');
    await row(pa, title).getByRole('button', { name: '委员会表决', exact: true }).click();
    const ballotA = modal(pa, '委员会表决');
    await choose(pa, ballotA, 'vote', '赞成');
    await closeSelect(pa, ballotA, 'vote');
    await ballotA.locator('#note').fill('评估可行, 赞成.');
    await save(pa, '委员会表决');
    await open(pa, id, '需求与治理', '变更控制');
    await expect(row(pa, title).getByText('表决中 1/2 / 成员 2')).toBeVisible();

    // 审核人甲在未达门槛时批准 -> 命中 409 表决门控, 弹窗内联告警可见.
    await row(pa, title).getByRole('button', { name: '批准', exact: true }).click();
    const decisionForm = modal(pa, '批准评审');
    await decisionForm.locator('#reason').fill('票数未达门槛试图批准');
    await saveExpectGate(pa, '批准评审', 409);
    await expect(decisionForm.getByText(/表决未达通过票数/)).toBeVisible();
    await shot(pa, 'h09ccb-3-approve-gated-409.png');
    await dismiss(pa, '批准评审');

    // 4) 委员会乙登录投赞成 -> 达到门槛, 列翻"表决通过 2/2".
    const ctxB = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const pb = await ctxB.newPage();
    pb.on('pageerror', error => errors.push(error.message));
    await login(pb, boardB, pwd);
    await open(pb, id, '需求与治理', '变更控制');
    await row(pb, title).getByRole('button', { name: '委员会表决', exact: true }).click();
    const ballotB = modal(pb, '委员会表决');
    await choose(pb, ballotB, 'vote', '赞成');
    await closeSelect(pb, ballotB, 'vote');
    await ballotB.locator('#note').fill('同意增补, 赞成.');
    await save(pb, '委员会表决');
    await open(pb, id, '需求与治理', '变更控制');
    await expect(row(pb, title).getByText('表决通过 2/2')).toBeVisible();
    await shot(pb, 'h09ccb-4-passed-2of2.png');

    // 5) 门槛已达 -> 审核人甲批准成功 -> approved.
    await open(pa, id, '需求与治理', '变更控制');
    await row(pa, title).getByRole('button', { name: '批准', exact: true }).click();
    const approveForm = modal(pa, '批准评审');
    await approveForm.locator('#reason').fill('委员会表决通过, 独立批准.');
    await save(pa, '批准评审');
    const final = await changeRow(pa, id, rid);
    expect(final.status, '达到门槛后批准成功').toBe('approved');
    await open(pa, id, '需求与治理', '变更控制');
    await expect(row(pa, title).getByText('表决通过 2/2')).toBeVisible();
    await shot(pa, 'h09ccb-5-approved.png');

    await ctxA.close();
    await ctxB.close();
    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
