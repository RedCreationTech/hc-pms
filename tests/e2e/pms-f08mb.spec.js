const { test, expect } = require('@playwright/test');
const path = require('node:path');

// F08 成本毛利看板 (只读派生): 项目费用 -> 成本与分摊 页签内的"成本毛利看板"面板,
// 概算-预算-核算-决算各口径取最新已批准版本, 只读派生收入/成本/毛利与毛利率; 零或负收入标注"不可算".
// 数据来自 finance overview 顶层 :cost_margin (整项目全部费用版本只读聚合, 免迁移/无门控).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/f08mb');
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
  await expect(el.getByRole('heading', { name: '成本毛利看板', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:finance:query', 'pms:finance:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `财务审批${suffix}`, role_key: `pms_f08_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_f08_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立财务审批人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `F08-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
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
  const evidence = await command('/governance/documents', { code: 'F08-EV', title: '毛利证据', filename: 'f08.txt', content: '合成记录.', stage: 'design', structure_node: 'U1' });
  const charter = await command('/governance/charters', { title: '毛利章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  const template = await command('/governance/gate-templates', { code: 'F08-G', title: '执行工程评审', stage: 'execution', required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
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

// 定位"成本毛利看板"面板 (section + heading exact, 避免跨面板串台).
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '成本毛利看板', exact: true }) }).first();

test.describe('F08 成本毛利看板 (只读派生)', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 概算毛利口径 -> 决算零收入不可算, 面板与 :cost_margin 一致', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await toExecution(page, await fixture(page, browser, '成本毛利看板'));
    const id = f.id;
    const create = (kind, name, revenue) => f.command('/cost-versions', { kind, period: '2026-09', currency: 'CNY', name, revenue, reviewer_id: f.userId });
    const entry = (vid, category, amount) => f.command(`/cost-versions/${vid}/entries`, { category, label: category, amount });
    const submit = vid => f.command(`/cost-versions/${vid}/submit`, {});
    const review = (vid, reason) => f.command(`/cost-versions/${vid}/review`, { decision: 'approved', reason }, f.reviewer);

    // 1. 空态: 尚无已批准版本 -> 面板引导文案, :cost_margin.available = false, 四口径均 present=false.
    await openFinanceCost(page, id);
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无已批准的四算版本')).toBeVisible();
    await shot(page, 'f08mb-1-empty.png');
    await shotPanel(page, 'f08mb-1-empty-panel.png');
    let cm = (await api(page, 'GET', base(id) + '/finance')).cost_margin;
    expect(cm.available).toBe(false);
    expect(cm['by-kind']).toHaveLength(4);
    expect(cm['by-kind'].every(x => x.present === false)).toBe(true);

    // 2. 概算 收入1000 成本600 (material 600) -> 独立批准 -> 毛利率 40%.
    const est = await create('estimate', '概算A', '1000');
    await entry(est.id, 'material', '600');
    await submit(est.id);
    await review(est.id, '毛利达标');

    await openFinanceCost(page, id);
    await expect(panel(page).getByText('概算 (v1) 收入 1000.00 成本 600.00 毛利 400.00')).toBeVisible();
    await expect(panel(page).getByText('毛利率 40%')).toBeVisible();
    await expect(panel(page).getByText('预算 · 尚无已批准版本')).toBeVisible();
    await shot(page, 'f08mb-2-estimate.png');
    await shotPanel(page, 'f08mb-2-estimate-panel.png');
    cm = (await api(page, 'GET', base(id) + '/finance')).cost_margin;
    expect(cm.available).toBe(true);
    const estimate = cm['by-kind'].find(x => x.kind === 'estimate');
    expect(estimate).toMatchObject({ present: true, computable: true, 'version_no': 1, revenue: '1000.00', cost: '600.00', margin: '400.00', 'margin-pct': 40 });
    expect(cm['by-kind'].find(x => x.kind === 'budget').present).toBe(false);
    expect(cm['by-kind'].find(x => x.kind === 'settlement').present).toBe(false);

    // 3. 决算 收入0 成本200 (labor 200) -> 独立批准 -> 零收入"不可算".
    const st = await create('settlement', '决算A', '0');
    await entry(st.id, 'labor', '200');
    await submit(st.id);
    await review(st.id, '零收入口径');

    await openFinanceCost(page, id);
    await expect(panel(page).getByText('毛利率 40%')).toBeVisible();
    await expect(panel(page).getByText('不可算 (零或负收入)')).toBeVisible();
    await expect(panel(page).getByText('决算 (v1) 收入 0.00 成本 200.00 毛利 -200.00')).toBeVisible();
    await shot(page, 'f08mb-3-mixed.png');
    await shotPanel(page, 'f08mb-3-mixed-panel.png');
    cm = (await api(page, 'GET', base(id) + '/finance')).cost_margin;
    const settlement = cm['by-kind'].find(x => x.kind === 'settlement');
    expect(settlement).toMatchObject({ present: true, computable: false, revenue: '0.00', cost: '200.00', margin: '-200.00' });
    expect(settlement['margin-pct']).toBeNull();
    // 同币种 -> 可横向比较.
    expect(cm.comparable).toBe(true);

    // 读模型不改变逐条版本状态 (成本版本本身仍是已批准).
    const versions = (await api(page, 'GET', base(id) + '/finance')).cost_versions;
    expect(versions.find(x => x.id === est.id).status).toBe('approved');
    expect(versions.find(x => x.id === st.id).status).toBe('approved');

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
