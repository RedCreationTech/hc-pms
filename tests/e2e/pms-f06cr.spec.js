const { test, expect } = require('@playwright/test');
const path = require('node:path');

// F06 四算版本审批闭环汇总 (只读派生): 项目费用 -> 成本与分摊 页签顶部的"四算版本审批闭环汇总"面板,
// 汇总 草稿/审批中/已批准/已驳回/已取消 徽标 + 审批完成率 + 概算-预算-核算-决算各口径分布.
// 数据来自 finance overview 顶层 :cost_review (整项目全部费用版本只读聚合, 免迁移/无门控).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/f06cr');
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

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:finance:query', 'pms:finance:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `财务审批${suffix}`, role_key: `pms_f06_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_f06_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立财务审批人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `F06-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
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
  const evidence = await command('/governance/documents', { code: 'F06-EV', title: '费用证据', filename: 'f06.txt', content: '合成记录.', stage: 'design', structure_node: 'U1' });
  const charter = await command('/governance/charters', { title: '费用章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  const template = await command('/governance/gate-templates', { code: 'F06-G', title: '执行工程评审', stage: 'execution', required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
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

// 定位"四算版本审批闭环汇总"面板 (section + heading exact, 避免跨面板串台).
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '四算版本审批闭环汇总', exact: true }) }).first();

test.describe('F06 四算版本审批闭环汇总 (只读派生)', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 草稿 -> 批准/驳回/提交/取消混合汇总, 面板与 :cost_review 一致', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await toExecution(page, await fixture(page, browser, '四算审批汇总'));
    const id = f.id;
    const create = (kind, name) => f.command('/cost-versions', { kind, period: '2026-09', currency: 'CNY', name, revenue: '1000', reviewer_id: f.userId });
    const entry = (vid, label) => f.command(`/cost-versions/${vid}/entries`, { category: 'material', label, amount: '100' });
    const submit = vid => f.command(`/cost-versions/${vid}/submit`, {});
    const review = (vid, decision, reason) => f.command(`/cost-versions/${vid}/review`, { decision, reason }, f.reviewer);
    const cancel = (vid, reason) => f.command(`/cost-versions/${vid}/cancel`, { reason });

    // 1. 空态: 尚无费用版本 -> 面板显示引导文案, :cost_review.available = false.
    await openFinanceCost(page, id);
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无四算费用版本')).toBeVisible();
    await shot(page, 'f06cr-1-empty.png');
    let cr = (await api(page, 'GET', base(id) + '/finance')).cost_review;
    expect(cr.available).toBe(false);
    expect(cr.total).toBe(0);
    expect(cr['review-pct']).toBe(0);

    // 2. 建立三份不同口径草稿 -> 草稿 3 / 已作决定 0 / 完成率 0%.
    const v1 = await create('estimate', '概算A');
    const v2 = await create('budget', '预算A');
    const v3 = await create('actual', '核算A');
    await openFinanceCost(page, id);
    await expect(panel(page).getByText('版本总数 3')).toBeVisible();
    await expect(panel(page).getByText('草稿 3')).toBeVisible();
    await expect(panel(page).getByText('共 3 个四算版本, 已作决定 0 个, 待处理 3 个, 审批完成率 0%')).toBeVisible();
    await shot(page, 'f06cr-2-draft.png');
    cr = (await api(page, 'GET', base(id) + '/finance')).cost_review;
    expect(cr).toMatchObject({ available: true, total: 3, draft: 3, pending: 3, processed: 0 });
    expect(cr['review-pct']).toBe(0);

    // 3. 推进为混合生命周期:
    //    v1 概算 提交->独立批准 = approved; v2 预算 提交->独立驳回 = rejected;
    //    v3 核算 提交后停留 submitted; v4 决算 仅草稿 draft; v5 概算 草稿后取消 = cancelled.
    await entry(v1.id, '概算料'); await submit(v1.id); await review(v1.id, 'approved', 'ok');
    await entry(v2.id, '预算料'); await submit(v2.id); await review(v2.id, 'rejected', '超支');
    await entry(v3.id, '核算料'); await submit(v3.id);
    const v4 = await create('settlement', '决算A');
    const v5 = await create('estimate', '概算B'); await cancel(v5.id, '作废草稿');

    await openFinanceCost(page, id);
    await expect(panel(page).getByText('版本总数 5')).toBeVisible();
    await expect(panel(page).getByText('已批准 1')).toBeVisible();
    await expect(panel(page).getByText('审批中 1')).toBeVisible();
    await expect(panel(page).getByText('草稿 1')).toBeVisible();
    await expect(panel(page).getByText('已驳回 1')).toBeVisible();
    await expect(panel(page).getByText('已取消 1')).toBeVisible();
    await expect(panel(page).getByText('共 5 个四算版本, 已作决定 2 个, 待处理 2 个, 审批完成率 40%')).toBeVisible();
    await expect(panel(page).getByText('概算 · 总2 批准1 待0')).toBeVisible();
    await expect(panel(page).getByText('决算 · 总1 批准0 待1')).toBeVisible();
    await shot(page, 'f06cr-3-mixed.png');

    cr = (await api(page, 'GET', base(id) + '/finance')).cost_review;
    expect(cr).toMatchObject({ available: true, total: 5, approved: 1, rejected: 1, submitted: 1, draft: 1, cancelled: 1, processed: 2, pending: 2 });
    expect(cr['review-pct']).toBe(40);
    expect(cr['by-kind'].find(x => x.kind === 'estimate')).toMatchObject({ total: 2, approved: 1, pending: 0 });
    expect(cr['by-kind'].find(x => x.kind === 'settlement')).toMatchObject({ total: 1, approved: 0, pending: 1 });
    // 读模型不改变逐条版本状态.
    const versions = (await api(page, 'GET', base(id) + '/finance')).cost_versions;
    expect(versions.find(x => x.id === v1.id).status).toBe('approved');
    expect(versions.find(x => x.id === v5.id).status).toBe('cancelled');

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
