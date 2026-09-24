const { test, expect } = require('@playwright/test');
const path = require('node:path');

// E 节 (增量6 计划与进度深化): 主/子/单机计划派生 (B03), 主子约束冲突定位 (B03), 项目级阶段权重覆盖 (B02),
// 节点重排保留原基线 (H04), 实际开始/完成日期反馈 (H06), 挣值与完工预测 + 进度快照趋势 (H06/B02),
// 定时扫描同源的逾期提醒进入 我的待办 (B19), Gate 检查项例外放行 (B12/B15), 组合看板 SPI/CPI (B19/G02).
// 流程: 发布带派生层级的设备模板 -> 建项目并应用模板 -> 界面派生计划网络 -> 拉长单机任务制造主子冲突 ->
// 界面覆盖阶段权重 -> 章程/执行关口/基线批准进入执行 -> 界面重排附件单元 (基线仍为已批准) ->
// 界面反馈实际日期 -> 批准工时 -> 界面生成进度快照 -> 审核人待办出现系统提醒 -> 界面 Gate 例外放行 -> 组合看板 SPI/CPI.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/e-progress');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const localDate = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; // 本地日期 (与后端所在时区的 "今天" 一致, 避免 0-8 点 UTC 跨日)
const today = () => localDate(new Date());
const daysAgo = n => { const d = new Date(); d.setDate(d.getDate() - n); return localDate(d); };

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

