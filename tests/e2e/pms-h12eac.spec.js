const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H12/H13 预测完工成本 EAC/ETC (只读派生): 项目费用 -> 成本与分摊 页签内的"预测完工成本"面板,
// 按预算与概算两个基线口径派生 BAC/AC/已承诺/ETC/EAC; 预算缺失或币种不一致如实不可算, 不做隐式换汇.
// 数据来自 finance overview 顶层 :forecast (读取时派生, 免迁移/无门控, 不写费用版本).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h12eac');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
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

async function mutate(page, id, suffix, data = {}, expected = 200) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version }, expected);
}

async function tab(page, name) {
  await drawer(page).getByRole('tab', { name, exact: true }).click();
  await page.waitForLoadState('networkidle');
}

// 打开 项目费用 -> 内部"成本与分摊"页签 (面板所在).
async function openFinanceCost(page, id) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await tab(page, '项目费用');
  await drawer(page).getByLabel('项目费用').getByRole('tab', { name: '成本与分摊', exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 面板在抽屉滚动容器内偏下, 通用 shot scrollTo(0,0) 可能截不到 -> 定位后单独截元素图.
async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '预测完工成本 (EAC/ETC)', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:finance:query', 'pms:finance:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `预测审批${suffix}`, role_key: `pms_h12eac_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_h12eac_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立财务审批人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H12EAC-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, suffix };
}

async function toExecution(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '交付任务', owner_id: f.adminId, task_type: 'task', duration_days: 2, start_date: '2026-09-22' });
  const evidence = await command('/governance/documents', { code: 'H12EAC-EV', title: '预测证据', filename: 'h12eac.txt', content: '合成记录.', stage: 'design', structure_node: 'U1' });
  const charter = await command('/governance/charters', { title: '预测章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  const template = await command('/governance/gate-templates', { code: 'H12EAC-G', title: '执行工程评审', stage: 'execution', required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
  const gate = await command('/governance/gates', { template_id: template.id, title: '执行准入检查', reviewer_id: f.userId });
  await command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'C1', passed: true, evidence_ids: [evidence.id] }] });
  await command(`/governance/gates/${gate.id}/submit`, {});
  await command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验' }, f.reviewer);
  await mutate(page, f.id, '/transition', { status: 'initiated', reason: 'E2E' });
  await mutate(page, f.id, '/transition', { status: 'planning', reason: 'E2E' });
  const baseline = await command('/planning/submit', { comment: '执行前置已批准计划' });
  await command(`/planning/baselines/${baseline.baseline_id}/review`, { decision: 'approved', comment: '独立批准' }, f.reviewer);
  await mutate(page, f.id, '/transition', { status: 'execution', reason: '前置均已批准' });
  return { ...f, task, evidence, command };
}

// 定位"预测完工成本"面板 (section + heading exact, 避免跨面板串台).
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '预测完工成本 (EAC/ETC)', exact: true }) }).first();

test.describe('H12/H13 预测完工成本 EAC/ETC (只读派生)', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 预算+实际批准后 EAC=ETC+BAC, 面板与 :forecast 一致', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await toExecution(page, await fixture(page, browser, '预测完工成本'));
    const id = f.id;
    const create = (kind, name) => f.command('/cost-versions', { kind, period: '2026-09', currency: 'CNY', name, revenue: '0', reviewer_id: f.userId });
    const entry = (vid, amount) => f.command(`/cost-versions/${vid}/entries`, { category: 'material', label: '材料', amount });
    const submit = vid => f.command(`/cost-versions/${vid}/submit`, {});
    const review = (vid, reason) => f.command(`/cost-versions/${vid}/review`, { decision: 'approved', reason }, f.reviewer);

    // 1. 空态: 尚无已批准预算 -> 两个基线口径均标注不可算.
    await openFinanceCost(page, id);
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('缺少可比的已批准预算, 不可算').first()).toBeVisible();
    await shot(page, 'h12eac-1-empty.png');
    await shotPanel(page, 'h12eac-1-empty-panel.png');
    let fc = (await api(page, 'GET', base(id) + '/finance')).forecast;
    expect(fc.budget.available).toBe(false);
    expect(fc.budget.eac_minor).toBeNull();
    expect(fc.estimate.available).toBe(false);

    // 2. 预算 1000 (material 1000) + 实际 400 -> 独立批准 -> BAC 1000 AC 400 ETC 600 EAC 1000.
    const bv = await create('budget', '预算A');
    await entry(bv.id, '1000');
    await submit(bv.id);
    await review(bv.id, '预算批准');
    const av = await create('actual', '实际A');
    await entry(av.id, '400');
    await submit(av.id);
    await review(av.id, '实际批准');

    await openFinanceCost(page, id);
    await expect(panel(page).getByText(/预算基线 · BAC 1000\.00 元, AC 400\.00 元/)).toBeVisible();
    await expect(panel(page).getByText('已用 40%')).toBeVisible();
    await shot(page, 'h12eac-2-budget-actual.png');
    await shotPanel(page, 'h12eac-2-budget-actual-panel.png');
    fc = (await api(page, 'GET', base(id) + '/finance')).forecast;
    expect(fc.budget).toMatchObject({ available: true, comparable: true, 'bac_minor': 100000, 'ac_minor': 40000, 'committed_minor': 0, 'etc_minor': 60000, 'eac_minor': 100000, 'used_pct': 40, 'remaining_pct': 60, 'over_budget': false });
    expect(fc.budget.bac).toBe('1000.00');
    expect(fc.budget.etc).toBe('600.00');
    expect(fc.budget.eac).toBe('1000.00');
    // 概算口径无已批准预算 -> 仍不可算, 两口径互不串用.
    expect(fc.estimate.available).toBe(false);

    // 3. 登记 300 承诺 (提交即计入已承诺) -> ETC 降到 300, EAC 仍 1000, 已用 70%.
    const commit = await f.command('/commitments', { kind: 'purchase', code: `C-${f.suffix}`, period: '2026-09', supplier: '供应商A', currency: 'CNY', gross: '300', description: '采购', reviewer_id: f.userId });
    await f.command(`/commitments/${commit.id}/submit`, {});

    await openFinanceCost(page, id);
    await expect(panel(page).getByText(/已承诺 300\.00 元, ETC 300\.00 元, EAC 1000\.00 元/)).toBeVisible();
    await expect(panel(page).getByText('已用 70%')).toBeVisible();
    // 概算口径无已批准预算 -> 应提示"缺少可比的已批准预算", 不能误报币种不一致.
    await expect(panel(page).getByText('缺少可比的已批准预算, 不可算')).toBeVisible();
    await expect(panel(page).getByText(/币种不一致/)).toHaveCount(0);
    await shot(page, 'h12eac-3-committed.png');
    await shotPanel(page, 'h12eac-3-committed-panel.png');
    fc = (await api(page, 'GET', base(id) + '/finance')).forecast;
    expect(fc.budget).toMatchObject({ 'committed_minor': 30000, 'etc_minor': 30000, 'eac_minor': 100000, 'used_pct': 70, 'over_budget': false });

    // 读模型不改变逐条费用版本状态.
    const versions = (await api(page, 'GET', base(id) + '/finance')).cost_versions;
    expect(versions.find(x => x.id === bv.id).status).toBe('approved');
    expect(versions.find(x => x.id === av.id).status).toBe('approved');

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
