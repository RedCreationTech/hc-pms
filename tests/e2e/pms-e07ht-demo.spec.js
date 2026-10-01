const { test, expect } = require('@playwright/test');

// E07 交底及时率只读汇总 — 完整功能演示录像 (真实浏览器, 带旁白字幕叠加).
// 展示: 配置 handover_required + 发运日+2 截止 -> 发货单A 发运(今天-10) 自动生成 overdue-open 交底 -> 面板"交底单1/待交底1/已逾期1"
//       -> 发货单B 发运(今天) 自动生成未逾期交底 -> "交底单2/待交底2/已逾期1" -> 完成A(今天-6, 晚于截止)逾期完成
//       -> 完成B(今天, 早于截止)按期完成 -> "已闭环2/按期完成1/逾期完成1/按期率50%".
// 只读派生不门控写操作, 真正的完成交底门控仍由服务端 complete-handover! 在写入时强制.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
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

async function caption(page, text) {
  await page.evaluate(t => {
    let el = document.getElementById('e07-demo-caption');
    if (!el) {
      el = document.createElement('div');
      el.id = 'e07-demo-caption';
      el.style.cssText = 'position:fixed;left:50%;bottom:28px;transform:translateX(-50%);z-index:99999;'
        + 'max-width:82%;padding:14px 26px;border-radius:12px;background:rgba(17,24,39,0.92);color:#fff;'
        + 'font-size:20px;line-height:1.5;font-weight:600;box-shadow:0 8px 30px rgba(0,0,0,0.35);text-align:center;'
        + 'font-family:"PingFang SC","Microsoft YaHei",sans-serif;pointer-events:none;';
      document.body.appendChild(el);
    }
    el.textContent = t;
  }, text);
}

