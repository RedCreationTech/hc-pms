const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H11 延伸: 售后遗留项处理时限只读派生 (免迁移, 无新命令/新kind/新路由).
// "工程交付 -> 售后闭环" 在"售后遗留项闭环汇总"之后新增只读"售后遗留项处理时限"面板:
// 按每条售后异常的登记日期到今天算已历时, 按登记日到承诺期限算允许天数, 未关闭项另给已逾期天数;
// 再汇总 平均已历时/未关闭平均已历时/平均允许天数/未关闭逾期数, 以及承诺期限分段 (<=7 天/8 至 30 天/超 30 天/未设期限).
// 与闭环汇总互补: 后者看关闭进度, 本项看时间是否被拖住.
// 造 SE-1 (期限已过 3 天) 与 SE-2 (10 天后到期) -> 面板 售后项2/未关闭2/未关闭逾期1/未设期限2 (两条都由今天登记, 允许天数各自成立);
// 关闭 SE-1 后未关闭回落 1, 平均已历时与分段随之变化. 空态单独截图.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h11tt');
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

const TITLE = '售后遗留项处理时限';
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: TITLE, exact: true }) }).first();

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: TITLE, exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `售后时限核查${suffix}`, role_key: `pms_h11t_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_h11t_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立售后核查人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H11T-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `H11T-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `H11T-${suffix}-M1`, name: '主机#1' });
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


test.describe('H11 售后遗留项处理时限只读派生', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('处理时限面板按已历时与承诺期限分段, 关闭后未关闭数回落, 服务端 :service_turnaround 同源', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f0 = await fixture(page, browser, '售后遗留项处理时限');
    const f = await toExecution(page, f0);
    const id = f.id;
    const command = f.command;
    const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };

    const bom = await kittedAssemblyBom(command, f, refs);
    const assembly = await approvedAssemblyWithTests(command, f, refs, bom);
    const shipment = await shippedShipment(command, f, refs, assembly);

    // 1. 尚无售后异常 -> 面板空态; :service_turnaround.available=false.
    await open(page, id, '工程交付', '售后闭环');
    await expect(panel(page).getByText('尚无售后异常, 条件接收或拒收时自动生成, 也可在发运后手工登记')).toBeVisible();
    await shotPanel(page, 'h11tt-1-empty.png');
    let tt = (await api(page, 'GET', base(id) + '/delivery')).service_turnaround;
    expect(tt.available).toBe(false);
    expect(tt.total).toBe(0);
    expect(tt['avg-days-open']).toBe(0);

    // 2. 登记两条手工售后异常: 一条期限已过 3 天, 一条 10 天后到期 (当天登记, 故已历时均为 0).
    const svc1 = await command('/delivery/service-cases', { ...refs, code: 'SE-1', title: '运输附件需更换', shipment_id: shipment.id, owner_id: f.adminId, due_date: daysAgo(3) });
    await command('/delivery/service-cases', { ...refs, code: 'SE-2', title: '控制软件补丁', shipment_id: shipment.id, owner_id: f.adminId, due_date: daysAhead(10) });
    expect(svc1.status).toBe('open');

    await open(page, id, '工程交付', '售后闭环');
    const p1 = panel(page);
    await expect(p1.getByText('售后项 2', { exact: true })).toBeVisible();
    await expect(p1.getByText('未关闭 2', { exact: true })).toBeVisible();
    await expect(p1.getByText('未关闭逾期 1', { exact: true })).toBeVisible();
    await expect(p1.getByText('平均已历时 0 天', { exact: true })).toBeVisible();
    await expect(p1.getByText('未关闭平均已历时 0 天', { exact: true })).toBeVisible();
    // 允许天数 -3 与 10 -> 平均 4 天 (四舍五入: ( -3 + 10 ) / 2 = 3.5 -> 4); 分段 <=7 一条(负值/小), 8-30 一条.
    await expect(p1.getByText('平均允许 4 天', { exact: true })).toBeVisible();
    await expect(p1.getByText('7 天内 1', { exact: true })).toBeVisible();
    await expect(p1.getByText('8 至 30 天 1', { exact: true })).toBeVisible();
    await expect(p1.getByText('超 30 天 0', { exact: true })).toBeVisible();
    await expect(p1.getByText('未设期限 0', { exact: true })).toBeVisible();
    await shot(page, 'h11tt-2-open.png');
    await shotPanel(page, 'h11tt-2-open-panel.png');
    tt = (await api(page, 'GET', base(id) + '/delivery')).service_turnaround;
    expect(tt).toMatchObject({ available: true, total: 2, unresolved: 2, 'overdue-unresolved': 1, 'short-bucket': 1, 'medium-bucket': 1, 'long-bucket': 0, 'unspecified-bucket': 0, 'avg-slack-days': 4 });

    // 3. 提交解决方案并由独立核查人关闭 SE-1 -> 未关闭回落 1, 逾期归零, 分段随状态不再影响 (段位按允许天数, 不变).
    await command(`/delivery/service-cases/${svc1.id}/resolve`, { resolution: '更换附件并现场确认', reviewer_id: f.userId, evidence_ids: [f.evidence.id] });
    await command(`/delivery/service-cases/${svc1.id}/decision`, { decision: 'approved', reason: '现场独立验证' }, f.reviewer);
    await open(page, id, '工程交付', '售后闭环');
    const p2 = panel(page);
    await expect(p2.getByText('售后项 2', { exact: true })).toBeVisible();
    await expect(p2.getByText('未关闭 1', { exact: true })).toBeVisible();
    await expect(p2.getByText('未关闭逾期 0', { exact: true })).toBeVisible();
    await shotPanel(page, 'h11tt-3-closed-panel.png');
    tt = (await api(page, 'GET', base(id) + '/delivery')).service_turnaround;
    expect(tt.unresolved).toBe(1);
    expect(tt['overdue-unresolved']).toBe(0);
    expect(tt.total).toBe(2);
    // 闭环汇总不受影响, 证明两块面板同源不同维度.
    expect((await api(page, 'GET', base(id) + '/delivery')).service_closure['closure-pct']).toBe(50);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
