const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H11 售后遗留项闭环只读汇总 (只读派生): 工程交付 -> 售后闭环 页签内"售后遗留项闭环汇总"面板,
// 按售后生命周期 (待处理/解决审核中/已关闭/已驳回) 聚合项目全部售后异常的期限达成与闭环健康度,
// 含逾期未闭环预警, 来源分解 (条件接收自动生成 vs 手工登记) 与最近处理期限, 以及逐条台账的"处理时限"派生列.
// 数据来自 delivery workspace 顶层 :service_closure 与逐条 :service_cases 只读派生键 (免迁移/无门控/无新命令/无新路由).
// 逐条售后状态不因该汇总改变 (解决须证据并独立关闭仍由服务端 resolve-service!/decide-service! 在写入时强制).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h11sc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const base = id => `/api/pms/projects/${id}`;
const localDate = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const daysAgo = n => { const d = new Date(); d.setDate(d.getDate() - n); return localDate(d); };
const daysAhead = n => { const d = new Date(); d.setDate(d.getDate() + n); return localDate(d); };

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
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '售后遗留项闭环汇总', exact: true }) }).first();

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '售后遗留项闭环汇总', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `售后闭环审核${suffix}`, role_key: `pms_h11_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_h11_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立售后验证人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H11-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `H11-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `H11-${suffix}-M1`, name: '主机#1' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, sub, machine, suffix };
}

async function toExecution(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '单机装配任务', owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22', node_id: f.machine.node_id });
  const evidence = await command('/governance/documents', { code: 'H11-EV', title: '售后闭环证据', filename: 'h11.txt', content: '合成装配/试验/入库/发运/签收/售后记录.' });
  const requirement = await command('/governance/requirements', { code: 'H11-URS', text: '售后遗留项责任/期限/独立关闭可见', category: '交付', priority: 'required', owner_id: f.adminId });
  const charter = await command('/governance/charters', { title: '售后章程', objective: '受控交付', scope: '合成系统', success_criteria: '售后闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  for (const stage of ['execution', 'closure']) {
    const template = await command('/governance/gate-templates', { code: `H11-${stage}`, title: `${stage}工程评审`, stage, required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
    const gate = await command('/governance/gates', { template_id: template.id, title: `${stage}准入检查`, reviewer_id: f.userId });
    await command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'C1', passed: true, evidence_ids: [evidence.id] }] });
    await command(`/governance/gates/${gate.id}/submit`, {});
    await command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验' }, f.reviewer);
  }
  await mutate(page, f.id, '/transition', { status: 'initiated', reason: 'E2E' });
  await mutate(page, f.id, '/transition', { status: 'planning', reason: 'E2E' });
  const baseline = await command('/planning/submit', { comment: '执行前置已批准计划' });
  await command(`/planning/baselines/${baseline.baseline_id}/review`, { decision: 'approved', comment: '独立批准' }, f.reviewer);
  await command('/delivery/configuration', { required_stages: ['materials', 'assembly', 'quality', 'shipment'], required_test_types: ['SIT', 'FAT', 'SAT'], pre_ship_conditions: ['warehouse_in', 'payment'], reason: 'E2E 售后闭环配置' });
  await mutate(page, f.id, '/transition', { status: 'execution', reason: '前置均已批准' });
  return { ...f, task, evidence, requirement, command };
}

// 冻结BOM -> 齐套100% -> 齐套Gate, 返回 bom.
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

// 装配 -> 开工 -> 独立交检批准; SIT/FAT 试验建立并独立批准; 返回已批准的 assembly.
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

// 发货单 -> 放行 -> 独立批准 -> 实际发运 (shipped), 满足 create-service! 前置.
async function shippedShipment(command, f, refs, assembly) {
  const shipment = await command('/delivery/shipments', { ...refs, code: 'SHIP-1', title: '设备发运', assembly_ids: [assembly.id], consignee: '现场接收团队', delivery_address: '客户指定地址', planned_date: '2026-10-05', reviewer_id: f.userId });
  await command(`/delivery/shipments/${shipment.id}/conditions`, { warehouse_in_confirmed: true, warehouse_note: 'WMS入库单 IN-001', payment_confirmed: true, payment_note: '财务确认提货款到账', evidence_ids: [f.evidence.id] });
  await command(`/delivery/shipments/${shipment.id}/submit`, { evidence_ids: [f.evidence.id] });
  await command(`/delivery/shipments/${shipment.id}/decision`, { decision: 'approved', reason: '独立放行' }, f.reviewer);
  await command(`/delivery/shipments/${shipment.id}/dispatch`, { shipped_on: daysAgo(2), tracking_no: 'H11-TRACK', evidence_ids: [f.evidence.id] });
  return shipment;
}

