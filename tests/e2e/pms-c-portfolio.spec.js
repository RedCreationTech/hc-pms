const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C/F/G 节: 追踪偏差与覆盖率 (C03), 文档多层下钻 (C04), 密级访问 (C05), 逾期追溯升级 (C09), 我的待办 (C07),
// 全局检索 (G16), 项目组合看板 (G02), 过程看板 (G03), 四算拉通 (F06), 工时更正/封期 (F04), 研发费用池 (F05), 经营目标 (F09).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c-portfolio');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const daysAgo = n => { const d = new Date(); d.setDate(d.getDate() - n); return d.toISOString().slice(0, 10); };

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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
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

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve', 'pms:time:approve', 'pms:finance:query', 'pms:finance:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `组合审批${suffix}`, role_key: `pms_pf_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_pf_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立组合审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `PF-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, suffix, username };
}

async function toExecution(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '交付任务', owner_id: f.adminId, task_type: 'task', duration_days: 2, start_date: '2026-09-22' });
  const evidence = await command('/governance/documents', { code: 'PF-EV', title: '组合证据', filename: 'pf.txt', content: '合成记录.', stage: 'design', structure_node: 'U1' });
  const requirement = await command('/governance/requirements', { code: 'PF-URS', text: '组合看板需求验证', category: '交付', priority: 'required', owner_id: f.adminId });
  const charter = await command('/governance/charters', { title: '组合章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  for (const stage of ['execution', 'closure']) {
    const template = await command('/governance/gate-templates', { code: `PF-${stage}`, title: `${stage}工程评审`, stage, required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
    const gate = await command('/governance/gates', { template_id: template.id, title: `${stage}准入检查`, reviewer_id: f.userId });
    await command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'C1', passed: true, evidence_ids: [evidence.id] }] });
    await command(`/governance/gates/${gate.id}/submit`, {});
    await command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验' }, f.reviewer);
  }
  await mutate(page, f.id, '/transition', { status: 'initiated', reason: 'E2E' });
  await mutate(page, f.id, '/transition', { status: 'planning', reason: 'E2E' });
  const baseline = await command('/planning/submit', { comment: '执行前置已批准计划' });
  await command(`/planning/baselines/${baseline.baseline_id}/review`, { decision: 'approved', comment: '独立批准' }, f.reviewer);
  await mutate(page, f.id, '/transition', { status: 'execution', reason: '前置均已批准' });
  return { ...f, task, evidence, requirement, command };
}

