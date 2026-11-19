const { test, expect } = require('playwright/test');
const path = require('node:path');

// F08 税后净利: 模板与规则 > 税率表 建立并发布 (增值税 13% + 净利率 5%),
// 随后成本毛利看板在有收入的口径上派生 税额 = 收入 x 13%, 税后净利 = 收入 - 税额 - 5% 收入,
// 未发布税率表时如实标注"税后净利不可算"而不是猜默认税率.
// 精确算式由 pms_finance_test (net-margin-requires-published-tax-rate) 断言, 本用例验证界面闭环.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/f08net');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const base = id => `/api/pms/projects/${id}`;
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '成本毛利看板', exact: true }) }).first();

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

async function openFinanceCost(page, id) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await drawer(page).getByRole('tab', { name: '项目费用', exact: true }).click();
  await page.waitForLoadState('networkidle');
  await drawer(page).getByLabel('项目费用').getByRole('tab', { name: '成本与分摊', exact: true }).click();
  await page.waitForLoadState('networkidle');
}

test.describe('F08 税后净利与税率表', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('税率表发布 -> 成本毛利看板派生税额与税后净利 -> 退役后回落不可算', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    // 清场: 同编码只能有一张在用税率表, 重跑前先退役历史版本 (必须在首次看板断言之前).
    for (const row of ((await api(page, 'GET', '/api/pms/config/tax-rate')).rows || []).filter(r => r.status !== 'retired')) {
      await api(page, 'POST', `/api/pms/config/tax-rate/${row.id}/retire`, { reason: 'E2E 重跑清理' });
    }

    // 1. 先建项目并批准一个有收入的概算, 看板此时应标注"税后净利不可算".
    const suffix = serial();
    const menus = await api(page, 'GET', '/api/system/menu');
    const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:finance:query', 'pms:finance:approve']);
    const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `税率审批${suffix}`, role_key: `pms_f08net_${suffix}`, 'menu-ids': menuIds });
    const roles = await api(page, 'GET', '/api/system/role');
    const roleId = roles.find(item => item.role_key === `pms_f08net_${suffix}`).role_id;
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
    const username = `pms_${suffix}`;
    const password = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立财务审批人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
    const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `F08NET-${suffix}`, name: `税后净利 ${suffix}`, project_type: 'equipment', manager_id: adminId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });

    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const reviewer = await context.newPage();
    await login(reviewer, username, password);
    const command = (suffix2, data, actor = page) => mutate(actor, id, suffix2, data).then(r => r.result);

    await command('/tasks', { wbs_code: '1', name: '交付任务', owner_id: adminId, task_type: 'task', duration_days: 2, start_date: '2026-09-22' });
    const evidence = await command('/governance/documents', { code: 'F08NET-EV', title: '税利证据', filename: 'f08net.txt', content: '合成记录.', stage: 'design', structure_node: 'U1' });
    const charter = await command('/governance/charters', { title: '税利章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: adminId });
    await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: userId });
    await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, reviewer);
    const template = await command('/governance/gate-templates', { code: 'F08NET-G', title: '执行工程评审', stage: 'execution', required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
    const gate = await command('/governance/gates', { template_id: template.id, title: '执行准入检查', reviewer_id: userId });
    await command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'C1', passed: true, evidence_ids: [evidence.id] }] });
    await command(`/governance/gates/${gate.id}/submit`, {});
    await command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验' }, reviewer);
    await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E' });
    await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E' });
    const baseline = await command('/planning/submit', { comment: '执行前置已批准计划' });
    await command(`/planning/baselines/${baseline.baseline_id}/review`, { decision: 'approved', comment: '独立批准' }, reviewer);
    await mutate(page, id, '/transition', { status: 'execution', reason: '前置均已批准' });

    const estimate = await command('/cost-versions', { kind: 'estimate', period: '2026-09', currency: 'CNY', name: '概算v1', revenue: '1000', reviewer_id: userId });
    await command(`/cost-versions/${estimate.id}/entries`, { category: 'material', label: '材料', amount: '600' });
    await command(`/cost-versions/${estimate.id}/submit`, {});
    await command(`/cost-versions/${estimate.id}/review`, { decision: 'approved', reason: '概算批准' }, reviewer);

    // 无税率表 -> 面板如实标注净利不可算.
    await openFinanceCost(page, id);
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('未发布税率表, 税后净利不可算')).toBeVisible();
    await expect(panel(page).getByText(/毛利率 40%/)).toBeVisible();
    await expect(panel(page).getByText(/税后净利 \d/)).toHaveCount(0);
    await panel(page).screenshot({ path: path.join(output, 'f08net-1-no-tax.png'), animations: 'disabled' });

    // 2. 模板与规则 > 税率表: 建立 13% 增值税 + 5% 净利率并发布.
    const code = 'TAX-CNY'; // 编码由本位币派生
    await page.goto('/pms/config');
    await page.getByRole('tab', { name: '税率表', exact: true }).click();
    await expect(page.getByText(/税额 = 收入 × 增值税税率/)).toBeVisible();
    await page.getByRole('button', { name: '建立税率表' }).click();
    const dlg = page.getByRole('dialog', { name: '建立税率表', exact: true });
    await expect(dlg).toBeVisible();
    await dlg.getByLabel('增值税税率 (0-1, 最多6位小数)').fill('0.13');
    await dlg.getByLabel('净利率 (0-1, 用于税后净利)').fill('0.05');
    const created = page.waitForResponse(r => r.url().includes('/api/pms/config/tax-rate') && r.request().method() === 'POST');
    await dlg.getByRole('button', { name: /^保\s*存$/ }).click();
    expect((await (await created).json()).code).toBe(200);
    await expect(dlg).toBeHidden();

    const draftRow = page.locator('table tbody tr').filter({ hasText: code }).filter({ hasText: '草稿' });
    await expect(draftRow).toHaveCount(1);
    await draftRow.getByRole('button', { name: '发布', exact: true }).click();
    const publish = page.getByRole('dialog', { name: '发布税率表', exact: true });
    await expect(publish).toBeVisible();
    await publish.getByPlaceholder('可空').fill('E2E 启用税率');
    const published = page.waitForResponse(r => r.url().includes('/publish') && r.request().method() === 'POST');
    await publish.getByRole('button', { name: /^保\s*存$/ }).click();
    expect((await (await published).json()).code).toBe(200);
    await expect(publish).toBeHidden();
    await expect(page.locator('table tbody tr').filter({ hasText: code }).filter({ hasText: '已发布' })).toHaveCount(1);
    await shot(page, 'f08net-2-tax-published.png');

    // 3. 看板应派生税额 130.00 与税后净利 820.00 (82%).
    await openFinanceCost(page, id);
    await expect(panel(page).getByText(/税率口径 TAX-CNY · 增值税 0\.13 · 净利率 0\.05/)).toBeVisible();
    const row = panel(page).getByText(/概算 \(v1\)/);
    await expect(row).toContainText('收入 1000.00 成本 600.00 毛利 400.00');
    await expect(row).toContainText('毛利率 40%');
    await expect(row).toContainText('税后净利 820.00 元 (82%) 含税 130.00 元');
    await shootPanel(page);

    const cm = (await api(page, 'GET', base(id) + '/finance')).cost_margin;
    expect(cm.tax).toMatchObject({ code: 'TAX-CNY', base_currency: 'CNY', vat_rate: '0.13', net_rate: '0.05' });
    expect(cm.taxable).toBe(true);
    const est = cm['by-kind'].find(x => x.kind === 'estimate');
    expect(cm).toHaveProperty('taxable', true);
    expect(est).toMatchObject({ taxable: true, vat_rate: '0.13', tax_amount: '130.00', net_margin: '820.00', net_margin_pct: 82, margin: '400.00' });

    // 4. 退役税率表后回落为不可算, 不残留旧税率.
    const live = (await api(page, 'GET', '/api/pms/config/tax-rate')).rows.find(r => r.code === code && r.status === 'published');
    expect(live, '已发布的 TAX-CNY 税率表存在').toBeTruthy();
    await api(page, 'POST', `/api/pms/config/tax-rate/${live.id}/retire`, { reason: 'E2E 退役' });
    const cm2 = (await api(page, 'GET', base(id) + '/finance')).cost_margin;
    expect(cm2.tax).toBeNull();
    expect(cm2.taxable).toBe(false);
    expect(cm2['by-kind'].find(x => x.kind === 'estimate').net_margin).toBeNull();

    await openFinanceCost(page, id);
    await expect(panel(page).getByText('未发布税率表, 税后净利不可算')).toBeVisible();
    await expect(panel(page).getByText(/税后净利 \d/)).toHaveCount(0);
    await panel(page).screenshot({ path: path.join(output, 'f08net-3-retired.png'), animations: 'disabled' });

    await context.close();
    expect(errors).toEqual([]);
  });

  async function shootPanel(page) {
    const el = panel(page);
    await el.scrollIntoViewIfNeeded();
    await page.waitForLoadState('networkidle');
    await el.screenshot({ path: path.join(output, 'f08net-2-net-margin.png'), animations: 'disabled' });
  }
});
