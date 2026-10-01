const { test, expect } = require('@playwright/test');
const path = require('node:path');

// F05 研发费用分摊闭环汇总 (只读派生): 项目费用 -> 成本与分摊 页签的"研发费用分摊闭环汇总"面板,
// 汇总 分摊批次/覆盖任务/生成成本条目/舍入未摊任务/总额守恒 徽标 + 冻结池与摊出金额.
// 数据来自 finance overview 顶层 :allocation_review (整项目全部分摊批次只读聚合, 免迁移/无门控).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/f05ac');
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

// 面板位于抽屉自身滚动容器靠下位置, 通用 scrollTo(0,0) 截不到 -> 滚动到面板后单独截取该 section 元素.
async function shotPanel(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  const card = panel(page);
  await card.scrollIntoViewIfNeeded();
  await expect(card.getByRole('heading', { name: '研发费用分摊闭环汇总', exact: true })).toBeVisible();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:time:approve', 'pms:finance:query', 'pms:finance:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `财务审批${suffix}`, role_key: `pms_f05_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_f05_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立财务审批人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `F05-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
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
  const evidence = await command('/governance/documents', { code: 'F05-EV', title: '分摊证据', filename: 'f05.txt', content: '合成记录.', stage: 'design', structure_node: 'U1' });
  const charter = await command('/governance/charters', { title: '分摊章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  const template = await command('/governance/gate-templates', { code: 'F05-G', title: '执行工程评审', stage: 'execution', required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
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

// 独立批准某任务在指定日期的一段已提交工时.
async function approvedHours(f, taskId, workDate, hours) {
  const entry = await f.command('/time-entries', { task_id: taskId, work_date: workDate, hours, note: '研发', reviewer_id: f.userId });
  await f.command(`/time-entries/${entry.id}/review`, { decision: 'approved', reason: 'ok' }, f.reviewer);
}

// 定位"研发费用分摊闭环汇总"面板 (section + heading exact, 避免跨面板串台).
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '研发费用分摊闭环汇总', exact: true }) }).first();

test.describe('F05 研发费用分摊闭环汇总 (只读派生)', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 单批 -> 双批跨任务汇总, 面板与 :allocation_review 一致', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await toExecution(page, await fixture(page, browser, '研发分摊闭环'));
    const id = f.id;
    const createActual = name => f.command('/cost-versions', { kind: 'actual', period: '2026-09', currency: 'CNY', name, revenue: '1000', reviewer_id: f.userId });
    const allocate = (vid, amount, key, label) => f.command(`/cost-versions/${vid}/allocate`, { amount, from_date: '2026-09-01', to_date: '2026-09-30', idempotency_key: key, label });

    // 1. 空态: 尚无分摊批次 -> 面板显示引导文案, :allocation_review.available = false.
    await openFinanceCost(page, id);
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无费用分摊批次')).toBeVisible();
    await shot(page, 'f05ac-1-empty.png');
    await shotPanel(page, 'f05ac-1-empty-panel.png');
    let ar = (await api(page, 'GET', base(id) + '/finance')).allocation_review;
    expect(ar.available).toBe(false);
    expect(ar.total).toBe(0);
    expect(ar['pool-amount']).toBe('0.00');
    expect(ar.conserved).toBe(true);

    // 2. 单批: 批准任务一 6h -> 建立核算草稿 -> 分摊 1000 元 (覆盖 1 个任务, 生成 1 条人工成本, 守恒).
    await approvedHours(f, f.task.task_id, '2026-09-15', 6);
    const v1 = await createActual('核算批次一');
    await allocate(v1.id, '1000.00', 'alloc-e2e-1', '研发池一');
    await openFinanceCost(page, id);
    await expect(panel(page).getByText('分摊批次 1')).toBeVisible();
    await expect(panel(page).getByText('覆盖任务 1')).toBeVisible();
    await expect(panel(page).getByText('生成成本条目 1')).toBeVisible();
    await expect(panel(page).getByText('总额守恒', { exact: true })).toBeVisible();
    await expect(panel(page).getByText(/冻结费用池合计 1000\.00 元, 实际摊出 1000\.00 元/)).toBeVisible();
    await shot(page, 'f05ac-2-one.png');
    await shotPanel(page, 'f05ac-2-one-panel.png');
    ar = (await api(page, 'GET', base(id) + '/finance')).allocation_review;
    expect(ar).toMatchObject({ available: true, total: 1, 'entry-count': 1, 'task-count': 1, 'zero-task-count': 0, conserved: true });
    expect(ar['pool-amount']).toBe('1000.00');
    expect(ar['allocated-amount']).toBe('1000.00');

    // 3. 双批跨任务: 建任务二批准 4h -> 建核算草稿 -> 分摊 800 元 (期间含两任务工时, 按 6:4 摊出).
    const task2 = await f.command('/tasks', { wbs_code: '2', name: '交付任务二', owner_id: f.adminId, task_type: 'task', duration_days: 2, start_date: '2026-09-22' });
    await approvedHours(f, task2.task_id, '2026-09-16', 4);
    const v2 = await createActual('核算批次二');
    await allocate(v2.id, '800.00', 'alloc-e2e-2', '研发池二');
    await openFinanceCost(page, id);
    await expect(panel(page).getByText('分摊批次 2')).toBeVisible();
    await expect(panel(page).getByText('覆盖任务 2')).toBeVisible();
    await expect(panel(page).getByText('总额守恒', { exact: true })).toBeVisible();
    await expect(panel(page).getByText(/冻结费用池合计 1800\.00 元, 实际摊出 1800\.00 元/)).toBeVisible();
    await shot(page, 'f05ac-3-two.png');
    await shotPanel(page, 'f05ac-3-two-panel.png');

    ar = (await api(page, 'GET', base(id) + '/finance')).allocation_review;
    expect(ar).toMatchObject({ available: true, total: 2, 'task-count': 2, conserved: true });
    expect(ar['pool-amount']).toBe('1800.00');
    expect(ar['allocated-amount']).toBe('1800.00');
    // 批次一 1 行 + 批次二 2 行 = 3 条人工成本.
    expect(ar['entry-count']).toBe(3);

    // 读模型不改变逐条分摊批次: 仍能在费用分摊记录中读到两批.
    const allocations = (await api(page, 'GET', base(id) + '/finance')).allocations;
    expect(allocations.length).toBe(2);

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
