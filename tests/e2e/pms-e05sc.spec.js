const { test, expect } = require('@playwright/test');
const path = require('node:path');

// E05 发运放行与签收闭环只读汇总 (只读派生): 工程交付 -> 发运签收 页签内"发运签收闭环汇总"面板,
// 按发运生命周期 (草稿/放行审批/放行/发运/签收/条件接收/拒收退回) 聚合项目全部发货单的放行进度与签收闭环健康度
// (免迁移/无门控/无新命令/无新路由). 数据来自 delivery workspace 顶层 :shipment_closure;
// 逐条发货单的状态不因该汇总改变 (真正的放行/发运/签收门控仍由服务端 shipping-ready!/dispatch!/receipt! 在写入时强制).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/e05sc');
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
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '发运签收闭环汇总', exact: true }) }).first();

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '发运签收闭环汇总', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `发运签收审核${suffix}`, role_key: `pms_e05_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_e05_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立签收验证人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `E05-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `E05-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `E05-${suffix}-M1`, name: '主机#1' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, sub, machine, suffix };
}

async function toExecution(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '单机装配任务', owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22', node_id: f.machine.node_id });
  const evidence = await command('/governance/documents', { code: 'E05-EV', title: '发运签收闭环证据', filename: 'e05.txt', content: '合成装配/试验/入库/发运/签收记录.' });
  const requirement = await command('/governance/requirements', { code: 'E05-URS', text: '发运须独立放行与签收', category: '交付', priority: 'required', owner_id: f.adminId });
  const charter = await command('/governance/charters', { title: '发运章程', objective: '受控交付', scope: '合成系统', success_criteria: '签收闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  for (const stage of ['execution', 'closure']) {
    const template = await command('/governance/gate-templates', { code: `E05-${stage}`, title: `${stage}工程评审`, stage, required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
    const gate = await command('/governance/gates', { template_id: template.id, title: `${stage}准入检查`, reviewer_id: f.userId });
    await command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'C1', passed: true, evidence_ids: [evidence.id] }] });
    await command(`/governance/gates/${gate.id}/submit`, {});
    await command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验' }, f.reviewer);
  }
  await mutate(page, f.id, '/transition', { status: 'initiated', reason: 'E2E' });
  await mutate(page, f.id, '/transition', { status: 'planning', reason: 'E2E' });
  const baseline = await command('/planning/submit', { comment: '执行前置已批准计划' });
  await command(`/planning/baselines/${baseline.baseline_id}/review`, { decision: 'approved', comment: '独立批准' }, f.reviewer);
  await command('/delivery/configuration', { required_stages: ['materials', 'assembly', 'quality', 'shipment'], required_test_types: ['SIT', 'FAT', 'SAT'], pre_ship_conditions: ['warehouse_in', 'payment'], reason: 'E2E 发运签收闭环配置' });
  await mutate(page, f.id, '/transition', { status: 'execution', reason: '前置均已批准' });
  return { ...f, task, evidence, requirement, command };
}

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

// 装配 -> 开工 -> 独立交检批准; SIT/FAT 试验建立并独立批准; 返回已批准的 assembly (满足放行前置).
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

const statusCount = (sc, key) => sc['by-status'].find(x => x.key === key).count;

test.describe('E05 发运放行与签收闭环只读汇总', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 草稿 -> 已放行 -> 已签收, 面板与 :shipment_closure 一致且只读不门控', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f0 = await fixture(page, browser, '发运签收闭环汇总');
    const f = await toExecution(page, f0);
    const id = f.id;
    const command = f.command;
    const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };

    const bom = await kittedAssemblyBom(command, f, refs);
    const assembly = await approvedAssemblyWithTests(command, f, refs, bom);

    // 1. 尚无发货单 -> 面板空态; :shipment_closure.available=false, total/各状态计数 0.
    await open(page, id, '工程交付', '发运签收');
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无发货单, 质量试验合格后建立发运计划')).toBeVisible();
    await shot(page, 'e05sc-1-empty.png');
    await shotPanel(page, 'e05sc-1-empty-panel.png');
    let sc = (await api(page, 'GET', base(id) + '/delivery')).shipment_closure;
    expect(sc.available).toBe(false);
    expect(sc.total).toBe(0);
    expect(sc['closure-pct']).toBe(0);
    expect(sc['receipt-pct']).toBe(0);
    expect(sc['by-status'].map(x => x.key)).toEqual(['draft', 'in_review', 'rejected', 'released', 'shipped', 'received', 'conditional', 'returned']);
    expect(sc['by-status'].every(x => x.count === 0)).toBe(true);

    // 2. 建立发货单 (draft) -> 发货单 1 / 放行及以后 0 / 在途 0 / 已签收 0 / 闭环率 0%; 草稿 1.
    const shipment = await command('/delivery/shipments', { ...refs, code: 'SHIP-1', title: '设备发运', assembly_ids: [assembly.id], consignee: '现场接收团队', delivery_address: '客户指定地址', planned_date: '2026-10-05', reviewer_id: f.userId });
    await command(`/delivery/shipments/${shipment.id}/conditions`, { warehouse_in_confirmed: true, warehouse_note: 'WMS入库单 IN-001', payment_confirmed: true, payment_note: '财务确认提货款到账', evidence_ids: [f.evidence.id] });
    await open(page, id, '工程交付', '发运签收');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('发货单 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('放行及以后 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('在途 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已签收 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('签收闭环率 0%', { exact: true })).toBeVisible();
    await shot(page, 'e05sc-2-draft.png');
    await shotPanel(page, 'e05sc-2-draft-panel.png');
    sc = (await api(page, 'GET', base(id) + '/delivery')).shipment_closure;
    expect(sc).toMatchObject({ available: true, total: 1, draft: 1, 'released-or-beyond': 0, 'in-transit': 0, received: 0, 'closure-pct': 0, 'receipt-pct': 0 });
    expect(statusCount(sc, 'draft')).toBe(1);

    // 3. 提交放行并由独立审核人批准 -> released -> 放行及以后 1 / 在途 1 / 已签收 0 / 闭环率 0%.
    await command(`/delivery/shipments/${shipment.id}/submit`, { evidence_ids: [f.evidence.id] });
    await command(`/delivery/shipments/${shipment.id}/decision`, { decision: 'approved', reason: '独立放行' }, f.reviewer);
    await open(page, id, '工程交付', '发运签收');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('放行及以后 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('在途 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已签收 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('签收闭环率 0%', { exact: true })).toBeVisible();
    await shot(page, 'e05sc-3-released.png');
    await shotPanel(page, 'e05sc-3-released-panel.png');
    sc = (await api(page, 'GET', base(id) + '/delivery')).shipment_closure;
    expect(sc).toMatchObject({ total: 1, draft: 0, released: 1, 'released-or-beyond': 1, 'in-transit': 1, dispatched: 0, 'closure-pct': 0, 'receipt-pct': 0 });

    // 4. 实际发运 (admin, 非验证人; dispatch 拒签收验证人自行登记) -> shipped -> 在途 1 / 放行及以后 1.
    await command(`/delivery/shipments/${shipment.id}/dispatch`, { shipped_on: daysAgo(2), tracking_no: 'E05-TRACK', evidence_ids: [f.evidence.id] });
    await open(page, id, '工程交付', '发运签收');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('在途 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已签收 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('签收闭环率 0%', { exact: true })).toBeVisible();
    await shot(page, 'e05sc-4-shipped.png');
    await shotPanel(page, 'e05sc-4-shipped-panel.png');
    sc = (await api(page, 'GET', base(id) + '/delivery')).shipment_closure;
    expect(sc).toMatchObject({ shipped: 1, 'released-or-beyond': 1, dispatched: 1, 'in-transit': 1, received: 0, 'closure-pct': 0 });

    // 只读汇总不门控: 发运后发货单状态=shipped; 非指定审核人 (admin) 尝试签收 -> 403 (只有指定审核人可以作出决定), 状态不变.
    await mutate(page, id, `/delivery/shipments/${shipment.id}/receipt`, { received_on: daysAgo(1), receiver_name: '非法签收', acceptance: 'accepted', evidence_ids: [f.evidence.id] }, 403);
    expect((await api(page, 'GET', base(id) + '/delivery')).shipments.find(x => x.id === shipment.id).status).toBe('shipped');

    // 5. 独立验证人签收 accepted -> received; 闭环率 100% / 放行后签收率 100% / 在途 0.
    await command(`/delivery/shipments/${shipment.id}/receipt`, { received_on: daysAgo(1), receiver_name: '现场接收人', acceptance: 'accepted', evidence_ids: [f.evidence.id] }, f.reviewer);
    await open(page, id, '工程交付', '发运签收');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('发货单 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('放行及以后 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('在途 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已签收 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('签收异常 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('签收闭环率 100%', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('放行后签收率 100%', { exact: true })).toBeVisible();
    await shot(page, 'e05sc-5-received.png');
    await shotPanel(page, 'e05sc-5-received-panel.png');
    sc = (await api(page, 'GET', base(id) + '/delivery')).shipment_closure;
    expect(sc).toMatchObject({ total: 1, received: 1, 'released-or-beyond': 1, dispatched: 1, 'in-transit': 0, exception: 0, 'closure-pct': 100, 'receipt-pct': 100 });

    // 只读汇总不改变逐条发货单状态 (SHIP-1 = received).
    const row = (await api(page, 'GET', base(id) + '/delivery')).shipments.find(x => x.id === shipment.id);
    expect(row.status).toBe('received');

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
