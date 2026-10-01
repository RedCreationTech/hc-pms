const { test, expect } = require('@playwright/test');
const path = require('node:path');

// E02/E03 试验执行闭环只读汇总 (只读派生): 工程交付 -> 质量试验 页签内"试验执行闭环汇总"面板,
// 按试验生命周期 (草稿/待提交/检验中/已批准/已驳回) 与 SIT/FAT/SAT 类型聚合项目全部试验的执行
// 健康度与必检达标情况 (免迁移/无门控/无新命令/无新路由). 数据来自 delivery workspace 顶层 :test_execution;
// 逐条试验状态不因该汇总改变 (结果登记/提交/签核仍由服务端状态机与证据门控在写入时强制).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/e02tc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const base = id => `/api/pms/projects/${id}`;
const localDate = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const daysAgo = n => { const d = new Date(); d.setDate(d.getDate() - n); return localDate(d); };

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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 面板在抽屉滚动容器内偏下, 通用 shot scrollTo(0,0) 可能截不到 -> 定位后单独截元素图.
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '试验执行闭环汇总', exact: true }) }).first();

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '试验执行闭环汇总', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `试验签核审批${suffix}`, role_key: `pms_e02_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_e02_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立试验审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `E02-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `E02-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `E02-${suffix}-M1`, name: '主机#1' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, sub, machine, suffix };
}

async function toExecution(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '单机装配任务', owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22', node_id: f.machine.node_id });
  const evidence = await command('/governance/documents', { code: 'E02-EV', title: '试验交检证据', filename: 'e02.txt', content: '合成试验记录.' });
  const requirement = await command('/governance/requirements', { code: 'E02-URS', text: '试验须逐项签核', category: '交付', priority: 'required', owner_id: f.adminId });
  const charter = await command('/governance/charters', { title: '试验章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  for (const stage of ['execution', 'closure']) {
    const template = await command('/governance/gate-templates', { code: `E02-${stage}`, title: `${stage}工程评审`, stage, required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
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

async function approveDelivery(f, collection, item, action = 'submit') {
  await f.command(`/delivery/${collection}/${item.id}/${action}`, { evidence_ids: [f.evidence.id], reviewer_id: f.userId });
  return f.command(`/delivery/${collection}/${item.id}/decision`, { decision: 'approved', reason: '独立核查' }, f.reviewer);
}

// 冻结BOM -> 齐套100% -> 齐套Gate (assembly.start 阻断项) -> 返回 bom.
async function kittedAssemblyBom(f, refs) {
  const material = await f.command('/delivery/material-requests', { ...refs, code: 'MR-1', title: '长周期件', request_type: 'long_lead', owner_id: f.adminId, needed_on: '2026-10-01', items: [{ code: 'M-1', name: '执行器', quantity: 2, unit: '个' }, { code: 'M-2', name: '连接线', quantity: 4, unit: '根' }] });
  await approveDelivery(f, 'material-requests', material);
  const bom = await f.command('/delivery/boms', { code: 'BOM-1', title: '受控配置', material_request_id: material.id });
  await approveDelivery(f, 'boms', bom, 'freeze');
  await f.command(`/delivery/boms/${bom.id}/kit`, { items: [{ code: 'M-1', available_quantity: 2 }, { code: 'M-2', available_quantity: 4 }], evidence_ids: [f.evidence.id] });
  const kitting = await f.command('/governance/gate-templates/from-catalog', { gate_type: 'kitting' });
  const gate = await f.command('/governance/gates', { template_id: kitting.id, title: '齐套放行', reviewer_id: f.userId });
  await f.command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'KIT-1', passed: true, evidence_ids: [f.evidence.id] }, { code: 'KIT-2', passed: true, evidence_ids: [f.evidence.id] }] });
  await f.command(`/governance/gates/${gate.id}/submit`, {});
  await f.command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '齐套确认' }, f.reviewer);
  return bom;
}

// 建立并批准一台装配 (assembly approved), 供试验 prerequisites! 通过.
async function approvedAssembly(f, refs, bom) {
  const assembly = await f.command('/delivery/assemblies', { ...refs, code: 'ASS-1', title: '执行装配', bom_id: bom.id, owner_id: f.adminId });
  await f.command(`/delivery/assemblies/${assembly.id}/start`, { evidence_ids: [f.evidence.id] });
  await approveDelivery(f, 'assemblies', assembly);
  return assembly;
}

const CRITERIA = [{ code: 'Q-1', title: '动作满足URS', required: true }];
const resultsBody = f => ({ checks: [{ code: 'Q-1', passed: true, actual: '通过', evidence_ids: [f.evidence.id] }], due_date: '2026-10-03' });

async function makeTest(f, assembly, refs, code, type) {
  return f.command('/delivery/tests', { ...refs, code, title: `${type}验证`, assembly_id: assembly.id, test_type: type, owner_id: f.adminId, criteria: CRITERIA });
}

const byType = (te, key) => te['by-type'].find(x => x.key === key);

test.describe('E02/E03 试验执行闭环只读汇总', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 草稿/待提交/检验中/已批准 四态 + SIT/FAT/SAT 分布, 面板与 :test_execution 一致且只读不门控', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f0 = await fixture(page, browser, '试验执行闭环');
    const f = await toExecution(page, f0);
    const id = f.id;
    const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };
    const bom = await kittedAssemblyBom(f, refs);
    const assembly = await approvedAssembly(f, refs, bom);

    // 1. 尚无试验 -> 面板空态; :test_execution.available=false, total/各态 0, closure-pct=0, by-type 三行全 0.
    await open(page, id, '工程交付', '质量试验');
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无试验记录, 装配通过后建立 SIT/FAT/SAT 质量试验')).toBeVisible();
    await shot(page, 'e02tc-1-empty.png');
    await shotPanel(page, 'e02tc-1-empty-panel.png');
    let te = (await api(page, 'GET', base(id) + '/delivery')).test_execution;
    expect(te.available).toBe(false);
    expect(te.total).toBe(0);
    expect(te.draft).toBe(0);
    expect(te.ready).toBe(0);
    expect(te['in-review']).toBe(0);
    expect(te.approved).toBe(0);
    expect(te.rejected).toBe(0);
    expect(te.open).toBe(0);
    expect(te['results-recorded']).toBe(0);
    expect(te['required-ready']).toBe(0);
    expect(te['closure-pct']).toBe(0);
    expect(te['by-type'].map(x => x.key)).toEqual(['SIT', 'FAT', 'SAT']);
    expect(te['by-type'].every(x => x.total === 0 && x.approved === 0)).toBe(true);

    // 2. 建四台试验: SIT-1 全流程批准; FAT-1 记录结果并提交 -> 检验中; FAT-2 记录结果 -> 待提交; SAT-1 仅建立 -> 草稿.
    const sit1 = await makeTest(f, assembly, refs, 'SIT-1', 'SIT');
    await f.command(`/delivery/tests/${sit1.id}/results`, resultsBody(f));
    await f.command(`/delivery/tests/${sit1.id}/submit`, { reviewer_id: f.userId, evidence_ids: [f.evidence.id] });
    await f.command(`/delivery/tests/${sit1.id}/decision`, { decision: 'approved', reason: '独立签核通过' }, f.reviewer);
    const fat1 = await makeTest(f, assembly, refs, 'FAT-1', 'FAT');
    await f.command(`/delivery/tests/${fat1.id}/results`, resultsBody(f));
    await f.command(`/delivery/tests/${fat1.id}/submit`, { reviewer_id: f.userId, evidence_ids: [f.evidence.id] });
    const fat2 = await makeTest(f, assembly, refs, 'FAT-2', 'FAT');
    await f.command(`/delivery/tests/${fat2.id}/results`, resultsBody(f));
    const sat1 = await makeTest(f, assembly, refs, 'SAT-1', 'SAT');

    await open(page, id, '工程交付', '质量试验');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('试验 4', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('草稿 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('待提交 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('检验中 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已批准 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已驳回 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已登记结果 3', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('必检达标 3/4', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('批准闭环率 25%', { exact: true })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '系统集成试验', exact: true })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '现场验收试验', exact: true })).toBeVisible();
    await shot(page, 'e02tc-2-four-states.png');
    await shotPanel(page, 'e02tc-2-four-states-panel.png');
    te = (await api(page, 'GET', base(id) + '/delivery')).test_execution;
    expect(te).toMatchObject({ available: true, total: 4, draft: 1, ready: 1, 'in-review': 1, approved: 1, rejected: 0, open: 3, 'results-recorded': 3, 'required-ready': 3, 'closure-pct': 25 });
    expect(byType(te, 'SIT').total).toBe(1);
    expect(byType(te, 'SIT').approved).toBe(1);
    expect(byType(te, 'SIT')['approved-pct']).toBe(100);
    expect(byType(te, 'FAT').total).toBe(2);
    expect(byType(te, 'FAT').approved).toBe(0);
    expect(byType(te, 'FAT')['approved-pct']).toBe(0);
    expect(byType(te, 'SAT').total).toBe(1);
    expect(byType(te, 'SAT').approved).toBe(0);
    expect(byType(te, 'SAT')['approved-pct']).toBe(0);

    // 只读汇总不改变逐条试验状态 (SIT-1 approved/FAT-1 in_review/FAT-2 ready/SAT-1 draft); 逐条必检派生标记真实可见.
    const rows = (await api(page, 'GET', base(id) + '/delivery')).tests;
    expect(rows.find(r => r.id === sit1.id).status).toBe('approved');
    expect(rows.find(r => r.id === sit1.id).test_required_all_passed).toBe(true);
    expect(rows.find(r => r.id === fat1.id).status).toBe('in_review');
    expect(rows.find(r => r.id === fat2.id).status).toBe('ready');
    expect(rows.find(r => r.id === fat2.id).test_results_recorded).toBe(true);
    expect(rows.find(r => r.id === sat1.id).status).toBe('draft');
    expect(rows.find(r => r.id === sat1.id).test_results_recorded).toBe(false);
    expect(rows.find(r => r.id === sat1.id).test_required_all_passed).toBe(false);

    // 门控仍在: 已 approved 的 SIT-1 再次提交 -> 409 (状态守卫, 仅 ready/rejected 可提交), 只读汇总不放宽写入约束.
    await mutate(page, id, `/delivery/tests/${sit1.id}/submit`, { reviewer_id: f.userId, evidence_ids: [f.evidence.id] }, 409);

    // 继续闭环: 批准 FAT-1, 批准 FAT-2 (前序 SIT-1 已批), 无 SAT 记录 -> 批准数升至 3, 闭环率 75%, 检验中归 0.
    await f.command(`/delivery/tests/${fat1.id}/decision`, { decision: 'approved', reason: '独立签核' }, f.reviewer);
    await f.command(`/delivery/tests/${fat2.id}/submit`, { reviewer_id: f.userId, evidence_ids: [f.evidence.id] });
    await f.command(`/delivery/tests/${fat2.id}/decision`, { decision: 'approved', reason: '补交后签核' }, f.reviewer);
    await open(page, id, '工程交付', '质量试验');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('已批准 3', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('检验中 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('草稿 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('批准闭环率 75%', { exact: true })).toBeVisible();
    await shot(page, 'e02tc-3-mostly-approved.png');
    await shotPanel(page, 'e02tc-3-mostly-approved-panel.png');
    te = (await api(page, 'GET', base(id) + '/delivery')).test_execution;
    expect(te).toMatchObject({ total: 4, draft: 1, ready: 0, 'in-review': 0, approved: 3, rejected: 0, open: 1, 'results-recorded': 3, 'required-ready': 3, 'closure-pct': 75 });
    expect(byType(te, 'FAT').approved).toBe(2);
    expect(byType(te, 'FAT')['approved-pct']).toBe(100);
    expect(byType(te, 'SAT').approved).toBe(0);

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