test.describe('C/F/G 节 追踪偏差, 文档下钻与密级, 追溯升级, 待办/检索/组合看板/过程看板, 四算拉通, 工时更正封期, 研发费用池, 经营目标', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('治理侧: 追踪阶段/偏差与覆盖率, 文档多层下钻与机密文档403, 逾期问题追溯升级, 过程看板', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await toExecution(page, await fixture(page, browser, '治理组合'));
    const id = f.id;

    // 1. C03: 界面建立带 FAT 阶段与严重偏差的验证追踪 -> 矩阵显示偏差与覆盖率.
    await open(page, id, '需求与治理', 'URS与追踪');
    await drawer(page).getByRole('button', { name: '建立需求追踪', exact: true }).click();
    const trace = modal(page, '建立需求追踪');
    await choose(page, trace, 'requirement_id', 'PF-URS');
    await choose(page, trace, 'target', '组合证据');
    await choose(page, trace, 'relation', '验证需求');
    await choose(page, trace, 'phase', 'FAT');
    await choose(page, trace, 'deviation_level', '严重');
    await fill(trace, { deviation_note: 'FAT 实测压力偏差 5%' });
    const created = (await save(page, '建立需求追踪')).data.result;
    expect(created.deviation_level).toBe('major');
    await expect(drawer(page).getByText('整链覆盖率 0%')).toBeVisible();
    await expect(drawer(page).getByText('验证覆盖 100%')).toBeVisible();
    await expect(drawer(page).getByText('严重偏差 1')).toBeVisible();
    await shot(page, 'c-1-trace-deviation-coverage.png');
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.trace_summary['verification-pct']).toBe(100);
    expect(ws.trace_summary.deviations.major).toBe(1);

    // 2. C04/C05: 机密文档登记 -> 多层下钻树; 无密级权限审核人读正文 403, 管理员 200.
    const secret = await f.command('/governance/documents', { code: 'PF-SEC', title: '机密工艺文件', filename: 's.txt', content: '机密内容', classification: 'confidential', stage: 'design', structure_node: 'U1' });
    await open(page, id, '需求与治理', '证据版本');
    await expect(drawer(page).getByText('文档多层下钻')).toBeVisible();
    await expect(drawer(page).getByText(/机密 1/).first()).toBeVisible();
    await expect(drawer(page).getByText(/PF-SEC V1 · 机密工艺文件 · 已登记/)).toBeVisible();
    await shot(page, 'c-2-document-tree.png');
    await api(f.reviewer, 'GET', base(id) + `/governance/documents/${secret.id}/content`, undefined, 403);
    expect((await api(page, 'GET', base(id) + `/governance/documents/${secret.id}/content`)).content).toBe('机密内容');

    // 3. C09: 登记已逾期的严重问题 -> "建议追溯升级" -> 界面追溯升级 -> 待升级确认 (逾期追溯) -> 提交解决 409.
    const issue = await f.command('/governance/issues', { title: '逾期未处理的严重问题', severity: 'major', owner_id: f.adminId, due_date: daysAgo(5) });
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, '逾期未处理的严重问题').getByText('逾期未升级, 建议追溯升级')).toBeVisible();
    await row(page, '逾期未处理的严重问题').getByRole('button', { name: '追溯升级' }).click();
    await fill(modal(page, '逾期追溯升级'), { reason: '客户催办' });
    await save(page, '逾期追溯升级');
    await expect(row(page, '逾期未处理的严重问题').getByText('待升级确认 / management (逾期追溯)')).toBeVisible();
    await shot(page, 'c-3-issue-retro-escalation.png');
    await mutate(page, id, `/governance/issues/${issue.id}/resolve`, { resolution: '已处理', evidence_ids: [f.evidence.id], reviewer_id: f.userId }, 409);

    // 4. G03 过程看板: 同一事实的专项过程视图.
    await tab(page, '过程看板');
    await expect(drawer(page).getByText('主机/附件汇总与关口进展')).toBeVisible();
    await expect(drawer(page).getByText('SIT / FAT / SAT')).toBeVisible();
    await expect(drawer(page).getByText('追踪覆盖率 0%')).toBeVisible();
    await shot(page, 'g-3-process-board.png');
    await f.context.close();
    expect(errors).toEqual([]);
  });

  test('平台侧: 我的待办, 全局检索, 项目组合看板, 经营目标, 四算拉通, 工时更正与封期, 研发费用池分摊', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await toExecution(page, await fixture(page, browser, '平台组合'));
    const id = f.id;

    // 1. 待办: 章程修订提交给审核人 -> 审核人页面 待我审批 出现; 责任人逾期问题出现在 我负责的事项.
    const charter = (await api(page, 'GET', base(id) + '/governance')).charters[0];
    const revision = await f.command(`/governance/charters/${charter.id}/revisions`, { title: '组合章程 V2', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
    await f.command(`/governance/charters/${revision.id}/submit`, { reviewer_id: f.userId });
    await f.command('/governance/issues', { title: '待办逾期问题', severity: 'minor', owner_id: f.userId, due_date: daysAgo(2) });
    await f.reviewer.goto('/pms/todo');
    await expect(f.reviewer.getByRole('heading', { name: '我的待办' })).toBeVisible();
    await expect(f.reviewer.getByText('组合章程 V2')).toBeVisible();
    await expect(f.reviewer.getByText('待办逾期问题')).toBeVisible();
    await expect(f.reviewer.getByText('已逾期 2 天').first()).toBeVisible();
    await f.reviewer.screenshot({ path: path.join(output, 'c-4-my-todo.png'), animations: 'disabled' });
    const todo = await api(f.reviewer, 'GET', '/api/pms/todo');
    expect(todo.reviews.some(r => r.title === '组合章程 V2')).toBe(true);
    expect(todo.owned.some(r => r.title === '待办逾期问题' && r.overdue)).toBe(true);

    // 2. 全局检索: 关键字命中需求与项目, 结果回链.
    await page.goto('/pms/search');
    await page.getByPlaceholder('输入关键字').fill('组合看板需求');
    await page.getByRole('button', { name: /^检\s*索$/ }).click();
    await expect(page.getByRole('cell', { name: 'PF-URS', exact: true }).first()).toBeVisible({ timeout: 30000 });
    await expect(page.getByText(/检索结果 \d+/)).toBeVisible();
    await shot(page, 'g-1-global-search.png');

    // 3. 组合看板: 项目行进度/试验/问题, 展开结构节点.
    await page.goto('/pms/portfolio');
    await expect(page.getByRole('heading', { name: '项目组合看板' })).toBeVisible();
    const portfolioRow = page.locator('tbody tr').filter({ hasText: `PF-${f.suffix}` }).first();
    await expect(portfolioRow).toBeVisible();
    await expect(portfolioRow.getByText('阻断 0')).toBeVisible();
    await portfolioRow.locator('.ant-table-row-expand-icon').click();
    await expect(page.getByRole('cell', { name: '主项目', exact: true })).toBeVisible();
    await shot(page, 'g-2-portfolio-board.png');

    // 4. F06 四算拉通: 概算/预算批准后财务页签显示对比与差异.
    const estimate = await f.command('/cost-versions', { kind: 'estimate', period: '2026-09', currency: 'CNY', name: '概算', revenue: '10000', reviewer_id: f.userId });
    await f.command(`/cost-versions/${estimate.id}/entries`, { category: 'material', label: '材料', amount: '3000' });
    await f.command(`/cost-versions/${estimate.id}/submit`, {});
    await f.command(`/cost-versions/${estimate.id}/review`, { decision: 'approved', reason: 'ok' }, f.reviewer);
    const budget = await f.command('/cost-versions', { kind: 'budget', period: '2026-09', currency: 'CNY', name: '预算', revenue: '10000', reviewer_id: f.userId });
    await f.command(`/cost-versions/${budget.id}/entries`, { category: 'material', label: '材料', amount: '3200' });
    await f.command(`/cost-versions/${budget.id}/submit`, {});
    await f.command(`/cost-versions/${budget.id}/review`, { decision: 'approved', reason: 'ok' }, f.reviewer);
    await open(page, id, '项目费用');
    await expect(drawer(page).getByText('预算-概算 200.00')).toBeVisible();
    await expect(drawer(page).getByRole('cell', { name: '3200.00', exact: true }).first()).toBeVisible();
    await shot(page, 'f-1-four-count.png');

    // 5. F04: 提交工时 -> 审核人批准 -> 界面更正 -> 原单已更正; 封期后提交 409.
    // 期间按运行随机取 2026-01..2026-08, 并清理此前运行遗留的封期/费用池, 保证可重复执行.
    const month = String((Date.now() % 8) + 1).padStart(2, '0');
    const period = `2026-${month}`;
    for (const lock of (await api(page, 'GET', '/api/pms/config/period-lock')).rows) {
      if (lock.period === period && lock.status === 'locked') await api(page, 'POST', `/api/pms/config/period-lock/${lock.id}/retire`, { reason: 'E2E 清理' });
    }
    for (const p of (await api(page, 'GET', '/api/pms/config/rd-pool')).rows) {
      if (p.period === period && ['draft', 'frozen'].includes(p.status)) await api(page, 'POST', `/api/pms/config/rd-pool/${p.id}/retire`, { reason: 'E2E 清理' });
    }
    const entry = await f.command('/time-entries', { task_id: f.task.task_id, work_date: `${period}-15`, hours: 4, note: '研发', reviewer_id: f.userId });
    await f.command(`/time-entries/${entry.id}/review`, { decision: 'approved', reason: 'ok' }, f.reviewer);
    await open(page, id, '实际工时');
    await row(page, `${period}-15`).getByRole('button', { name: '更正' }).click();
    const correct = modal(page, '更正已批准工时');
    await fill(correct, { hours: '6', reason: '记错工时' });
    await choose(page, correct, 'reviewer_id', '独立组合审核人');
    await save(page, '更正已批准工时');
    await expect(drawer(page).getByText('已被更正')).toBeVisible();
    await expect(drawer(page).getByText('更正单 · 记错工时')).toBeVisible();
    await shot(page, 'f-2-time-correction.png');
    const correction = (await api(page, 'GET', base(id) + '/time-entries')).rows.find(r => r.corrects_entry_id === entry.id);
    await mutate(f.reviewer, id, `/time-entries/${correction.id}/review`, { decision: 'approved', reason: 'ok' });
    await page.goto('/pms/config');
    await page.getByRole('tab', { name: '工时封期' }).click();
    await page.getByRole('button', { name: '锁定期间' }).click();
    await fill(modal(page, '锁定期间'), { period, reason: '月度封账' });
    await save(page, '锁定期间');
    await expect(page.locator('tbody tr').filter({ hasText: period }).filter({ hasText: '月度封账' }).first().getByText('已锁定')).toBeVisible();
    await mutate(page, id, '/time-entries', { task_id: f.task.task_id, work_date: `${period}-16`, hours: 1, note: '封期后', reviewer_id: f.userId }, 409);
    await shot(page, 'f-3-period-lock.png');

    // 6. F05: 研发费用池建立 -> 冻结 -> 预览守恒 -> 执行分摊 -> 项目生成核算草稿.
    await page.getByRole('tab', { name: '研发费用池' }).click();
    await page.getByRole('button', { name: '建立研发费用池' }).click();
    await fill(modal(page, '建立研发费用池'), { period, amount: '1000', description: '月度研发池' });
    const pool = (await save(page, '建立研发费用池')).data;
    const poolRow = page.locator('tbody tr').filter({ hasText: `POOL-${period}` }).filter({ hasText: '未分摊' }).first();
    await poolRow.getByRole('button', { name: '冻结' }).click();
    await save(page, '冻结费用池');
    await poolRow.getByRole('button', { name: '预览分摊' }).click();
    await expect(page.getByText('总额守恒')).toBeVisible();
    await expect(page.getByRole('dialog', { name: /分摊预览/ }).getByRole('cell', { name: '1000.00', exact: true })).toBeVisible();
    await shot(page, 'f-4-pool-preview.png');
    await page.getByRole('dialog', { name: /分摊预览/ }).getByRole('button', { name: 'Close' }).click();
    await poolRow.getByRole('button', { name: '执行分摊' }).click();
    await choose(page, modal(page, '执行跨项目分摊'), 'reviewer_id', '独立组合审核人');
    const allocated = (await save(page, '执行跨项目分摊')).data;
    expect(allocated.allocation.rows.length).toBeGreaterThanOrEqual(1);
    await expect(page.locator('tbody tr').filter({ hasText: `POOL-${period}` }).getByText(/已分摊到 \d+ 个项目/).first()).toBeVisible();
    await shot(page, 'f-4b-pool-allocated.png');
    const finance = await api(page, 'GET', base(id) + '/finance');
    expect(finance.cost_versions.some(v => v.kind === 'actual' && v.status === 'draft' && v.entries.some(e => e.source_ref === `rd-pool:${pool.id}`))).toBe(true);

    // 7. F09: 界面下达季度目标并发布 -> 看板显示达成率.
    //    目标编码由 年/季度/指标 确定 (2026Q3-revenue), 先退役此前运行遗留的非退役版本, 保证同库可重复执行.
    for (const t of (await api(page, 'GET', '/api/pms/config/quarterly-target')).rows) {
      if (t.code === '2026Q3-revenue' && ['draft', 'published'].includes(t.status)) await api(page, 'POST', `/api/pms/config/quarterly-target/${t.id}/retire`, { reason: 'E2E 清理' });
    }
    await page.goto('/pms/targets');
    await page.getByRole('button', { name: '下达季度目标' }).click();
    const target = modal(page, '下达季度目标');
    await target.locator('#year').fill('2026');
    await choose(page, target, 'quarter', 'Q3');
    await fill(target, { target_value: '5000000', basis: '年度经营计划分解' });
    const targetRecord = (await save(page, '下达季度目标')).data;
    // 看板按 年/季度/指标/版本 升序, 新下达版本是同指标的最后一行 (此前运行遗留的已退役版本排在前面).
    const targetRow = page.locator('tbody tr').filter({ hasText: '2026 Q3' }).filter({ hasText: '收入' }).last();
    await targetRow.getByRole('button', { name: '发布' }).click();
    await save(page, '发布目标版本');
    await expect(targetRow.getByText('已发布')).toBeVisible();
    await expect(targetRow.getByText(/\d+%/)).toBeVisible();
    await shot(page, 'f-5-quarterly-targets.png');
    expect(targetRecord.code).toBe('2026Q3-revenue');
    await f.context.close();
    expect(errors).toEqual([]);
  });
});