const pause = (page, ms = 1400) => page.waitForTimeout(ms);
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '交底及时率汇总', exact: true }) }).first();

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `交底及时率审核${suffix}`, role_key: `pms_e07d_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_e07d_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立放行审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `E07D-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `E07D-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `E07D-${suffix}-M1`, name: '主机#1' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, sub, machine, suffix };
}

async function toExecution(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '单机装配任务', owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22', node_id: f.machine.node_id });
  const evidence = await command('/governance/documents', { code: 'E07-EV', title: '交底及时率证据', filename: 'e07.txt', content: '合成装配/试验/入库/发运/交底记录.' });
  const requirement = await command('/governance/requirements', { code: 'E07-URS', text: '发运须独立放行并按时交底', category: '交付', priority: 'required', owner_id: f.adminId });
  const charter = await command('/governance/charters', { title: '交底章程', objective: '受控交付', scope: '合成系统', success_criteria: '按时交底', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  for (const stage of ['execution', 'closure']) {
    const template = await command('/governance/gate-templates', { code: `E07-${stage}`, title: `${stage}工程评审`, stage, required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
    const gate = await command('/governance/gates', { template_id: template.id, title: `${stage}准入检查`, reviewer_id: f.userId });
    await command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'C1', passed: true, evidence_ids: [evidence.id] }] });
    await command(`/governance/gates/${gate.id}/submit`, {});
    await command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '独立核验' }, f.reviewer);
  }
  await mutate(page, f.id, '/transition', { status: 'initiated', reason: 'E2E' });
  await mutate(page, f.id, '/transition', { status: 'planning', reason: 'E2E' });
  const baseline = await command('/planning/submit', { comment: '执行前置已批准计划' });
  await command(`/planning/baselines/${baseline.baseline_id}/review`, { decision: 'approved', comment: '独立批准' }, f.reviewer);
  await command('/delivery/configuration', { required_stages: ['materials', 'assembly', 'quality', 'shipment'], required_test_types: ['SIT', 'FAT'], pre_ship_conditions: ['warehouse_in', 'payment'], handover_required: true, handover_deadline_days: 2, reason: 'E2E 交底及时率配置' });
  await mutate(page, f.id, '/transition', { status: 'execution', reason: '前置均已批准' });
  return { ...f, task, evidence, requirement, command };
}

async function kittedAssemblyBom(command, f, refs, pfx, kittingGate) {
  const ev = f.evidence.id;
  const material = await command('/delivery/material-requests', { ...refs, code: `MR-${pfx}`, title: '长周期件', request_type: 'long_lead', owner_id: f.adminId, needed_on: '2026-10-01', items: [{ code: `M-${pfx}1`, name: '执行器', quantity: 2, unit: '个' }, { code: `M-${pfx}2`, name: '连接线', quantity: 4, unit: '根' }] });
  await command(`/delivery/material-requests/${material.id}/submit`, { evidence_ids: [ev], reviewer_id: f.userId });
  await command(`/delivery/material-requests/${material.id}/decision`, { decision: 'approved', reason: '独立核查' }, f.reviewer);
  const bom = await command('/delivery/boms', { code: `BOM-${pfx}`, title: '受控配置', material_request_id: material.id });
  await command(`/delivery/boms/${bom.id}/freeze`, { evidence_ids: [ev], reviewer_id: f.userId });
  await command(`/delivery/boms/${bom.id}/decision`, { decision: 'approved', reason: '冻结确认' }, f.reviewer);
  await command(`/delivery/boms/${bom.id}/kit`, { items: [{ code: `M-${pfx}1`, available_quantity: 2 }, { code: `M-${pfx}2`, available_quantity: 4 }], evidence_ids: [ev] });
  if (kittingGate) {
    const kitting = await command('/governance/gate-templates/from-catalog', { gate_type: 'kitting' });
    const gate = await command('/governance/gates', { template_id: kitting.id, title: '齐套放行', reviewer_id: f.userId });
    await command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'KIT-1', passed: true, evidence_ids: [ev] }, { code: 'KIT-2', passed: true, evidence_ids: [ev] }] });
    await command(`/governance/gates/${gate.id}/submit`, {});
    await command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '齐套确认' }, f.reviewer);
  }
  return bom;
}

async function approvedAssembly(command, f, refs, bom, pfx) {
  const ev = f.evidence.id;
  const assembly = await command('/delivery/assemblies', { ...refs, code: `ASS-${pfx}`, title: `主机装配 ${pfx}`, bom_id: bom.id, owner_id: f.adminId });
  await command(`/delivery/assemblies/${assembly.id}/start`, { evidence_ids: [ev] });
  await command(`/delivery/assemblies/${assembly.id}/submit`, { evidence_ids: [ev], reviewer_id: f.userId });
  await command(`/delivery/assemblies/${assembly.id}/decision`, { decision: 'approved', reason: '交检通过' }, f.reviewer);
  for (const type of ['SIT', 'FAT']) {
    const test = await command('/delivery/tests', { ...refs, code: `${type}-${pfx}`, title: `${type}验证 ${pfx}`, assembly_id: assembly.id, test_type: type, owner_id: f.adminId, criteria: [{ code: 'Q-1', title: '动作满足URS', required: true }] });
    await command(`/delivery/tests/${test.id}/results`, { checks: [{ code: 'Q-1', passed: true, actual: '通过', evidence_ids: [ev] }], due_date: '2026-10-03' });
    await command(`/delivery/tests/${test.id}/submit`, { evidence_ids: [ev], reviewer_id: f.userId });
    await command(`/delivery/tests/${test.id}/decision`, { decision: 'approved', reason: '试验合格' }, f.reviewer);
  }
  return assembly;
}

async function releasedShipment(command, f, refs, pfx, kittingGate) {
  const bom = await kittedAssemblyBom(command, f, refs, pfx, kittingGate);
  const assembly = await approvedAssembly(command, f, refs, bom, pfx);
  const shipment = await command('/delivery/shipments', { ...refs, code: `SHIP-${pfx}`, title: `设备发运 ${pfx}`, assembly_ids: [assembly.id], consignee: '现场接收团队', delivery_address: '客户指定地址', planned_date: '2026-10-05', reviewer_id: f.userId });
  await command(`/delivery/shipments/${shipment.id}/conditions`, { warehouse_in_confirmed: true, warehouse_note: `WMS入库单 IN-${pfx}`, payment_confirmed: true, payment_note: '财务确认提货款到账', evidence_ids: [f.evidence.id] });
  await command(`/delivery/shipments/${shipment.id}/submit`, { evidence_ids: [f.evidence.id] });
  await command(`/delivery/shipments/${shipment.id}/decision`, { decision: 'approved', reason: '独立放行' }, f.reviewer);
  return shipment;
}

test.use({
  viewport: { width: 1600, height: 1000 },
  video: { mode: 'on', size: { width: 1600, height: 1000 } },
});

test.describe('E07 交底及时率只读汇总 — 完整功能演示', () => {
  test.setTimeout(300000);

  test('逾期在办 -> 追加未逾期 -> 逾期完成 + 按期完成, 面板全流程刷新', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    await caption(page, 'E07 交底及时率只读汇总 · 真实浏览器功能演示');
    await pause(page, 2000);

    await caption(page, '步骤 1/6 · 推进到执行: 配置 handover_required, 截止期 = 发运日 + 2 天 + 齐套Gate + SIT/FAT 合格');
    const f0 = await fixture(page, browser, '交底及时率演示');
    const f = await toExecution(page, f0);
    const id = f.id;
    const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };
    await pause(page, 600);

    await caption(page, '步骤 2/6 · 打开 工程交付 › 工勘与现场, 尚未发运 -> 面板显示引导空态');
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无交底任务, 发运登记后自动生成 (截止期 = 发运日 + 配置天数)')).toBeVisible();
    await pause(page, 2600);

    await caption(page, '步骤 3/6 · 发货单A 放行后发运(今天-10, 截止=今天-8 已过) -> 交底自动 open 且逾期: 交底单1 / 待交底1 / 已逾期1 / 按期率 0%');
    const shipmentA = await releasedShipment(f.command, f, refs, 'A', true);
    await f.command(`/delivery/shipments/${shipmentA.id}/dispatch`, { shipped_on: daysAgo(10), tracking_no: 'E07-A', evidence_ids: [f.evidence.id] });
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('交底单 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('待交底 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已逾期 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('按期率 0%', { exact: true })).toBeVisible();
    await pause(page, 3000);

    await caption(page, '步骤 4/6 · 发货单B 发运(今天, 截止=今天+2 未过) -> 第二条交底未逾期: 交底单2 / 待交底2 / 已逾期1');
    const shipmentB = await releasedShipment(f.command, f, refs, 'B', false);
    await f.command(`/delivery/shipments/${shipmentB.id}/dispatch`, { shipped_on: daysAgo(0), tracking_no: 'E07-B', evidence_ids: [f.evidence.id] });
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('交底单 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('待交底 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已逾期 1', { exact: true })).toBeVisible();
    await pause(page, 3000);

    const dl1 = (await api(page, 'GET', base(id) + '/delivery'));
    const handoverA = dl1.handovers.find(x => x.shipment_id === shipmentA.id);
    const handoverB = dl1.handovers.find(x => x.shipment_id === shipmentB.id);

    await caption(page, '步骤 5/6 · 只读汇总不门控完成交底 (仍由服务端校验日期不倒退/不超今天/open 态); 完成逾期A(今天-6, 晚于截止) -> 逾期完成');
    await f.command(`/delivery/handovers/${handoverA.id}/complete`, { document_ids: [f.evidence.id], checklist_note: '交底清单 A (逾期)', completed_on: daysAgo(6) });
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('逾期完成 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已闭环 1', { exact: true })).toBeVisible();
    await pause(page, 2600);

    await caption(page, '步骤 6/6 · 完成按期B(今天, 早于截止) -> 面板刷新: 已闭环2 / 按期完成1 / 逾期完成1 / 按期率 50%');
    await f.command(`/delivery/handovers/${handoverB.id}/complete`, { document_ids: [f.evidence.id], checklist_note: '交底清单 B (按期)', completed_on: daysAgo(0) });
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('待交底 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已闭环 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('按期完成 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('逾期完成 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('按期率 50%', { exact: true })).toBeVisible();
    await pause(page, 3000);

    const ht = (await api(page, 'GET', base(id) + '/delivery')).handover_timeliness;
    expect(ht).toMatchObject({ total: 2, open: 0, closed: 2, 'overdue-open': 0, 'completed-on-time': 1, 'completed-late': 1, 'on-time-pct': 50 });
    expect(ht['nearest-deadline']).toBeNull();

    await caption(page, '演示结束 · E07 交底及时率只读汇总 (免迁移 / 无门控 / 无新命令 / 无新路由)');
    await pause(page, 2200);

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
