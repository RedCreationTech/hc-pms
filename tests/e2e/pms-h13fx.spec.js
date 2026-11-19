const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H13 多币种汇率表: 模板与规则 > 汇率表 建立 USD->CNY 汇率并发布,
// 随后以 USD 记账的项目实际经已发布汇率折算到本位币, 预测完工成本面板显式标注所用汇率与折算日.
// 精确折算算法 (直连/中转, 8 位小数, 无浮点) 由 pms_finance_test 断言, 本用例验证界面闭环.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h13fx');
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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 汇率表页签内, 找到 FX-CNY 行所在的表格行 (口径: 编码列).

async function retireAll(page) {
  const prior = (await api(page, 'GET', '/api/pms/config/exchange-rate')).rows || [];
  for (const row of prior.filter(r => r.status !== 'retired')) {
    await api(page, 'POST', `/api/pms/config/exchange-rate/${row.id}/retire`, { reason: 'E2E 重跑清理' });
  }
}

test.describe('H13 多币种汇率表与看板折算标注', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('汇率表建立发布 -> USD 实际经已发布汇率折算并在看板标注', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    await retireAll(page);

    // 1. 模板与规则 > 汇率表: 建立 USD->CNY 7.1234 自 2026-01-01.
    await page.goto('/pms/config');
    await page.getByRole('tab', { name: '汇率表', exact: true }).click();
    await expect(page.getByText('同一币种对的生效区间不可重叠', { exact: false })).toBeVisible();
    await page.getByRole('button', { name: '建立汇率表' }).click();
    const dlg = page.getByRole('dialog', { name: '建立汇率表', exact: true });
    await expect(dlg).toBeVisible();
    const code = 'USD-CNY'; // 汇率行编码与表编码均后端按币种派生
    await dlg.getByPlaceholder('汇率 (最多8位小数)').first().fill('7.1234');
    await dlg.locator('input[type="date"]').first().fill('2026-01-01');
    const created = page.waitForResponse(r => r.url().includes('/api/pms/config/exchange-rate') && r.request().method() === 'POST');
    await dlg.getByRole('button', { name: /^保\s*存$/ }).click();
    expect((await (await created).json()).code).toBe(200);
    await expect(dlg).toBeHidden();

    // 2. 草稿行 -> 发布.
    // 同一编码可能存在历史退役版本, 取当前草稿行.
    const draftRow = page.locator('table tbody tr').filter({ hasText: 'FX-CNY' }).filter({ hasText: '草稿' });
    await expect(draftRow).toHaveCount(1);
    await expect(draftRow).toContainText('草稿');
    await draftRow.getByRole('button', { name: '发布', exact: true }).click();
    const publish = page.getByRole('dialog', { name: '发布汇率表', exact: true });
    await expect(publish).toBeVisible();
    await publish.locator('textarea').fill('E2E 启用汇率');
    const published = page.waitForResponse(r => r.url().includes('/publish') && r.request().method() === 'POST');
    await publish.getByRole('button', { name: /^保\s*存$/ }).click();
    expect((await (await published).json()).code).toBe(200);
    await expect(publish).toBeHidden();
    const publishedRow = page.locator('table tbody tr').filter({ hasText: 'FX-CNY' }).filter({ hasText: '已发布' });
    await expect(publishedRow).toHaveCount(1);
    await shot(page, 'h13fx-1-published.png');

    // 3. 详情弹窗呈现币种对与汇率.
    await publishedRow.getByRole('button', { name: '详情', exact: true }).click();
    const detail = page.getByRole('dialog').filter({ hasText: /汇率表 FX-CNY/ });
    await expect(detail.getByRole('cell', { name: 'USD -> CNY' })).toBeVisible();
    await expect(detail.getByRole('cell', { name: '7.1234' })).toBeVisible();
    await page.screenshot({ path: path.join(output, 'h13fx-2-detail.png'), animations: 'disabled' });
    await detail.getByRole('button', { name: /取\s*消|关\s*闭|Close/ }).first().click().catch(() => page.keyboard.press('Escape'));

    // 4. 建项目并推进到执行, 预算 CNY 1000 + 实际 USD 100 -> 独立批准.
    const suffix = serial();
    const menus = await api(page, 'GET', '/api/system/menu');
    const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:finance:query', 'pms:finance:approve']);
    const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `汇率审批${suffix}`, role_key: `pms_h13fx_${suffix}`, 'menu-ids': menuIds });
    const roles = await api(page, 'GET', '/api/system/role');
    const roleId = roles.find(item => item.role_key === `pms_h13fx_${suffix}`).role_id;
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
    const username = `pms_${suffix}`;
    const password = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立财务审批人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
    const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H13FX-${suffix}`, name: `汇率折算 ${suffix}`, project_type: 'equipment', manager_id: adminId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });

    const context = await page.context().browser().newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const reviewer = await context.newPage();
    await login(reviewer, username, password);
    const command = (suffix2, data, actor = page) => mutate(actor, id, suffix2, data).then(r => r.result);

    await command('/tasks', { wbs_code: '1', name: '交付任务', owner_id: adminId, task_type: 'task', duration_days: 2, start_date: '2026-09-22' });
    const evidence = await command('/governance/documents', { code: 'H13FX-EV', title: '汇率证据', filename: 'h13fx.txt', content: '合成记录.', stage: 'design', structure_node: 'U1' });
    const charter = await command('/governance/charters', { title: '汇率章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: adminId });
    await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: userId });
    await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, reviewer);
    const template = await command('/governance/gate-templates', { code: 'H13FX-G', title: '执行工程评审', stage: 'execution', required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
    const gate = await command('/governance/gates', { template_id: template.id, title: '执行准入检查', reviewer_id: userId });
    await command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'C1', passed: true, evidence_ids: [evidence.id] }] });
    await command(`/governance/gates/${gate.id}/submit`, {});
    await command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验' }, reviewer);
    await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E' });
    await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E' });
    const baseline = await command('/planning/submit', { comment: '执行前置已批准计划' });
    await command(`/planning/baselines/${baseline.baseline_id}/review`, { decision: 'approved', comment: '独立批准' }, reviewer);
    await mutate(page, id, '/transition', { status: 'execution', reason: '前置均已批准' });

    const create = (kind, period, currency, name) => command('/cost-versions', { kind, period, currency, name, revenue: '0', reviewer_id: userId });
    const entry = (vid, amount) => command(`/cost-versions/${vid}/entries`, { category: 'material', label: '材料', amount });
    const submit = vid => command(`/cost-versions/${vid}/submit`, {});
    const review = (vid, reason) => command(`/cost-versions/${vid}/review`, { decision: 'approved', reason }, reviewer);

    const bv = await create('budget', '2026-09', 'CNY', '预算本币');
    await entry(bv.id, '1000');
    await submit(bv.id);
    await review(bv.id, '预算批准');
    const av = await create('actual', '2026-09', 'USD', '实际美元');
    await entry(av.id, '100');
    await submit(av.id);
    await review(av.id, '实际批准');

    // 5. 项目费用 > 成本与分摊: 面板应展示折算标注, 且不出现"币种不一致".
    await page.goto(`/pms/project?id=${id}`);
    const tabbed = drawer(page);
    await expect(tabbed.getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
    await tabbed.getByRole('tab', { name: '项目费用', exact: true }).click();
    await page.waitForLoadState('networkidle');
    await tabbed.getByLabel('项目费用').getByRole('tab', { name: '成本与分摊', exact: true }).click();
    await page.waitForLoadState('networkidle');
    const panel = tabbed.locator('section').filter({ has: page.getByRole('heading', { name: '预测完工成本 (EAC/ETC)', exact: true }) }).first();
    await panel.scrollIntoViewIfNeeded();
    await expect(panel.getByRole('heading', { name: '预测完工成本 (EAC/ETC)', exact: true })).toBeVisible();
    const budget = panel.getByText(/预算基线/);
    await expect(budget).toContainText('已折算 USD→CNY @7.1234');
    await expect(budget).toContainText('AC 712.34 元 (由 10000 分 USD 按 直连汇率 7.1234 折算, 折算日');
    await expect(budget).toContainText('ETC 287.66 元, EAC 1000.00 元');
    await expect(budget).toContainText('已用 71%');
    await expect(panel.getByText(/币种不一致/)).toHaveCount(0);
    await panel.screenshot({ path: path.join(output, 'h13fx-3-panel-converted.png'), animations: 'disabled' });

    // 6. 接口口径一致: forecast.budget.fx 标注直接命中, 折算后 AC = 100 USD * 7.1234 = 712.34 元.
    const fc = (await api(page, 'GET', base(id) + '/finance')).forecast;
    expect(fc.budget.available).toBe(true);
    expect(fc.budget.fx).toMatchObject({ from: 'USD', to: 'CNY', rate: '7.1234', converted: true });
    expect(fc.budget.fx.via_base).toBe(false);
    expect(fc.budget.ac_minor).toBe(71234);
    expect(fc.budget.currency_mismatch).toBe(false);
    expect(fc.budget.etc_minor).toBe(100000 - 71234);
    expect(fc.budget.over_budget).toBe(false);

    // 7. 退役汇率表后: 不再有可比汇率 -> 如实标注币种不一致 (不估算), EAC 不可算.
    const live = (await api(page, 'GET', '/api/pms/config/exchange-rate')).rows.find(r => r.code === 'FX-CNY' && r.status === 'published');
    expect(live, '已发布的 FX-CNY 汇率表存在').toBeTruthy();
    await api(page, 'POST', `/api/pms/config/exchange-rate/${live.id}/retire`, { reason: 'E2E 退役' });
    const fc2 = (await api(page, 'GET', base(id) + '/finance')).forecast;
    expect(fc2.budget.fx).toBeNull();
    expect(fc2.budget.available).toBe(false);
    expect(fc2.budget.currency_mismatch).toBe(true);

    await page.goto(`/pms/project?id=${id}`);
    await expect(tabbed.getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
    await tabbed.getByRole('tab', { name: '项目费用', exact: true }).click();
    await page.waitForLoadState('networkidle');
    await tabbed.getByLabel('项目费用').getByRole('tab', { name: '成本与分摊', exact: true }).click();
    await page.waitForLoadState('networkidle');
    await expect(panel.getByText(/币种不一致 \(CNY \/ USD\) 不可算/)).toBeVisible();
    await expect(panel.getByText(/已折算/)).toHaveCount(0);
    await panel.scrollIntoViewIfNeeded();
    await panel.screenshot({ path: path.join(output, 'h13fx-4-panel-unconverted.png'), animations: 'disabled' });

    await context.close();
    expect(errors).toEqual([]);
  });
});
