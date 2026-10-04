const { test, expect } = require('@playwright/test');
const path = require('node:path');

// E02/E03 试验不合格整改闭环只读汇总 (只读派生): 工程交付 -> 质量试验 页签内"试验整改闭环汇总"面板
// 与逐条试验"整改闭环"列, 按试验维度聚合必检不合格自动生成的阻断整改问题是否已全部关闭并给出闭环率.
// 数据来自 delivery workspace 顶层 :test_remediation_closure 与逐条 test_remediation_* 派生键
// (免迁移/无门控/无新命令/无新路由). 只读派生不放宽写入约束: 未通过必检与其整改未关闭时,
// 逐条试验提交仍由服务端 ready-test! 真实拦截 (409), 关闭整改问题需独立审核人签核.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/e02trc');
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
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '试验整改闭环汇总', exact: true }) }).first();

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '试验整改闭环汇总', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `试验整改审批${suffix}`, role_key: `pms_e2r_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_e2r_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立整改审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `E2R-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `E2R-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `E2R-${suffix}-M1`, name: '主机#1' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, sub, machine, suffix };
}

async function toExecution(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '单机装配任务', owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22', node_id: f.machine.node_id });
  const evidence = await command('/governance/documents', { code: 'E2R-EV', title: '试验交检证据', filename: 'e2r.txt', content: '合成试验记录.' });
  const requirement = await command('/governance/requirements', { code: 'E2R-URS', text: '试验须逐项签核', category: '交付', priority: 'required', owner_id: f.adminId });
  const charter = await command('/governance/charters', { title: '试验章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  for (const stage of ['execution', 'closure']) {
    const template = await command('/governance/gate-templates', { code: `E2R-${stage}`, title: `${stage}工程评审`, stage, required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
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

// 冻结BOM -> 齐套100% -> 齐套Gate, 返回 bom (装配创建所需).
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

// 建立并批准一台装配 (assembly approved).
async function approvedAssembly(f, refs, bom) {
  const assembly = await f.command('/delivery/assemblies', { ...refs, code: 'ASS-1', title: '执行装配', bom_id: bom.id, owner_id: f.adminId });
  await f.command(`/delivery/assemblies/${assembly.id}/start`, { evidence_ids: [f.evidence.id] });
  await approveDelivery(f, 'assemblies', assembly);
  return assembly;
}

const CRITERIA = [{ code: 'Q-1', title: '动作满足URS', required: true }];
// pastDue=true 令整改问题到期日落在过去, 从而在开放期确定性地计入逾期 (与 today 漂移无关).
const resultsBody = (f, passed, pastDue) => ({ checks: [{ code: 'Q-1', passed, actual: passed ? '通过' : '未达标', evidence_ids: [f.evidence.id] }], due_date: pastDue ? '2026-09-01' : '2099-12-31' });

async function makeTest(f, assembly, refs, code, type) {
  return f.command('/delivery/tests', { ...refs, code, title: `${type}验证`, assembly_id: assembly.id, test_type: type, owner_id: f.adminId, criteria: CRITERIA });
}

const testRow = async (page, id, testId) => (await api(page, 'GET', base(id) + '/delivery')).tests.find(r => r.id === testId);
const closure = async (page, id) => (await api(page, 'GET', base(id) + '/delivery')).test_remediation_closure;

test.describe('E02/E03 试验不合格整改闭环只读汇总', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 必检不合格自动形成待关闭整改(pending/逾期) -> 独立关闭整改后翻转为已闭环(resolved); 只读派生不放宽签核门控', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f0 = await fixture(page, browser, '试验整改闭环');
    const f = await toExecution(page, f0);
    const id = f.id;
    const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };
    const bom = await kittedAssemblyBom(f, refs);
    const assembly = await approvedAssembly(f, refs, bom);

    // 1. 尚无整改问题 -> 面板空态; :test_remediation_closure.available=false, 逐条试验派生为 none.
    const sit1 = await makeTest(f, assembly, refs, 'SIT-1', 'SIT');
    await open(page, id, '工程交付', '质量试验');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('尚无试验不合格整改问题, 提交不合格必检结果会自动形成待关闭的整改')).toBeVisible();
    await shot(page, 'e02trc-1-empty.png');
    await shotPanel(page, 'e02trc-1-empty-panel.png');
    let cl = await closure(page, id);
    expect(cl.available).toBe(false);
    expect(cl['tests-with-remediation']).toBe(0);
    expect(cl['issue-total']).toBe(0);
    expect(cl['closure-pct']).toBe(0);
    expect((await testRow(page, id, sit1.id)).test_remediation_state).toBe('none');

    // 2. 登记不合格必检结果 -> 自动生成一条 blocker 整改问题; 只读派生为 pending, 到期日已过 -> 逾期.
    const failing = await f.command(`/delivery/tests/${sit1.id}/results`, resultsBody(f, false, true));
    const issueId = failing.checks[0].issue_id;
    expect(typeof issueId).toBe('string');
    await open(page, id, '工程交付', '质量试验');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('待整改试验 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('整改已闭环 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('整改中 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('逾期未闭环 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('整改问题 0/1 已关闭', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('整改闭环率 0%', { exact: true })).toBeVisible();
    await expect(drawer(page).getByRole('cell', { name: /整改中 0\/1/ })).toBeVisible();
    await expect(drawer(page).getByRole('cell', { name: /逾期 1/ })).toBeVisible();
    await shot(page, 'e02trc-2-pending.png');
    await shotPanel(page, 'e02trc-2-pending-panel.png');
    cl = await closure(page, id);
    expect(cl).toMatchObject({ available: true, 'tests-with-remediation': 1, 'pending-tests': 1, 'resolved-tests': 0, 'overdue-tests': 1, 'issue-total': 1, 'issue-closed': 0, 'issue-open': 1, 'closure-pct': 0 });
    let row = await testRow(page, id, sit1.id);
    expect(row.test_remediation_state).toBe('pending');
    expect(row.test_remediation_total).toBe(1);
    expect(row.test_remediation_open).toBe(1);
    expect(row.test_remediation_closed).toBe(0);
    expect(row.test_remediation_overdue).toBe(1);

    // 只读汇总不改变逐条状态, 门控仍在: 必检未通过时提交 -> 409 (ready-test! 真实拦截).
    await mutate(page, id, `/delivery/tests/${sit1.id}/submit`, { reviewer_id: f.userId, evidence_ids: [f.evidence.id] }, 409);

    // 复验通过 (复用同一整改问题, 试验准则 passed 变 true, 但整改问题仍未关闭 -> 逐条仍 pending; 汇总仍 issue-closed=0).
    await f.command(`/delivery/tests/${sit1.id}/results`, resultsBody(f, true, true));
    row = await testRow(page, id, sit1.id);
    expect(row.test_remediation_state).toBe('pending');
    expect(row.test_remediation_total).toBe(1); // 复验复用同一问题, g/latest 去重后仍计一条
    cl = await closure(page, id);
    expect(cl['issue-closed']).toBe(0);
    expect(cl['closure-pct']).toBe(0);

    // 关闭整改问题: 落实解决 + 独立审核人签核 -> 该试验整改翻转为 resolved, 闭环率 100%.
    await mutate(page, id, `/governance/issues/${issueId}/resolve`, { resolution: '更换执行器并复测达标', evidence_ids: [f.evidence.id], reviewer_id: f.userId });
    await f.command(`/governance/issues/${issueId}/decision`, { decision: 'approved', reason: '独立复验满足' }, f.reviewer);
    await open(page, id, '工程交付', '质量试验');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('待整改试验 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('整改已闭环 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('整改中 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('逾期未闭环 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('整改问题 1/1 已关闭', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('整改闭环率 100%', { exact: true })).toBeVisible();
    await expect(drawer(page).getByRole('cell', { name: '整改已闭环 1/1', exact: true })).toBeVisible();
    await shot(page, 'e02trc-3-resolved.png');
    await shotPanel(page, 'e02trc-3-resolved-panel.png');
    cl = await closure(page, id);
    expect(cl).toMatchObject({ available: true, 'tests-with-remediation': 1, 'pending-tests': 0, 'resolved-tests': 1, 'overdue-tests': 0, 'issue-total': 1, 'issue-closed': 1, 'issue-open': 0, 'closure-pct': 100 });
    row = await testRow(page, id, sit1.id);
    expect(row.test_remediation_state).toBe('resolved');
    expect(row.test_remediation_total).toBe(1);
    expect(row.test_remediation_open).toBe(0);
    expect(row.test_remediation_closed).toBe(1);
    expect(row.test_remediation_overdue).toBe(0);

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
