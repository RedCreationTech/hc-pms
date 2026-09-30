const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H06 挣值偏差纠正措施登记与闭环: SPI/CPI 低于 0.9 自动识别为需纠正的项目偏差 (只读派生),
// 界面把偏差登记为有负责人和到期日的可追踪纠正措施 (复用会议行动类型), 措施落实状态在偏差面板聚合可见,
// 并复用既有行动 "提交完成结果与证据 -> 独立核验" 闭环翻转为已闭环.
// 流程: 发布设备模板 -> 建项目并派生主/子/单机计划 (不做任何进度反馈 -> SPI<0.9 触发进度落后偏差) ->
// 章程/执行关口/基线批准进入执行 -> 界面确认偏差面板显示 "进度落后 / 尚未落实" ->
// 界面登记纠正措施 -> 措施落实翻转为 "落实中 (1 项 · 未闭环 1)" -> 非法偏差种类经真实 HTTP 400 ->
// 提交完成结果+证据并经独立核验 -> 界面措施落实翻转为 "已闭环 (1 项 · 未闭环 0)".
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h06-variance');
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

async function api(page, method, url, data, expected = 200, timeout = 30000) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data, timeout });
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

async function mutate(page, id, suffix, data = {}, expected = 200, method = 'POST') {
  const project = await api(page, 'GET', base(id));
  return api(page, method, base(id) + suffix, { ...data, version: project.version }, expected);
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
  const input = form.locator(`[id="${key}"]`);
  await input.click();
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`[id="${key}"]`).fill(String(value));
}

