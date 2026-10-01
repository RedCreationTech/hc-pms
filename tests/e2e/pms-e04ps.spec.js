const { test, expect } = require('@playwright/test');
const path = require('node:path');

// E04 发货前条件满足度只读汇总 (只读派生): 工程交付 -> 发运签收 页签内"发货前条件满足度汇总"面板,
// 汇总 FAT/整改/入库/提货款 各发货前条件在项目全部发货单上的适用与满足分布及可放行比例
// (免迁移/无门控/无新命令/无新路由). 数据来自 delivery workspace 顶层 :preship_readiness;
// 逐条发货单的状态与条件清单不因该汇总改变 (真正的放行门控仍由服务端 conditions-ready! 在写入时强制).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/e04ps');
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
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '发货前条件满足度汇总', exact: true }) }).first();

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '发货前条件满足度汇总', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `发运放行审批${suffix}`, role_key: `pms_e04_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_e04_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立放行审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `E04-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `E04-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `E04-${suffix}-M1`, name: '主机#1' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, sub, machine, suffix };
}

async function toExecution(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '单机装配任务', owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22', node_id: f.machine.node_id });
  const evidence = await command('/governance/documents', { code: 'E04-EV', title: '发货前条件证据', filename: 'e04.txt', content: '合成装配/试验/入库记录.' });
  const requirement = await command('/governance/requirements', { code: 'E04-URS', text: '发货前条件须逐项确认', category: '交付', priority: 'required', owner_id: f.adminId });
  const charter = await command('/governance/charters', { title: '发运章程', objective: '受控交付', scope: '合成系统', success_criteria: '条件闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  for (const stage of ['execution', 'closure']) {
    const template = await command('/governance/gate-templates', { code: `E04-${stage}`, title: `${stage}工程评审`, stage, required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
    const gate = await command('/governance/gates', { template_id: template.id, title: `${stage}准入检查`, reviewer_id: f.userId });
    await command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'C1', passed: true, evidence_ids: [evidence.id] }] });
    await command(`/governance/gates/${gate.id}/submit`, {});
    await command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验' }, f.reviewer);
  }
  await mutate(page, f.id, '/transition', { status: 'initiated', reason: 'E2E' });
  await mutate(page, f.id, '/transition', { status: 'planning', reason: 'E2E' });
  const baseline = await command('/planning/submit', { comment: '执行前置已批准计划' });
  await command(`/planning/baselines/${baseline.baseline_id}/review`, { decision: 'approved', comment: '独立批准' }, f.reviewer);
  // 执行前配置发货前条件 (入库/提货款) 与适用试验, 使 preship_checklist 出现 4 项条件.
  await command('/delivery/configuration', { required_stages: ['materials', 'assembly', 'quality', 'shipment'], required_test_types: ['SIT', 'FAT', 'SAT'], pre_ship_conditions: ['warehouse_in', 'payment'], reason: 'E2E 发货前条件配置' });
  await mutate(page, f.id, '/transition', { status: 'execution', reason: '前置均已批准' });
  return { ...f, task, evidence, requirement, command };
}

const STEPS = ['on_island', 'assembling', 'unit_inspection', 'wiring_inspection', 'off_island', 'handover'];

// 冻结BOM -> 齐套100% -> 齐套Gate (assembly.start 阻断项解除) -> 返回 bom.
async function kittedAssemblyBom(command, f, refs) {
  const ev = f.evidence.id;
  const material = await command('/delivery/material-requests', { ...refs, code: 'MR-1', title: '长周期件', request_type: 'long_lead', owner_id: f.adminId, needed_on: '2026-10-01', items: [{ code: 'M-1', name: '执行器', quantity: 2, unit: '个' }, { code: 'M-2', name: '连接线', quantity: 4, unit: '根' }] });
  await command(`/delivery/material-requests/${material.id}/submit`, { evidence_ids: [ev], reviewer_id: f.userId });
  await command(`/delivery/material-requests/${material.id}/decision`, { decision: 'approved', reason: '独立核查' }, f.reviewer);
  const bom = await command('/delivery/boms', { code: 'BOM-1', title: '受控配置', material_request_id: material.id });
  await command(`/delivery/boms/${bom.id}/freeze`, { evidence_ids: [ev], reviewer_id: f.userId });
  await command(`/delivery/boms/${bom.id}/decision`, { decision: 'approved', reason: '冻结确认' }, f.reviewer);
  await command(`/delivery/boms/${bom.id}/kit`, { items: [{ code: 'M-1', available_quantity: 2 }, { code: 'M-2', available_quantity: 4 }], evidence_ids: [ev] });
  const kitting = await command('/governance/gate-templates/from-catalog', { gate_type: 'kitting' });
  const gate = await command('/governance/gates', { template_id: kitting.id, title: '齐套放行', reviewer_id: f.userId });
  await command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'KIT-1', passed: true, evidence_ids: [ev] }, { code: 'KIT-2', passed: true, evidence_ids: [ev] }] });
  await command(`/governance/gates/${gate.id}/submit`, {});
  await command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '齐套确认' }, f.reviewer);
  return bom;
}

// 装配 -> 开工 -> 独立交检批准; SIT/FAT 试验建立并独立批准; 返回已批准的 assembly (满足 fat-ok? 与 blocker-free?).
async function approvedAssemblyWithTests(command, f, refs, bom) {
  const ev = f.evidence.id;
  const assembly = await command('/delivery/assemblies', { ...refs, code: 'ASS-1', title: '主机装配', bom_id: bom.id, owner_id: f.adminId });
  await command(`/delivery/assemblies/${assembly.id}/start`, { evidence_ids: [ev] });
  await command(`/delivery/assemblies/${assembly.id}/submit`, { evidence_ids: [ev], reviewer_id: f.userId });
  await command(`/delivery/assemblies/${assembly.id}/decision`, { decision: 'approved', reason: '交检通过' }, f.reviewer);
  for (const type of ['SIT', 'FAT']) {
    const test = await command('/delivery/tests', { ...refs, code: `${type}-1`, title: `${type}验证`, assembly_id: assembly.id, test_type: type, owner_id: f.adminId, criteria: [{ code: 'Q-1', title: '动作满足URS', required: true }] });
    await command(`/delivery/tests/${test.id}/results`, { checks: [{ code: 'Q-1', passed: true, actual: '通过', evidence_ids: [ev] }], due_date: '2026-10-03' });
    await command(`/delivery/tests/${test.id}/submit`, { evidence_ids: [ev], reviewer_id: f.userId });
    await command(`/delivery/tests/${test.id}/decision`, { decision: 'approved', reason: '试验合格' }, f.reviewer);
  }
  return assembly;
}

test.describe('E04 发货前条件满足度只读汇总', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 发货单条件未齐 -> 独立放行后条件齐备, 面板与 :preship_readiness 一致', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f0 = await fixture(page, browser, '发货前条件汇总');
    const f = await toExecution(page, f0);
    const id = f.id;
    const command = f.command;
    const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };

    // 冻结BOM + 齐套Gate + 已批准装配 + 已批准SIT/FAT (使发货单能进入放行路径), 但此时尚未建立发货单.
    const bom = await kittedAssemblyBom(command, f, refs);
    const assembly = await approvedAssemblyWithTests(command, f, refs, bom);

    // 1. 尚无发货单 -> 面板空态; :preship_readiness.available=false, total/ready/blocked/released/readiness-pct 0.
    await open(page, id, '工程交付', '发运签收');
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无发货单, 质量试验合格后建立发运计划')).toBeVisible();
    await shot(page, 'e04ps-1-empty.png');
    await shotPanel(page, 'e04ps-1-empty-panel.png');
    let pr = (await api(page, 'GET', base(id) + '/delivery')).preship_readiness;
    expect(pr.available).toBe(false);
    expect(pr.total).toBe(0);
    expect(pr['readiness-pct']).toBe(0);
    expect(pr['by-condition'].map(x => x.code)).toEqual(['fat', 'remediation', 'warehouse_in', 'payment']);
    expect(pr['by-condition'].every(x => x.applicable === 0)).toBe(true);

    // 2. 建立发货单 (入库/提货款未确认) -> FAT/整改已满足, 入库/提货款未满足 -> 条件齐备 0 / 条件未齐 1 / 可放行率 0%.
    const shipment = await command('/delivery/shipments', { ...refs, code: 'SHIP-1', title: '设备发运', assembly_ids: [assembly.id], consignee: '现场接收团队', delivery_address: '客户指定地址', planned_date: '2026-10-05', reviewer_id: f.userId });
    await open(page, id, '工程交付', '发运签收');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('发货单 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('条件齐备 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('条件未齐 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已放行 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('可放行率 0%', { exact: true })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '入库/装箱已确认', exact: true })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '提货款条件已确认', exact: true })).toBeVisible();
    await shot(page, 'e04ps-2-unready.png');
    await shotPanel(page, 'e04ps-2-unready-panel.png');
    pr = (await api(page, 'GET', base(id) + '/delivery')).preship_readiness;
    expect(pr).toMatchObject({ available: true, total: 1, ready: 0, blocked: 1, released: 0, 'readiness-pct': 0 });
    expect(pr['by-condition'].map(x => x.applicable)).toEqual([1, 1, 1, 1]);
    expect(pr['by-condition'].map(x => x.satisfied)).toEqual([1, 1, 0, 0]);

    // 3. 确认入库/提货款 -> 4 项条件全满足 (可放行率 100%), 但发货单仍 draft (未放行) -> 已放行 0.
    await command(`/delivery/shipments/${shipment.id}/conditions`, { warehouse_in_confirmed: true, warehouse_note: 'WMS入库单 IN-001', payment_confirmed: true, payment_note: '财务确认提货款到账', evidence_ids: [f.evidence.id] });
    await open(page, id, '工程交付', '发运签收');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('条件齐备 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('可放行率 100%', { exact: true })).toBeVisible();
    pr = (await api(page, 'GET', base(id) + '/delivery')).preship_readiness;
    expect(pr).toMatchObject({ total: 1, ready: 1, blocked: 0, released: 0, 'readiness-pct': 100 });
    expect(pr['by-condition'].map(x => x.satisfied)).toEqual([1, 1, 1, 1]);

    // 4. 提交放行并由独立审核人批准 -> status released -> 已放行 1.
    await command(`/delivery/shipments/${shipment.id}/submit`, { evidence_ids: [f.evidence.id] });
    await command(`/delivery/shipments/${shipment.id}/decision`, { decision: 'approved', reason: '独立放行' }, f.reviewer);
    await open(page, id, '工程交付', '发运签收');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('已放行 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('可放行率 100%', { exact: true })).toBeVisible();
    await shot(page, 'e04ps-3-released.png');
    await shotPanel(page, 'e04ps-3-released-panel.png');
    pr = (await api(page, 'GET', base(id) + '/delivery')).preship_readiness;
    expect(pr).toMatchObject({ total: 1, ready: 1, blocked: 0, released: 1, 'readiness-pct': 100 });

    // 只读汇总不改变逐条发货单状态 (SHIP-1 仍 released, 其 preship_checklist 4 项全 satisfied).
    const rows = (await api(page, 'GET', base(id) + '/delivery')).shipments;
    const row = rows.find(x => x.id === shipment.id);
    expect(row.status).toBe('released');
    expect(row.preship_checklist).toHaveLength(4);
    expect(row.preship_checklist.every(c => c.satisfied)).toBe(true);

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
