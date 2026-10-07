const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H09 CCB 表决参与概览 (只读派生) 浏览器验收:
// 界面登记一份变更 -> 提交独立评审 -> admin 设立变更控制委员会(成员甲/乙, 门槛2) ->
// 变更控制页签出现"委员会表决参与概览"只读面板, 汇总委员会变更数/席位/已投票/参与率/在途/停滞与每位委员欠票负荷.
// 本用例全程单上下文(admin 登记人不能自投, 故参与率天然为 0%, 停滞未决=1), 断言的都是单上下文界面可见事实;
// 达到门槛/通过等需要第二审批上下文投票的状态翻转按规则A/B不进本用例 (已由 pms-h09ccb.spec.js 多上下文覆盖).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h09ccbp');
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
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'H09 CCB 参与概览 E2E合成委员会成员' });
  return (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id;
}

// 只读面板在治理抽屉内的第三个卡片, 需要滚动到视口再取元素截图.
const participationPanel = page =>
  drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '委员会表决参与概览', exact: true }) }).first();

test.describe('H09 CCB 表决参与概览只读面板浏览器验收 (单上下文)', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('未设委员会时概览为空 -> 设立委员会后概览只读汇总席位/参与率/停滞/欠票负荷', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `变更控制委员会${suffix}`, role_key: `ccb_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `ccb_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const boardA = `ccb_a_${suffix}`, boardB = `ccb_b_${suffix}`;
    const idA = await memberUser(page, suffix, deptId, roleId, '委员会甲', boardA);
    const idB = await memberUser(page, suffix, deptId, roleId, '委员会乙', boardB);

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H09CCBP-${suffix}`, name: `变更控制参与概览验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: idA, role: 'viewer' });
    await api(page, 'POST', base(id) + '/members', { user_id: idB, role: 'viewer' });

    // 1) 登记并提交一份变更 (审核人=委员会甲), 此时尚无委员会.
    const title = `设备增补变更-${suffix}`;
    await open(page, id, '需求与治理', '变更控制');
    await fillChange(page, title);
    await save(page, '提出项目变更');
    const created = await changeRow(page, id, (await api(page, 'GET', base(id) + '/governance')).changes.find(c => c.title === title).id);
    const rid = created.id;
    expect(created.status).toBe('draft');

    await row(page, title).getByRole('button', { name: '提交审批', exact: true }).click();
    const submitForm = modal(page, '提交独立审批');
    await choose(page, submitForm, 'reviewer_id', '委员会甲');
    await closeSelect(page, submitForm, 'reviewer_id');
    await save(page, '提交独立审批');
    const submitted = await changeRow(page, id, rid);
    expect(submitted.status, '提交后进入评审').toBe('in_review');

    // 2) 未设委员会时参与概览只读面板显示空态提示.
    await open(page, id, '需求与治理', '变更控制');
    const panel = participationPanel(page);
    await expect(panel).toBeVisible();
    await panel.scrollIntoViewIfNeeded();
    await expect(panel.getByText('暂无已设立委员会的变更')).toBeVisible();
    await panel.screenshot({ path: path.join(output, 'h09ccbp-1-empty.png') });

    // 空态时后端读模型: 委员会变更数 0, 可用 false.
    const emptyView = (await api(page, 'GET', base(id) + '/governance')).ccb_participation;
    expect(emptyView['committee-changes'], '尚未设委员会时不计入').toBe(0);
    expect(emptyView.available, '空概览 available 为 false').toBe(false);

    // 3) admin 设立委员会: 成员 甲/乙, 门槛 2. 参与概览翻为只读汇总.
    await row(page, title).getByRole('button', { name: '设立变更控制委员会', exact: true }).click();
    const rosterForm = modal(page, '设立变更控制委员会');
    await chooseMulti(page, rosterForm, 'members', ['委员会甲', '委员会乙']);
    await rosterForm.locator('#required').fill('2');
    await save(page, '设立变更控制委员会');

    // 4) 只读面板可见事实: 委员会变更 1 / 委员 2 人 2 席 / 参与率 0% (0/2 票) / 在途 1 / 停滞未决 1, 每位委员欠票 1.
    await open(page, id, '需求与治理', '变更控制');
    await panel.scrollIntoViewIfNeeded();
    await expect(panel.getByText('委员会变更 1')).toBeVisible();
    await expect(panel.getByText('委员 2 人 / 2 席')).toBeVisible();
    await expect(panel.getByText('参与率 0% (0/2 票)')).toBeVisible();
    await expect(panel.getByText('在途 1')).toBeVisible();
    await expect(panel.getByText('停滞未决 1')).toBeVisible();
    await expect(panel.getByText('委员会甲 · 已投 0/1 · 欠 1')).toBeVisible();
    await expect(panel.getByText('委员会乙 · 已投 0/1 · 欠 1')).toBeVisible();
    await shot(page, 'h09ccbp-2-context.png');
    await panel.screenshot({ path: path.join(output, 'h09ccbp-3-participation.png') });

    // 后端读模型同口径 (真实HTTP): 席位 2, 投票 0, 参与率 0, 在途 1, 停滞 1, by-member 两人各欠 1.
    const view = (await api(page, 'GET', base(id) + '/governance')).ccb_participation;
    expect(view['committee-changes']).toBe(1);
    expect(view.members).toBe(2);
    expect(view.seats).toBe(2);
    expect(view.ballots, '登记人未投票 -> 已投 0').toBe(0);
    expect(view['participation-pct'], '无人表决时参与率 0').toBe(0);
    expect(view['open-changes']).toBe(1);
    expect(view['stalled-changes'], '门槛2零赞成票 -> voting 停滞').toBe(1);
    const byId = Object.fromEntries(view['by-member'].map(m => [m['member-id'], m]));
    expect(byId[idA], '甲被邀请1已投0欠1').toEqual({ 'member-id': idA, invited: 1, cast: 0, pending: 1 });
    expect(byId[idB], '乙被邀请1已投0欠1').toEqual({ 'member-id': idB, invited: 1, cast: 0, pending: 1 });

    // 5) 只读派生不改变任何变更状态或表决: 重复读取分布稳定, 变更仍 in_review 且 ccb 汇总 state=voting.
    const reread = (await api(page, 'GET', base(id) + '/governance')).ccb_participation;
    expect(reread, '重复读取无漂移').toEqual(view);
    const after = await changeRow(page, id, rid);
    expect(after.status, '概览只读, 不改变变更状态').toBe('in_review');
    expect(after.ccb_summary.state).toBe('voting');
    expect(after.ccb_summary.approve).toBe(0);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
