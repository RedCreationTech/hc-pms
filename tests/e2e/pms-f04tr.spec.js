const { test, expect } = require('@playwright/test');
const path = require('node:path');

// F04 工时审核闭环汇总 (只读派生): 项目费用 -> 实际工时 页签顶部的"工时审核闭环汇总"面板,
// 汇总 待审核/已批准/已驳回/已更正/更正待审 徽标 + 审核完成率 + 工时小时分布.
// 数据来自 finance overview 顶层 :timesheet_review (整项目全部工时单只读聚合, 免迁移/无门控).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/f04tr');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
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

async function mutate(page, id, suffix, data = {}, expected = 200) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version }, expected);
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

// 打开 项目费用 -> 内部"实际工时"页签 (与外层抽屉自带的"实际工时"页签同名, 需用 项目费用 面板作用域消歧).
async function openFinanceTime(page, id) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await tab(page, '项目费用');
  await drawer(page).getByLabel('项目费用').getByRole('tab', { name: '实际工时', exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
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
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:time:approve', 'pms:finance:query', 'pms:finance:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `工时审批${suffix}`, role_key: `pms_f04_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_f04_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立工时审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `F04-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
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
  const evidence = await command('/governance/documents', { code: 'F04-EV', title: '工时证据', filename: 'f04.txt', content: '合成记录.', stage: 'design', structure_node: 'U1' });
  const charter = await command('/governance/charters', { title: '工时章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  const template = await command('/governance/gate-templates', { code: 'F04-G', title: '执行工程评审', stage: 'execution', required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
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

// 定位"工时审核闭环汇总"面板 (section + heading exact, 避免跨面板串台).
const panel = (page) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '工时审核闭环汇总', exact: true }) }).first();

test.describe('F04 工时审核闭环汇总 (只读派生)', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 待审核 -> 独立审批/驳回/更正后混合汇总, 面板与 :timesheet_review 一致', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await toExecution(page, await fixture(page, browser, '工时审核汇总'));
    const id = f.id;

    // 1. 空态: 尚无工时单 -> 面板显示引导文案, :timesheet_review.available = false.
    await openFinanceTime(page, id);
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无人提交工时单')).toBeVisible();
    await shot(page, 'f04tr-1-empty.png');
    let tr = (await api(page, 'GET', base(id) + '/finance')).timesheet_review;
    expect(tr.available).toBe(false);
    expect(tr.total).toBe(0);
    expect(tr['review-pct']).toBe(0);

    // 2. 提交两张待审核工时单 -> 待审核 2, 已作决定 0, 完成率 0%; hours 合计 5h 全待审核.
    // 用足够过去的独立日期, 规避跨项目 24h 日容量与其它用例串扰.
    const e1 = await f.command('/time-entries', { task_id: f.task.task_id, work_date: '2026-03-05', hours: 2, note: '开发A', reviewer_id: f.userId });
    const e2 = await f.command('/time-entries', { task_id: f.task.task_id, work_date: '2026-03-06', hours: 3, note: '开发B', reviewer_id: f.userId });
    await openFinanceTime(page, id);
    await expect(panel(page).getByText('待审核 2')).toBeVisible();
    await expect(panel(page).getByText('共 2 张工时单, 已作决定 0 张, 审核完成率 0%')).toBeVisible();
    await expect(panel(page).getByText('工时: 合计 5.00h / 已批准 0.00h / 待审核 5.00h')).toBeVisible();
    await shot(page, 'f04tr-2-pending.png');
    tr = (await api(page, 'GET', base(id) + '/finance')).timesheet_review;
    expect(tr).toMatchObject({ available: true, total: 2, submitted: 2, approved: 0, processed: 0 });
    expect(tr['review-pct']).toBe(0);

    // 3. 独立审核人批准 e1, 驳回 e2 -> 已批准 1 / 已驳回 1, 完成率 100%.
    await f.command(`/time-entries/${e1.id}/review`, { decision: 'approved', reason: 'ok' }, f.reviewer);
    await f.command(`/time-entries/${e2.id}/review`, { decision: 'rejected', reason: '证据不足' }, f.reviewer);

    // 4. 再提交 e3 (4h) 并批准, 然后由填报人对 e3 发起更正 (5h) ->
    //    e3 转"已更正", 更正单待审 -> 待审核 1 / 已批准 1 / 已驳回 1 / 已更正 1 / 更正待审 1;
    //    共 4 张, 已作决定 2 (approved+rejected), 完成率 50%; hours 合计 14h / 已批准 2h / 待审核 5h.
    const e3 = await f.command('/time-entries', { task_id: f.task.task_id, work_date: '2026-03-07', hours: 4, note: '开发C', reviewer_id: f.userId });
    await f.command(`/time-entries/${e3.id}/review`, { decision: 'approved', reason: 'ok' }, f.reviewer);
    await f.command(`/time-entries/${e3.id}/correct`, { hours: 5, note: '开发C(更正)', reason: '记错工时', reviewer_id: f.userId });
    await openFinanceTime(page, id);
    await expect(panel(page).getByText('待审核 1')).toBeVisible();
    await expect(panel(page).getByText('已批准 1')).toBeVisible();
    await expect(panel(page).getByText('已驳回 1')).toBeVisible();
    await expect(panel(page).getByText('已更正 1')).toBeVisible();
    await expect(panel(page).getByText('更正待审 1')).toBeVisible();
    await expect(panel(page).getByText('共 4 张工时单, 已作决定 2 张, 审核完成率 50%')).toBeVisible();
    await expect(panel(page).getByText('工时: 合计 14.00h / 已批准 2.00h / 待审核 5.00h')).toBeVisible();
    await shot(page, 'f04tr-3-mixed.png');

    tr = (await api(page, 'GET', base(id) + '/finance')).timesheet_review;
    expect(tr).toMatchObject({ available: true, total: 4, submitted: 1, approved: 1, rejected: 1, corrected: 1, processed: 2 });
    expect(tr['correction-pending']).toBe(1);
    expect(tr['review-pct']).toBe(50);
    expect(tr['hours-total']).toBe('14.00');
    expect(tr['hours-approved']).toBe('2.00');
    expect(tr['hours-pending']).toBe('5.00');

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
