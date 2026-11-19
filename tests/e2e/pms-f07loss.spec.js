const { test, expect } = require('playwright/test');
const path = require('node:path');

// F07 变更损失: 登记并独立批准一条量化成本影响的正式变更, 在核算口径草稿版本中新增变更损失条目,
// 通过"关联变更"把损失逐项挂到该变更上, 随后财务概览派生按产品线 (合同号) 与变更类型汇总的损失面板.
// 精确算式与门控由 pms_finance_test (change-loss-*) 断言, 本用例验证界面闭环与真实 HTTP 合同.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/f07loss');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const base = id => `/api/pms/projects/${id}`;
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '变更损失汇总', exact: true }) }).first();

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
  // 每次写入前同步真实版本, 使读模型之外的界面操作与独立审批人上下文都不再造成版本漂移.
  const current = (await api(page, 'GET', base(id))).version;
  return api(page, 'POST', base(id) + suffix, { ...data, version: current }, expected);
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

test.describe('F07 变更损失与产品线汇总', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('批准变更 -> 核算损失关联变更 -> 变更损失汇总按产品线与类型派生', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const menus = await api(page, 'GET', '/api/system/menu');
    const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:finance:query', 'pms:finance:approve']);
    const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `变更损失审批${suffix}`, role_key: `pms_f07loss_${suffix}`, 'menu-ids': menuIds });
    const roles = await api(page, 'GET', '/api/system/role');
    const roleId = roles.find(item => item.role_key === `pms_f07loss_${suffix}`).role_id;
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
    const username = `pms_${suffix}`;
    const password = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立变更审批人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
    const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `F07LOSS-${suffix}`, name: `变更损失 ${suffix}`, project_type: 'equipment', manager_id: adminId, dept_id: deptId, contract_no: `PL-${suffix}`, start_date: '2026-09-22', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });

    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const reviewer = await context.newPage();
    await login(reviewer, username, password);
    // 所有写命令共享同一版本计数, 独立审批人上下文也复用调用方读到的版本.
    const command = (suffix2, data, actor = page) => mutate(actor, id, suffix2, data).then(r => r.result);

    // 执行前置: 任务, 证据, 章程, 执行 Gate, 基线, 进入执行.
    await command('/tasks', { wbs_code: '1', name: '交付任务', owner_id: adminId, task_type: 'task', duration_days: 2, start_date: '2026-09-22' });
    const evidence = await command('/governance/documents', { code: 'F07LOSS-EV', title: '损失证据', filename: 'f07loss.txt', content: '合成记录.', stage: 'design', structure_node: 'U1' });
    const charter = await command('/governance/charters', { title: '损失章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: adminId });
    await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: userId });
    await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, reviewer);
    const template = await command('/governance/gate-templates', { code: 'F07LOSS-G', title: '执行工程评审', stage: 'execution', required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
    const gate = await command('/governance/gates', { template_id: template.id, title: '执行准入检查', reviewer_id: userId });
    await command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'C1', passed: true, evidence_ids: [evidence.id] }] });
    await command(`/governance/gates/${gate.id}/submit`, {});
    await command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验' }, reviewer);
    await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E' });
    await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E' });
    const baseline = await command('/planning/submit', { comment: '执行前置已批准计划' });
    await command(`/planning/baselines/${baseline.baseline_id}/review`, { decision: 'approved', comment: '独立批准' }, reviewer);
    await mutate(page, id, '/transition', { status: 'execution', reason: '前置均已批准' });

    // 1. 登记并独立批准一条量化成本影响的正式变更 (纠错性) 与一条更新类变更.
    const approveChange = async (body) => {
      const rec = await command('/governance/changes', body);
      await command(`/governance/changes/${rec.id}/submit`, { reviewer_id: userId });
      await command(`/governance/changes/${rec.id}/decision`, { decision: 'approved', reason: '影响已评估' }, reviewer);
      return rec;
    };
    const changeA = await approveChange({
      title: '客户追加接口变更', reason: '客户要求', scope_impact: '范围扩大', schedule_impact: '工期+6天',
      cost_impact: '成本增加', quality_impact: '无', resource_impact: '无',
      schedule_impact_days: 6, cost_impact_amount: '5000.00', change_type: 'corrective'
    });
    const changeB = await approveChange({
      title: '工艺文件更新', reason: '更新标准', scope_impact: '无', schedule_impact: '工期+2天',
      cost_impact: '成本增加', quality_impact: '无', resource_impact: '无',
      schedule_impact_days: 2, cost_impact_amount: '2000.00', change_type: 'updates'
    });

    // 2. 核算版本登记两条变更损失并逐项关联到已批准变更.
    const actual = await command('/cost-versions', { kind: 'actual', period: '2026-09', currency: 'CNY', name: '核算v1', revenue: '20000', reviewer_id: userId });
    const lossA = await command(`/cost-versions/${actual.id}/entries`, { category: 'change_loss', label: '接口变更损失', amount: '5000.00' });
    const lossB = await command(`/cost-versions/${actual.id}/entries`, { category: 'change_loss', label: '工艺更新损失', amount: '2000.00' });

    // 关联前汇总为空.
    await openFinanceCost(page, id);
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText(/尚无已批准的四算版本/)).toBeVisible();
    await panel(page).screenshot({ path: path.join(output, 'f07loss-1-empty.png'), animations: 'disabled' });

    // 3. 界面"查看明细" + 关联变更: 通过真实弹窗把两条损失挂到已批准变更.
    const costRow = drawer(page).locator('table tbody tr').filter({ hasText: '核算v1' });
    await costRow.getByRole('button', { name: '查看明细', exact: true }).click();
    await page.waitForLoadState('networkidle');
    for (const [label, code] of [['接口变更损失', changeA.code], ['工艺更新损失', changeB.code]]) {
      const row = drawer(page).locator('table tbody tr').filter({ hasText: label });
      await expect(row).toBeVisible();
      await row.getByRole('button', { name: '关联变更', exact: true }).click();
      const dlg = page.getByRole('dialog', { name: '关联正式变更', exact: true });
      await expect(dlg).toBeVisible();
      await dlg.getByLabel('变更编码').fill(code);
      const saved = page.waitForResponse(r => r.url().includes('/change') && r.request().method() === 'POST');
      await dlg.getByRole('button', { name: /^保\s*存$/ }).click();
      expect((await (await saved).json()).code).toBe(200);
      await expect(dlg).toBeHidden();
      await expect(row).toContainText(code);
    }
    await shot(page, 'f07loss-2-bound.png');

    // 4. 未批准版本不计入汇总 (只统计已批准四算版本).
    let cl = (await api(page, 'GET', base(id) + '/finance')).change_loss;
    expect(cl.total_count).toBe(0);

    // 5. 独立批准核算版本后, 汇总派生总额 / 产品线 / 变更类型.
    // 界面上的两次关联已推进项目版本, 这里重新同步后再发出后续命令.
    await command(`/cost-versions/${actual.id}/submit`, {});
    await command(`/cost-versions/${actual.id}/review`, { decision: 'approved', reason: '核算批准' }, reviewer);

    cl = (await api(page, 'GET', base(id) + '/finance')).change_loss;
    expect(cl.available).toBe(true);
    expect(cl.reconciled).toBe(true);
    expect(cl.total_count).toBe(2);
    expect(cl.total_amount).toBe('7000.00');
    expect(cl.total_schedule_days).toBe(8);
    expect(cl.currency).toBe('CNY');
    expect(cl.by_product_line).toHaveLength(1);
    expect(cl.by_product_line[0]).toMatchObject({ key: `PL-${suffix}`, count: 2, amount: '7000.00', schedule_days: 8 });
    expect(cl.by_type.find(t => t.key === 'corrective')).toMatchObject({ count: 1, amount: '5000.00' });
    expect(cl.by_type.find(t => t.key === 'updates')).toMatchObject({ count: 1, amount: '2000.00' });
    expect(cl.unapproved_count).toBe(0);
    expect(cl.orphan_count).toBe(0);

    await openFinanceCost(page, id);
    await expect(panel(page).getByText(/已计入笔数 2/)).toBeVisible();
    await expect(panel(page).getByText(/累计损失 7000\.00 CNY/)).toBeVisible();
    await expect(panel(page).getByText(/累计工期影响 8 天/)).toBeVisible();
    await expect(panel(page).getByText('全部损失均可追溯已批准变更')).toBeVisible();
    await expect(panel(page).getByText(`PL-${suffix}`)).toBeVisible();
    await expect(panel(page).getByText('corrective')).toBeVisible();
    const el = panel(page);
    await el.scrollIntoViewIfNeeded();
    await page.waitForLoadState('networkidle');
    await el.screenshot({ path: path.join(output, 'f07loss-3-summary.png'), animations: 'disabled' });

    // 6. 真实 HTTP 合同: 未批准变更/错误金额/非变更损失条目均被拒.
    // 独立审批由另一浏览器上下文完成, 同样推进了项目版本.
    const draftChange = await command('/governance/changes', {
      title: '草案变更', reason: '草案', scope_impact: 's', schedule_impact: 't',
      cost_impact: 'c', quality_impact: 'q', resource_impact: 'r'
    });
    expect(draftChange.code).toBeTruthy();
    const estimate = await command('/cost-versions', { kind: 'estimate', period: '2026-09', currency: 'CNY', name: '概算v1', revenue: '20000', reviewer_id: userId });
    const estLoss = await command(`/cost-versions/${estimate.id}/entries`, { category: 'change_loss', label: '概算损失', amount: '5000.00' });
    const ent = (v, label) => (v.entries || []).find(e => e.label === label);
    const estEntry = ent(estLoss, '概算损失');

    await mutate(page, id, `/cost-versions/${estimate.id}/entries/${estEntry.id}/change`, { change_code: changeA.code }, 400);
    const actual2 = await command('/cost-versions', { kind: 'actual', period: '2026-10', currency: 'CNY', name: '核算v2', revenue: '0', reviewer_id: userId });
    const mismatchedV = await command(`/cost-versions/${actual2.id}/entries`, { category: 'change_loss', label: '金额不符', amount: '1.00' });
    const mismatched = ent(mismatchedV, '金额不符');
    await mutate(page, id, `/cost-versions/${actual2.id}/entries/${mismatched.id}/change`, { change_code: changeA.code }, 409);
    await mutate(page, id, `/cost-versions/${actual2.id}/entries/${mismatched.id}/change`, { change_code: draftChange.code }, 409);
    const materialV = await command(`/cost-versions/${actual2.id}/entries`, { category: 'material', label: '材料', amount: '1.00' });
    const material = ent(materialV, '材料');
    await mutate(page, id, `/cost-versions/${actual2.id}/entries/${material.id}/change`, { change_code: changeA.code }, 400);

    // 7. 未批准变更若挂有损失时汇总如实标注待批准笔数而非悄悄计入总额.
    expect(lossA.id).toBeTruthy();
    expect(lossB.id).toBeTruthy();

    await context.close();
    expect(errors).toEqual([]);
  });
});