const statusCount = (sc, key) => sc['by-status'].find(x => x.key === key).count;

test.describe('H11 售后遗留项闭环只读汇总', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 待处理/逾期 -> 解决审核中 -> 独立关闭, 面板与 :service_closure 一致且只读不门控', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f0 = await fixture(page, browser, '售后遗留项闭环汇总');
    const f = await toExecution(page, f0);
    const id = f.id;
    const command = f.command;
    const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };

    const bom = await kittedAssemblyBom(command, f, refs);
    const assembly = await approvedAssemblyWithTests(command, f, refs, bom);
    const shipment = await shippedShipment(command, f, refs, assembly);

    // 1. 尚无售后异常 -> 面板空态; :service_closure.available=false, total/各状态计数 0, by-status 键序固定.
    await open(page, id, '工程交付', '售后闭环');
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无售后异常, 条件接收或拒收时自动生成, 也可在发运后手工登记')).toBeVisible();
    await shot(page, 'h11sc-1-empty.png');
    await shotPanel(page, 'h11sc-1-empty-panel.png');
    let sc = (await api(page, 'GET', base(id) + '/delivery')).service_closure;
    expect(sc.available).toBe(false);
    expect(sc.total).toBe(0);
    expect(sc['closure-pct']).toBe(0);
    expect(sc['nearest-due']).toBeNull();
    expect(sc['by-status'].map(x => x.key)).toEqual(['open', 'in_review', 'closed', 'rejected']);
    expect(sc['by-status'].every(x => x.count === 0)).toBe(true);

    // 2. 登记两条手工售后异常: SE-1 处理期限已过 (逾期), SE-2 十天后到期 (在期).
    const dueOverdue = daysAgo(3);
    const dueFuture = daysAhead(10);
    const svc1 = await command('/delivery/service-cases', { ...refs, code: 'SE-1', title: '运输附件需更换', shipment_id: shipment.id, owner_id: f.adminId, due_date: dueOverdue });
    const svc2 = await command('/delivery/service-cases', { ...refs, code: 'SE-2', title: '控制软件补丁', shipment_id: shipment.id, owner_id: f.adminId, due_date: dueFuture });
    expect(svc1.status).toBe('open');
    expect(svc1.source).toBe('manual_record');

    await open(page, id, '工程交付', '售后闭环');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('售后项 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('待处理 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('解决审核中 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已关闭 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已驳回 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('逾期未闭环 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('闭环率 0%', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('手工登记 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('条件接收转售后 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText(`最近期限 ${dueOverdue}`, { exact: true })).toBeVisible();
    // 逐条台账"处理时限"派生列: SE-1 红标逾期 3 天, SE-2 金标剩 10 天.
    await expect(drawer(page).getByText('已逾期 3 天', { exact: true }).first()).toBeVisible();
    await expect(drawer(page).getByText('剩 10 天', { exact: true }).first()).toBeVisible();
    await shot(page, 'h11sc-2-open.png');
    await shotPanel(page, 'h11sc-2-open-panel.png');
    sc = (await api(page, 'GET', base(id) + '/delivery')).service_closure;
    expect(sc).toMatchObject({ available: true, total: 2, open: 2, 'in-review': 0, closed: 0, rejected: 0, unresolved: 2, 'overdue-unresolved': 1, 'from-manual': 2, 'from-receipt': 0, 'closure-pct': 0, 'nearest-due': dueOverdue });
    expect(statusCount(sc, 'open')).toBe(2);
    // 派生键落在逐条 service_cases 记录上 (SE-1 逾期天数 -3).
    const rows = (await api(page, 'GET', base(id) + '/delivery')).service_cases;
    const row1 = rows.find(x => x.id === svc1.id);
    expect(row1.service_overdue).toBe(true);
    expect(row1.service_days_left).toBe(-3);
    const row2 = rows.find(x => x.id === svc2.id);
    expect(row2.service_overdue).toBe(false);
    expect(row2.service_days_left).toBe(10);

    // 只读汇总不改变逐条记录; 但服务端状态门控仍强制: open 记录尚未提交解决方案, 不能直接作出决定 -> 409 (当前状态不允许此操作), 状态不变.
    await mutate(page, id, `/delivery/service-cases/${svc1.id}/decision`, { decision: 'approved', reason: '跳过提交直接关闭' }, 409);
    expect((await api(page, 'GET', base(id) + '/delivery')).service_cases.find(x => x.id === svc1.id).status).toBe('open');

    // 3. 提交解决方案并指定独立验证人 -> in_review; 仍未闭环, 逾期项仍计 SE-1.
    await command(`/delivery/service-cases/${svc1.id}/resolve`, { resolution: '更换附件并现场确认', reviewer_id: f.userId, evidence_ids: [f.evidence.id] });
    await open(page, id, '工程交付', '售后闭环');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('待处理 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('解决审核中 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已关闭 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('逾期未闭环 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('闭环率 0%', { exact: true })).toBeVisible();
    await shot(page, 'h11sc-3-inreview.png');
    await shotPanel(page, 'h11sc-3-inreview-panel.png');
    sc = (await api(page, 'GET', base(id) + '/delivery')).service_closure;
    expect(sc).toMatchObject({ open: 1, 'in-review': 1, closed: 0, unresolved: 2, 'overdue-unresolved': 1, 'closure-pct': 0 });

    // 只读汇总不门控, 但服务端"指定独立验证人"门控仍强制: 提交后 in_review; 非指定验证人 (登记人 admin) 关闭 -> 403 (只有指定审核人可以作出决定), 状态仍 in_review.
    await mutate(page, id, `/delivery/service-cases/${svc1.id}/decision`, { decision: 'approved', reason: '登记人自行关闭' }, 403);
    expect((await api(page, 'GET', base(id) + '/delivery')).service_cases.find(x => x.id === svc1.id).status).toBe('in_review');

    // 4. 指定独立验证人 (非登记人) 关闭 SE-1 -> closed; 闭环率 50%, 逾期未闭环归零, 最近期限顺延到 SE-2.
    await command(`/delivery/service-cases/${svc1.id}/decision`, { decision: 'approved', reason: '现场独立验证' }, f.reviewer);
    await open(page, id, '工程交付', '售后闭环');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('售后项 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('待处理 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已关闭 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('逾期未闭环 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('闭环率 50%', { exact: true })).toBeVisible();
    await expect(panel(page).getByText(`最近期限 ${dueFuture}`, { exact: true })).toBeVisible();
    // SE-1 关闭后"处理时限"列不再有逾期红标 (无剩余天数 -> 破折号).
    await expect(drawer(page).getByText('已逾期 3 天', { exact: true })).toHaveCount(0);
    await shot(page, 'h11sc-4-closed.png');
    await shotPanel(page, 'h11sc-4-closed-panel.png');
    sc = (await api(page, 'GET', base(id) + '/delivery')).service_closure;
    expect(sc).toMatchObject({ total: 2, open: 1, 'in-review': 0, closed: 1, rejected: 0, unresolved: 1, 'overdue-unresolved': 0, 'from-manual': 2, 'from-receipt': 0, 'closure-pct': 50, 'nearest-due': dueFuture });
    expect(statusCount(sc, 'open')).toBe(1);
    expect(statusCount(sc, 'closed')).toBe(1);

    // 只读汇总不改变逐条售后状态: SE-1 = closed, SE-2 仍 open.
    const finalRows = (await api(page, 'GET', base(id) + '/delivery')).service_cases;
    expect(finalRows.find(x => x.id === svc1.id).status).toBe('closed');
    expect(finalRows.find(x => x.id === svc1.id).service_days_left).toBeNull();
    expect(finalRows.find(x => x.id === svc2.id).status).toBe('open');

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