// 保证已发布的设备模板带派生层级 (levels/default_days): 无则从目录导入, 旧版无 levels 则按目录建修订并发布.
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
  const result = (await api(page, 'GET', '/api/pms/config/project-template')).rows.find(r => r.code === 'TPL-EQUIPMENT' && r.status === 'published');
  expect(withLevels(result)).toBe(true);
  return result;
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:time:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `进度审批${suffix}`, role_key: `pms_pg_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_pg_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立进度审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const projectNo = `PG-${suffix}`;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: projectNo, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-01', end_date: '2027-03-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  const command = (suffix2, data, actor = page) => mutate(actor, id, suffix2, data).then(r => r.result);
  return { id, projectNo, userId, adminId: options.currentUserId, reviewer, context, suffix, username, command };
}

// 章程 + 模板声明的必需执行关口 (需求确认Gate) + 已批准基线 -> 执行.
async function toExecution(page, f) {
  const evidence = await f.command('/governance/documents', { code: 'PG-EV', title: '需求确认证据', filename: 'pg.txt', content: '需求确认记录.', stage: 'design', structure_node: 'U1' });
  const charter = await f.command('/governance/charters', { title: '进度深化章程', objective: '计划网络与挣值可追溯', scope: '单机设备', success_criteria: '主子计划闭环', sponsor_id: f.adminId });
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

test.describe('E 节 增量6 计划与进度深化', () => {
  test.setTimeout(360000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('派生主/子/单机计划, 主子冲突, 阶段权重覆盖, 节点重排保留基线, 实际日期与挣值快照, 系统提醒, Gate例外放行, 组合看板SPI/CPI', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const template = await equipmentTemplate(page);
    const f = await fixture(page, browser, '进度深化');
    const { id, projectNo } = f;

    // 0. 应用模板 (3 节点 / 8 阶段容器), 登记一个审核人负责且已逾期的手工任务与逾期问题 (用于系统提醒).
    const instance = await f.command('/governance/template-instances', { template_id: template.id, reason: 'E2E 建网' });
    expect(instance.node_count).toBe(3);
    const svcTask = await f.command('/tasks', { wbs_code: 'SVC-1', name: '现场服务准备', owner_id: f.userId, task_type: 'task', duration_days: 3, start_date: '2026-09-01', stage_code: 'S1' });
    await f.command('/governance/issues', { title: '进度提醒问题', severity: 'minor', owner_id: f.userId, due_date: daysAgo(3) });

    // 1. B03: 界面派生主/子/单机计划 -> 主计划 5 阶段任务, U1/U2 各 3, M1 4, 共 15 个派生任务 + 11 条 FS 依赖; WBS 表标注 "模板派生".
    await open(page, id, '计划与执行', '进度卷积');
    await drawer(page).getByRole('button', { name: '派生主/子/单机计划', exact: true }).click();
    await fill(modal(page, '派生主/子/单机计划'), { reason: 'E2E 按模板阶段层级派生' });
    const derived = (await save(page, '派生主/子/单机计划')).data.result;
    expect(derived).toMatchObject({ node_count: 3, main_stage_count: 5, derived_count: 15, skipped_count: 0, dependency_count: 11 });
    await tab(page, 'WBS与排程');
    await expect(drawer(page).getByRole('cell', { name: '模板派生', exact: true }).first()).toBeVisible();
    await expect(drawer(page).getByRole('cell', { name: 'S7-MAIN', exact: true })).toBeVisible();
    await expect(drawer(page).getByRole('cell', { name: `${projectNo}-M1-S4`, exact: true })).toBeVisible();
    await shot(page, 'e-1-derived-network.png');
    // 再次派生幂等: 全部跳过, 不重复建任务.
    const again = await f.command('/planning/derive', { reason: '重复派生' });
    expect(again).toMatchObject({ derived_count: 0, skipped_count: 15, dependency_count: 0 });

    // 2. B03: 拉长单机 M1 的装配调试 (S4) 工期 -> 单机现场SAT (M1-S7) 晚于主计划 S7 窗口 -> 冲突面板与头部标签.
    const plan = await api(page, 'GET', base(id) + '/planning');
    const m1s4 = plan.tasks.find(t => t.wbs_code === `${projectNo}-M1-S4`);
    await mutate(page, id, `/tasks/${m1s4.task_id}`, { duration_days: 60 }, 200, 'PUT');
    await open(page, id, '计划与执行', '进度卷积');
    await expect(drawer(page).getByText('主子约束冲突 1', { exact: true })).toBeVisible();
    const conflictRow = drawer(page).locator('tbody tr:visible').filter({ hasText: `${projectNo}-M1-S7` }).first();
    await expect(conflictRow).toBeVisible();
    await expect(conflictRow.getByText(/\d+ 天/)).toBeVisible();
    await conflictRow.scrollIntoViewIfNeeded();
    await shot(page, 'e-2-main-sub-conflict.png');
    const conflicts = (await api(page, 'GET', base(id) + '/planning')).plan_conflicts;
    expect(conflicts).toHaveLength(1);
    expect(conflicts[0]).toMatchObject({ node_code: `${projectNo}-M1`, stage: 'S7' });
    expect(conflicts[0].days_late).toBeGreaterThan(0);

    // 3. B02: 界面覆盖阶段权重 (合计 100) -> 卷积来源变为项目级覆盖, 装配调试权重 30; 合计不为 100 经真实 HTTP 400.
    await drawer(page).getByRole('button', { name: '覆盖阶段权重', exact: true }).click();
    const weights = modal(page, '覆盖阶段权重');
    await fill(weights, { w_S1: 5, w_S2: 15, w_S3: 10, w_S4: 30, w_S5: 15, w_S6: 10, w_S7: 10, w_S8: 5, reason: '装配调试为交付关键, 提高权重' });
    await save(page, '覆盖阶段权重');
    await expect(drawer(page).getByText('权重来自项目级覆盖 (模板快照不变)')).toBeVisible();
    await expect(row(page, '装配调试').getByRole('cell', { name: '30', exact: true })).toBeVisible();
    await shot(page, 'e-3-stage-weight-override.png');
    await mutate(page, id, '/planning/stage-weights', { stages: template.stages.map(s => ({ code: s.code, weight: 20 })), reason: '错误合计' }, 400);
    const weightsPlan = await api(page, 'GET', base(id) + '/planning');
    expect(weightsPlan.stage_weight_source).toBe('project_override');
    expect(weightsPlan.stages.find(s => s.code === 'S4')).toMatchObject({ weight: 30, override: true });

    // 4. 章程/执行关口/基线批准 -> 执行.
    const e = await toExecution(page, f);
    expect((await api(page, 'GET', base(id))).status).toBe('execution');

    // 5. H04: 界面重排附件单元 U2 (未开始任务整体后移) -> 重排记录可见; 已批准基线保持不变, 计划修订递增待再次提交.
    await open(page, id, '计划与执行', '进度卷积');
    await row(page, `${projectNo}-U2`).getByRole('button', { name: '重排', exact: true }).click();
    const reschedule = modal(page, `重排节点计划 ${projectNo}-U2`);
    await fill(reschedule, { start_date: '2026-10-12', reason: '附件单元图纸晚到, 整体后移' });
    await save(page, `重排节点计划 ${projectNo}-U2`);
    await expect(drawer(page).getByText('节点重排记录')).toBeVisible();
    const rsRow = drawer(page).locator('tbody tr:visible').filter({ hasText: '附件单元图纸晚到' }).first();
    await expect(rsRow).toBeVisible();
    await expect(rsRow.getByRole('cell', { name: '2026-10-12', exact: true })).toBeVisible();
    await rsRow.scrollIntoViewIfNeeded();
    await shot(page, 'e-4-node-reschedule.png');
    const afterReschedule = await api(page, 'GET', base(id) + '/planning');
    expect(afterReschedule.reschedules).toHaveLength(1);
    expect(afterReschedule.reschedules[0]).toMatchObject({ node_code: `${projectNo}-U2`, to_start: '2026-10-12', task_count: 3 });
    expect(afterReschedule.reschedules[0].delta_working_days).toBeGreaterThan(0);
    expect(afterReschedule.tasks.find(t => t.wbs_code === `${projectNo}-U2-S2`).start_date).toBe('2026-10-12');
    expect(afterReschedule.baselines.find(b => b.baseline_id === e.baseline.baseline_id).status).toBe('approved');
    expect(afterReschedule.plan_revision).toBeGreaterThan(e.baseline.plan_revision);
    // 后移后的附件单元 研发设计 (U2-S2) 晚于主计划 S2 窗口 -> 冲突定位立即多出一条 (M1-S7 + U2-S2).
    expect(afterReschedule.plan_conflicts.map(c => `${c.node_code}:${c.stage}`).sort()).toEqual([`${projectNo}-M1:S7`, `${projectNo}-U2:S2`]);
    await expect(drawer(page).getByText('主子约束冲突 2', { exact: true })).toBeVisible();
    // 主项目不能重排 (400); 已开始节点不能重排在第 6 步验证.
    const mainNode = afterReschedule.nodes.find(n => n.node_type === 'main');
    await mutate(page, id, `/planning/nodes/${mainNode.node_id}/reschedule`, { start_date: '2026-10-12', reason: 'x' }, 400);

    // 6. H06: 界面反馈实际开始/完成日期 -> 任务表与执行反馈表显示实际日期; 未来日期经真实 HTTP 400.
    await tab(page, 'WBS与排程');
    await row(page, 'S1-MAIN').getByRole('button', { name: '反馈进度', exact: true }).click();
    const done = modal(page, '反馈任务进度');
    await choose(page, done, 'status', '已完成');
    await fill(done, { percent_complete: 100, remaining_days: 0, actual_start: '2026-09-01', actual_end: '2026-09-12', comment: '设计准备实际完成' });
    await save(page, '反馈任务进度');
    await expect(row(page, 'S1-MAIN').getByRole('cell', { name: '2026-09-01 / 2026-09-12', exact: true })).toBeVisible();
    await row(page, 'S2-MAIN').getByRole('button', { name: '反馈进度', exact: true }).click();
    const half = modal(page, '反馈任务进度');
    await choose(page, half, 'status', '处理中');
    await fill(half, { percent_complete: 50, remaining_days: 10, actual_start: '2026-09-14', comment: '研发设计过半' });
    await save(page, '反馈任务进度');
    await expect(row(page, 'S2-MAIN').getByRole('cell', { name: '2026-09-14 / —', exact: true })).toBeVisible();
    await shot(page, 'e-5-feedback-actual-dates.png');
    const s2 = afterReschedule.tasks.find(t => t.wbs_code === 'S2-MAIN');
    await mutate(page, id, `/tasks/${s2.task_id}/feedback`, { status: 'in_progress', percent_complete: 60, remaining_days: 8, actual_end: today(), comment: 'x' }, 400);
    const m1 = afterReschedule.nodes.find(n => n.node_code === `${projectNo}-M1`);
    const m1s3 = afterReschedule.tasks.find(t => t.wbs_code === `${projectNo}-M1-S3`);
    await f.command(`/tasks/${m1s3.task_id}/feedback`, { status: 'in_progress', percent_complete: 20, remaining_days: 12, comment: '备料开始' });
    await mutate(page, id, `/planning/nodes/${m1.node_id}/reschedule`, { start_date: '2026-10-12', reason: '已开始' }, 409);
    // 已批准工时 -> AC (2h = 0.25 个工作日); 工作日按运行轮换, 避免同一用户同日跨项目累计超过 24h.
    const s1 = afterReschedule.tasks.find(t => t.wbs_code === 'S1-MAIN');
    const workDate = `2026-09-0${1 + (Date.now() % 9)}`;
    const entry = await f.command('/time-entries', { task_id: s1.task_id, work_date: workDate, hours: 2, note: '设计准备', reviewer_id: f.userId });
    await f.command(`/time-entries/${entry.id}/review`, { decision: 'approved', reason: 'ok' }, f.reviewer);

    // 7. B19: 管理员对迁移登记的定时任务 9001 "执行一次" -> Quartz 调用 com.ruoyi.task/pms-progress-scan -> 项目异步出现当日快照 (轮询真实 HTTP).
    const job = await api(page, 'GET', '/api/system/job/9001');
    expect(job).toMatchObject({ invoke_target: 'com.ruoyi.task/pms-progress-scan', cron_expression: '0 0 6 * * ?', job_group: 'PMS', status: '0' });
    await api(page, 'PUT', '/api/system/job/9001/run');
    await expect.poll(async () => (await api(page, 'GET', base(id) + '/planning')).progress_history.length, { timeout: 60000, intervals: [1000, 2000, 3000] }).toBe(1);
    // H06: 界面生成进度快照 -> 与定时扫描同日同一条记录覆盖更新 (仍 1 行), 挣值面板 (PV/EV/AC/SPI/CPI/EAC) 与趋势表当日行.
    await open(page, id, '计划与执行', '进度卷积');
    await drawer(page).getByRole('button', { name: '生成进度快照', exact: true }).click();
    const snapshot = (await save(page, '生成进度快照')).data;
    expect(snapshot.snapshot_id).toBeTruthy();
    expect(snapshot.reminders.open).toBeGreaterThanOrEqual(2);
    await expect(drawer(page).getByText('挣值与完工预测')).toBeVisible();
    await expect(drawer(page).getByText(/^SPI \d+(\.\d+)?$/).first()).toBeVisible();
    await drawer(page).getByText('BAC 计划总工作日').scrollIntoViewIfNeeded();
    await shot(page, 'e-6-earned-value.png');
    const history = drawer(page).locator('tbody tr:visible').filter({ hasText: today() }).first();
    await expect(history).toBeVisible();
    await history.scrollIntoViewIfNeeded();
    await shot(page, 'e-6b-progress-history.png');
    const again2 = await mutate(page, id, '/planning/snapshot', {});
    expect(again2.snapshot_id).toBe(snapshot.snapshot_id);
    const evPlan = await api(page, 'GET', base(id) + '/planning');
    expect(evPlan.progress_history).toHaveLength(1);
    expect(evPlan.progress_history[0]).toMatchObject({ snapshot_date: today(), conflict_count: 2 });
    const ev = evPlan.earned_value;
    expect(ev.unit).toBe('working_days');
    expect(ev.ev_days).toBeGreaterThan(0);
    expect(ev.ac_days).toBe(0.25);
    expect(ev.pv_days).toBeGreaterThan(0);
    expect(ev.spi).toBeGreaterThan(0);
    expect(ev.cpi).toBeGreaterThan(0);
    expect(['on_track', 'behind', 'ahead']).toContain(ev.schedule_status);
    expect(ev.forecast_finish).toBeTruthy();
    expect(ev.stages.find(s => s.key === 'S1')).toMatchObject({ ev_days: 10, ac_days: 0.25 });

    // 8. B19: 审核人的 我的待办 出现系统提醒 (任务逾期 SVC-1, 问题逾期); 管理员手动触发全量扫描覆盖执行中项目.
    await f.reviewer.goto('/pms/todo');
    await expect(f.reviewer.getByRole('heading', { name: '我的待办' })).toBeVisible();
    await expect(f.reviewer.getByText(/系统提醒 [1-9]\d*/).first()).toBeVisible();
    await expect(f.reviewer.getByText('SVC-1 现场服务准备')).toBeVisible();
    await expect(f.reviewer.getByText('系统提醒: 任务逾期').first()).toBeVisible();
    await expect(f.reviewer.getByText('系统提醒: 问题逾期').first()).toBeVisible();
    await f.reviewer.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await f.reviewer.screenshot({ path: path.join(output, 'e-7-todo-system-reminders.png'), animations: 'disabled' });
    const todo = await api(f.reviewer, 'GET', '/api/pms/todo');
    expect(todo.reminders.filter(r => r.project_id === id).map(r => r.target_kind).sort()).toEqual(['issue', 'task']);
    expect(todo.summary.reminders).toBeGreaterThanOrEqual(2);
    // 全量扫描同步处理全部在途项目, 耗时随共享库项目数增长 (本库 66 个在途项目约 32 秒), 单独放宽该调用的超时.
    const scan = await api(page, 'POST', '/api/pms/scan', {}, 200, 180000);
    expect(scan.results.some(r => r.project_id === id && r.snapshot_id === snapshot.snapshot_id)).toBe(true);
    await api(f.reviewer, 'POST', '/api/pms/scan', {}, 403);
    // 逾期任务完成 -> 再扫描后提醒自动关闭.
    await f.command(`/tasks/${svcTask.task_id}/feedback`, { status: 'done', percent_complete: 100, remaining_days: 0, actual_start: '2026-09-01', actual_end: '2026-09-03', comment: '补录完成' });
    const rescan = await mutate(page, id, '/planning/snapshot', {});
    expect(rescan.reminders.closed).toBe(1);
    expect((await api(f.reviewer, 'GET', '/api/pms/todo')).reminders.filter(r => r.project_id === id).map(r => r.target_kind)).toEqual(['issue']);

    // 9. B12/B15: 装配与测试交接Gate 界面填写检查 -> AT-4 例外放行 (需说明) -> 关口进展标注 "例外 1"; 审核人批准仍通过证据校验.
    await open(page, id, '需求与治理', 'Gate评审');
    await drawer(page).getByRole('button', { name: '发起Gate检查', exact: true }).click();
    const gateForm = modal(page, '发起Gate检查');
    await fill(gateForm, { title: '装配测试交接检查' });
    await choose(page, gateForm, 'template_id', '装配与测试交接Gate (G5)');
    await choose(page, gateForm, 'reviewer_id', f.username);
    await save(page, '发起Gate检查');
    await row(page, '装配测试交接检查').getByRole('button', { name: '填写检查', exact: true }).click();
    const checks = modal(page, '填写Gate检查结果');
    for (const code of ['AT-1', 'AT-2', 'AT-3']) {
      await choose(page, checks, `passed_${code}`, '检查通过');
      await choose(page, checks, `evidence_${code}`, 'PG-EV');
    }
    await choose(page, checks, 'passed_AT-4', '例外放行 (需说明)');
    await fill(checks, { 'waiver_AT-4': '测试工装下周到位, 接收责任人同意先行交接' });
    await save(page, '填写Gate检查结果');
    await expect(drawer(page).getByText('例外 1', { exact: true })).toBeVisible();
    await expect(drawer(page).getByText('检查 4/4', { exact: true })).toBeVisible();
    await drawer(page).getByText('Gate进展汇总', { exact: true }).scrollIntoViewIfNeeded();
    await shot(page, 'e-8-gate-waiver.png');
    const gate = (await api(page, 'GET', base(id) + '/governance')).gates.find(g => g.title === '装配测试交接检查');
    expect(gate.checks.find(c => c.code === 'AT-4')).toMatchObject({ waived: true, passed: false });
    await f.command(`/governance/gates/${gate.id}/submit`, {});
    await f.command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '例外项已登记, 同意交接' }, f.reviewer);
    // 例外缺少说明经真实 HTTP 400.
    const gate2 = await f.command('/governance/gates', { template_id: gate.template_id, title: '例外缺说明', reviewer_id: f.userId });
    await mutate(page, id, `/governance/gates/${gate2.id}/checks`, { checks: gate.checks.map(c => ({ code: c.code, passed: false, evidence_ids: [], waived: true, waiver_reason: '' })) }, 400);

    // 10. G02/B19: 组合看板显示 SPI / CPI 列 (来自当日快照).
    await page.goto('/pms/portfolio');
    await expect(page.getByRole('heading', { name: '项目组合看板' })).toBeVisible();
    const portfolioRow = page.locator('tbody tr').filter({ hasText: projectNo }).first();
    await expect(portfolioRow).toBeVisible();
    await expect(portfolioRow.getByText(/^SPI \d+(\.\d+)?$/)).toBeVisible();
    await expect(portfolioRow.getByText(/^CPI \d+(\.\d+)?$/)).toBeVisible();
    await portfolioRow.scrollIntoViewIfNeeded();
    await shot(page, 'e-9-portfolio-spi-cpi.png');
    const card = (await api(page, 'GET', '/api/pms/portfolio')).projects.find(p => p.project_id === id);
    expect(card.snapshot_date).toBe(today());
    expect(card.spi).toBeGreaterThan(0);
    expect(card.cpi).toBeGreaterThan(0);
    expect(card.stages.find(s => s.code === 'S4').weight).toBe(30);

    expect(errors).toEqual([]);
    await f.context.close();
  });
});