async function save(page, title, expected = 200) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(expected);
  if (expected === 200) { await expect(form).toBeHidden(); await page.waitForLoadState('networkidle'); }
  return body;
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function equipmentTemplate(page) {
  const listing = await api(page, 'GET', '/api/pms/config/project-template');
  const catalog = listing.catalog.find(item => item.code === 'TPL-EQUIPMENT');
  const rows = listing.rows.filter(r => r.code === 'TPL-EQUIPMENT');
  const published = rows.find(r => r.status === 'published');
  const withLevels = t => t && t.stages.every(s => Array.isArray(s.levels) && s.levels.length > 0);
  if (withLevels(published)) return published;
  const draft = rows.find(r => r.status === 'draft');
  if (draft) {
    await api(page, 'POST', `/api/pms/config/project-template/${draft.id}/publish`, { reason: 'E2E 发布' });
  } else if (rows.length === 0) {
    await api(page, 'POST', '/api/pms/config/project-template/import', { code: 'TPL-EQUIPMENT' });
    const imported = (await api(page, 'GET', '/api/pms/config/project-template')).rows.find(r => r.code === 'TPL-EQUIPMENT' && r.status === 'draft');
    await api(page, 'POST', `/api/pms/config/project-template/${imported.id}/publish`, { reason: 'E2E 发布' });
  } else {
    const latest = rows.reduce((a, b) => (a.revision > b.revision ? a : b));
    const revised = await api(page, 'POST', `/api/pms/config/project-template/${latest.id}/revisions`, { ...catalog, reason: 'E2E 升级为带派生层级的阶段定义' });
    await api(page, 'POST', `/api/pms/config/project-template/${revised.id}/publish`, { reason: 'E2E 发布' });
  }
  return (await api(page, 'GET', '/api/pms/config/project-template')).rows.find(r => r.code === 'TPL-EQUIPMENT' && r.status === 'published');
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:time:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `偏差审批${suffix}`, role_key: `pms_hv_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_hv_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立偏差审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const projectNo = `HV-${suffix}`;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: projectNo, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-01', end_date: '2027-03-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  const command = (suffix2, data, actor = page) => mutate(actor, id, suffix2, data).then(r => r.result);
  return { id, projectNo, userId, adminId: options.currentUserId, reviewer, context, suffix, username, command };
}

async function toExecution(page, f) {
  const evidence = await f.command('/governance/documents', { code: 'HV-EV', title: '偏差核验证据', filename: 'hv.txt', content: '实测与核验记录.' });
  const charter = await f.command('/governance/charters', { title: '偏差纠偏章程', objective: '挣值偏差可追溯纠正', scope: '单机设备', success_criteria: '措施闭环', sponsor_id: f.adminId });
  await f.command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await f.command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  const ws = await api(page, 'GET', base(f.id) + '/governance');
  for (const template of ws.gate_templates.filter(t => t.stage === 'execution' && t.required)) {
    const gate = await f.command('/governance/gates', { template_id: template.id, title: `${template.title} 检查`, reviewer_id: f.userId });
    await f.command(`/governance/gates/${gate.id}/checks`, { checks: template.checks.map(c => ({ code: c.code, passed: true, evidence_ids: [evidence.id] })) });
    await f.command(`/governance/gates/${gate.id}/submit`, {});
    await f.command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验' }, f.reviewer);
  }
  await mutate(page, f.id, '/transition', { status: 'initiated', reason: 'E2E' });
  await mutate(page, f.id, '/transition', { status: 'planning', reason: 'E2E' });
  const baseline = await f.command('/planning/submit', { comment: '派生后的主/子/单机计划基线' });
  await f.command(`/planning/baselines/${baseline.baseline_id}/review`, { decision: 'approved', comment: '独立批准' }, f.reviewer);
  await mutate(page, f.id, '/transition', { status: 'execution', reason: '前置均已批准' });
  return { ...f, evidence, baseline };
}

test.describe('H06 挣值偏差纠正措施登记与闭环', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('识别进度落后偏差, 界面登记纠正措施并复用行动独立核验闭环', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const template = await equipmentTemplate(page);
    const f = await fixture(page, browser, '偏差纠偏');
    const { id, projectNo } = f;

    // 派生主/子/单机计划 (不做任何进度反馈 -> EV 远低于 PV -> SPI<0.9 -> 进度落后偏差; 无已批准工时 -> 无成本偏差).
    await f.command('/governance/template-instances', { template_id: template.id, reason: 'E2E 建网' });
    await open(page, id, '计划与执行', '进度卷积');
    await drawer(page).getByRole('button', { name: '派生主/子/单机计划', exact: true }).click();
    await fill(modal(page, '派生主/子/单机计划'), { reason: 'E2E 派生用于偏差验证' });
    const derived = (await save(page, '派生主/子/单机计划')).data.result;
    expect(derived.derived_count).toBeGreaterThan(0);

    // 章程 + 执行关口 + 已批准基线 -> 执行.
    const e = await toExecution(page, f);
    expect((await api(page, 'GET', base(id))).status).toBe('execution');

    // 真实 HTTP 佐证: 读模型派生出 schedule 偏差, 初始落实状态尚未落实.
    const plan1 = await api(page, 'GET', base(id) + '/planning');
    expect(plan1.earned_value.schedule_status).toBe('behind');
    const vs = plan1.performance_variances;
    expect(vs.length).toBeGreaterThanOrEqual(1);
    const sched = vs.find(v => v.variance_kind === 'schedule');
    expect(sched).toMatchObject({ metric: 'spi', variance_action_state: 'unimplemented', variance_action_total: 0, variance_action_open: 0 });

    // 重新加载界面, 让前端携带 toExecution 后的最新项目版本 (避免登记时版本陈旧 409).
    await open(page, id, '计划与执行', '进度卷积');

    // 1. 界面偏差面板显示 "进度落后 / 尚未落实" 与触发指标.
    const panel = drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '绩效偏差与纠正措施', exact: true }) });
    await expect(panel).toBeVisible();
    await expect(panel.getByRole('cell', { name: '进度落后', exact: true })).toBeVisible();
    await expect(panel.getByRole('cell', { name: /尚未落实/ }).first()).toBeVisible();
    await expect(panel.getByText(/spi = /).first()).toBeVisible();
    await panel.scrollIntoViewIfNeeded();
    await shot(page, 'h06-1-variance-detected.png');

    // 2. 界面登记纠正措施 -> 复用会议行动类型 (open) -> 措施落实翻转为 "落实中 (1 项 · 未闭环 1)".
    await panel.getByRole('button', { name: '登记纠正措施', exact: true }).first().click();
    const regModal = modal(page, '登记进度偏差纠正措施');
    await fill(regModal, { title: '重排并行任务压缩关键路径', due_date: '2026-12-01' });
    const ownerSel = regModal.locator('[id="owner_id"]');
    await ownerSel.click();
    await ownerSel.type('admin', { delay: 30 });
    await expect(page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({ hasText: 'admin' }).first()).toBeVisible();
    await page.keyboard.press('Enter');
    await expect(regModal.getByText('选择责任人')).toHaveCount(0); // 责任人已选中, 占位符消失
    await save(page, '登记进度偏差纠正措施');
    await expect(panel.getByRole('cell', { name: /落实中/ }).first()).toBeVisible();
    await expect(panel.getByText(/1 项 · 未闭环 1/).first()).toBeVisible();
    await panel.scrollIntoViewIfNeeded();
    await shot(page, 'h06-2-action-registered.png');

    // 非法偏差种类经真实 HTTP 拒绝 (仅 schedule / cost).
    await mutate(page, id, '/governance/actions/from-variance', { title: '非法种类', owner_id: f.adminId, due_date: '2026-12-01', variance_kind: 'risk' }, 400);

    // 3. 关闭: 提交完成结果与证据 -> 独立核验通过 (复用既有行动闭环端点).
    const actions = (await api(page, 'GET', base(id) + '/governance')).actions;
    const aid = actions.find(a => a.variance_kind === 'schedule').id;
    await mutate(page, id, `/governance/actions/${aid}/complete`, { result: '已重排并行任务并附实测记录', evidence_ids: [e.evidence.id], reviewer_id: f.userId });
    await mutate(f.reviewer, id, `/governance/actions/${aid}/verify`, { decision: 'approved', reason: '独立核验通过' }, 200);

    const closedRow = (await api(page, 'GET', base(id) + '/governance')).actions.find(a => a.id === aid);
    expect(closedRow.status).toBe('closed');
    await open(page, id, '计划与执行', '进度卷积');
    await expect(panel.getByRole('cell', { name: /已闭环/ }).first()).toBeVisible();
    await expect(panel.getByText(/1 项 · 未闭环 0/).first()).toBeVisible();
    await panel.scrollIntoViewIfNeeded();
    await shot(page, 'h06-3-action-closed.png');

    expect(errors).toEqual([]);
    await f.context.close();
  });
});
