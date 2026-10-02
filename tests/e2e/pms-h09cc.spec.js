const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H09 延伸: 变更控制闭环汇总只读派生洞察 (免迁移, 无新命令/新kind/新路由).
// 界面登记多条变更覆盖各工作流状态(草稿/审批中/已批准/已驳回)与高影响升级处置(独立确认 -> 升级已确认),
// "变更控制"页签新增只读"变更控制闭环汇总"面板: 按每个变更最新有效版本聚合总数/各状态/批准率/高影响/升级处置/委员会表决态,
// 真实点击推进后翻牌, 真实 HTTP GET /governance 回显 change_closure_summary 一致. 只读派生不改变任何变更状态或门控.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h09cc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
// shared/panel 渲染 <section> 内含 <h3> 标题; "变更总数""已批准"等标签可能与台账重名, 须按面板标题作用域定位避免串台.
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
const base = id => `/api/pms/projects/${id}`;

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

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
  await form.locator(`#${key}`).press('Escape');
  await page.waitForLoadState('networkidle');
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

async function changeRow(page, id, rid) {
  const gov = await api(page, 'GET', base(id) + '/governance');
  return gov.changes.find(c => c.id === rid);
}

async function fillChange(page, { title, days }) {
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
}

// 面板在抽屉自身滚动容器里靠下, 整页截不到 -> 滚动到面板元素并做元素级截图.
async function panelShot(cov, file) {
  await cov.page().waitForLoadState('networkidle');
  await cov.scrollIntoViewIfNeeded();
  await cov.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('H09 变更控制闭环汇总只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('登记多条变更推进工作流 -> 只读闭环汇总面板按最新有效版本聚合各状态/高影响/升级处置 -> 真实HTTP回显一致', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    // 合成只读且具备质量审批权限的独立审批人 (与登记人/提交人 admin 分离), 不改动任何系统内置账号.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `变更闭环审批${suffix}`, role_key: `chg_cc_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `chg_cc_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const approverName = `cc_${suffix}`;
    const approverPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '变更闭环独立审批人', password: approverPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'H09 闭环汇总 E2E 合成独立审批人' });
    const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H09CC-${suffix}`, name: `变更闭环汇总验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });

    // 空态: 尚无变更时面板显示引导语.
    await open(page, id, '需求与治理', '变更控制');
    const empty = panel(page, '变更控制闭环汇总');
    await expect(empty).toBeVisible();
    await expect(empty.getByText('暂无项目变更')).toBeVisible();
    await panelShot(empty, 'h09cc-0-empty.png');

    // 1) 界面登记变更 A (低影响, 停在草稿).
    const titleA = `草稿观察变更-${suffix}`;
    await fillChange(page, { title: titleA });
    const createdA = await save(page, '提出项目变更');
    const ridA = createdA.result.id;

    // 2) 界面登记变更 B (低影响) 并提交独立审批.
    const titleB = `待批准变更-${suffix}`;
    await fillChange(page, { title: titleB });
    const createdB = await save(page, '提出项目变更');
    const ridB = createdB.result.id;
    await row(page, titleB).getByRole('button', { name: '提交审批', exact: true }).click();
    await choose(page, modal(page, '提交独立审批'), 'reviewer_id', '变更闭环独立审批人');
    await save(page, '提交独立审批');

    // 3) 界面登记变更 C (高影响, 工期12天>=10阈值) 并提交 -> 自动升级 pending.
    const titleC = `高影响范围变更-${suffix}`;
    await fillChange(page, { title: titleC, days: 12 });
    const createdC = await save(page, '提出项目变更');
    const ridC = createdC.result.id;
    await row(page, titleC).getByRole('button', { name: '提交审批', exact: true }).click();
    await choose(page, modal(page, '提交独立审批'), 'reviewer_id', '变更闭环独立审批人');
    await save(page, '提交独立审批');
    const submittedC = await changeRow(page, id, ridC);
    expect(submittedC.escalated, '高影响变更提交即自动升级').toBe(true);
    expect(submittedC.escalation_state).toBe('pending');

    // 4) 独立审批人第二上下文: 批准 B (低影响直接批准), 并确认 C 的升级处置(留 in_review 不批准).
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const approver = await context.newPage();
    approver.on('pageerror', error => errors.push(error.message));
    await login(approver, approverName, approverPwd);
    await open(approver, id, '需求与治理', '变更控制');
    await row(approver, titleB).getByRole('button', { name: '批准', exact: true }).click();
    const approveB = modal(approver, '批准评审');
    await approveB.locator('#reason').fill('低影响, 独立批准.');
    await save(approver, '批准评审');
    expect((await changeRow(approver, id, ridB)).status, 'B 已批准').toBe('approved');

    // C: 确认升级处置 -> acknowledged (不批准, 保持 in_review).
    await open(approver, id, '需求与治理', '变更控制');
    await row(approver, titleC).getByRole('button', { name: '确认升级处置', exact: true }).click();
    const ackForm = modal(approver, '确认变更升级');
    await choose(approver, ackForm, 'decision', '确认升级并责成处置');
    await ackForm.locator('#note').fill('变更控制确认升级成立, 责成补充回退方案.');
    await save(approver, '确认变更升级');
    const ackedC = await changeRow(approver, id, ridC);
    expect(ackedC.escalation_state, 'C 升级已由独立审批人确认').toBe('acknowledged');
    expect(ackedC.status, 'C 仍在评审').toBe('in_review');
    await context.close();

    // 5) admin 重开"变更控制"页签, 只读闭环汇总面板按最新有效版本聚合:
    // 总数3 / 已批准 33% (1/3) / 草稿 1 / 审批中 1 / 已批准 1 / 高影响 1 / 已升级 1 / 升级已确认 1.
    await open(page, id, '需求与治理', '变更控制');
    const sum = panel(page, '变更控制闭环汇总');
    await expect(sum).toBeVisible();
    await expect(sum.getByText('变更总数 3', { exact: true })).toBeVisible();
    await expect(sum.getByText('已批准 33% (1/3)', { exact: true })).toBeVisible();
    await expect(sum.getByText('草稿 1', { exact: true })).toBeVisible();
    await expect(sum.getByText('审批中 1', { exact: true })).toBeVisible();
    await expect(sum.getByText('已批准 1', { exact: true })).toBeVisible();
    await expect(sum.getByText('高影响 1', { exact: true })).toBeVisible();
    await expect(sum.getByText('已升级 1', { exact: true })).toBeVisible();
    await expect(sum.getByText('升级已确认 1', { exact: true })).toBeVisible();
    await expect(sum.getByText('待独立确认', { exact: true })).toHaveCount(0);
    await panelShot(sum, 'h09cc-1-summary-panel.png');

    // 6) 真实 HTTP 回显 change_closure_summary 与界面一致.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const cc = ws.change_closure_summary;
    expect(cc.available, '有数据').toBe(true);
    expect(cc.total, '总数').toBe(3);
    expect(cc.draft, '草稿').toBe(1);
    expect(cc['in-review'], '审批中').toBe(1);
    expect(cc.approved, '已批准').toBe(1);
    expect(cc['approval-pct'], '批准率 round(100/3)').toBe(33);
    expect(cc['high-impact'], '高影响').toBe(1);
    expect(cc.escalated, '已升级').toBe(1);
    expect(cc.acknowledged, '升级已确认').toBe(1);
    expect(cc.pending, '无待独立确认').toBe(0);

    // 7) 只读派生不改变变更状态: A 仍草稿, B 已批准, C 已确认升级但仍评审中.
    expect((await changeRow(page, id, ridA)).status, 'A 仍草稿(只读派生不改状态)').toBe('draft');
    expect((await changeRow(page, id, ridC)).status, 'C 仍评审中').toBe('in_review');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
