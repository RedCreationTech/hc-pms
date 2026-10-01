const { test, expect } = require('@playwright/test');
const path = require('node:path');

// E05 发运放行与签收闭环只读汇总 — 完整功能演示录像 (真实浏览器, 带旁白字幕叠加).
// 展示: 配置发货前条件+冻结BOM+齐套Gate+已批准装配+SIT/FAT -> 建发运单(草稿) -> 面板"放行及以后 0/闭环率 0%"
//       -> 提交放行+独立批准(released) -> "放行及以后 1/在途 1" -> 实际发运(shipped) -> 独立签收(received) -> "闭环率 100%/放行后签收率 100%".
// 只读派生不门控写操作, 真正门控仍由服务端 shipping-ready!/dispatch!/receipt! 在写入时强制.
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
    let el = document.getElementById('e05-demo-caption');
    if (!el) {
      el = document.createElement('div');
      el.id = 'e05-demo-caption';
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
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '发运签收闭环汇总', exact: true }) }).first();

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `发运签收审核${suffix}`, role_key: `pms_e05d_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_e05d_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立签收验证人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `E05D-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `E05D-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `E05D-${suffix}-M1`, name: '主机#1' });
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

async function buildKittedApprovedAssembly(command, f) {
  const bom = await buildKittedAssemblyBomOnly(command, f);
  const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };
  return approveAssemblyAndTests(command, f, refs, bom);
}

test.use({
  viewport: { width: 1600, height: 1000 },
  video: { mode: 'on', size: { width: 1600, height: 1000 } },
});

test.describe('E05 发运放行与签收闭环只读汇总 — 完整功能演示', () => {
  test.setTimeout(300000);

  test('草稿 -> 独立放行 -> 实际发运 -> 独立签收, 面板全流程闭环', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    await caption(page, 'E05 发运放行与签收闭环只读汇总 · 真实浏览器功能演示');
    await pause(page, 2000);

    await caption(page, '步骤 1/6 · 推进到执行: 配置条件 + 冻结BOM + 齐套Gate + 已批准装配 + SIT/FAT 合格');
    const f0 = await fixture(page, browser, '发运签收闭环演示');
    const f = await toExecution(page, f0);
    const id = f.id;
    const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };
    const bom = await buildKittedAssemblyBomOnly(f.command, f);
    const assembly = await approveAssemblyAndTests(f.command, f, refs, bom);
    await pause(page, 600);

    await caption(page, '步骤 2/6 · 打开 工程交付 › 发运签收, 尚未建立发货单 -> 面板显示引导空态');
    await open(page, id, '工程交付', '发运签收');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无发货单, 质量试验合格后建立发运计划')).toBeVisible();
    await pause(page, 2600);

    await caption(page, '步骤 3/6 · 建立发货单 (草稿) -> 面板: 发货单 1 / 放行及以后 0 / 签收闭环率 0%, 状态分布"草稿 1"');
    const shipment = await f.command('/delivery/shipments', { ...refs, code: 'SHIP-1', title: '设备发运', assembly_ids: [assembly.id], consignee: '现场接收团队', delivery_address: '客户指定地址', planned_date: '2026-10-05', reviewer_id: f.userId });
    await f.command(`/delivery/shipments/${shipment.id}/conditions`, { warehouse_in_confirmed: true, warehouse_note: 'WMS入库单 IN-001', payment_confirmed: true, payment_note: '财务确认提货款到账', evidence_ids: [f.evidence.id] });
    await open(page, id, '工程交付', '发运签收');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('发货单 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('放行及以后 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('签收闭环率 0%', { exact: true })).toBeVisible();
    await pause(page, 3000);

    await caption(page, '步骤 4/6 · 提交放行, 独立审核人批准 -> released -> 放行及以后 1 / 在途 1');
    await f.command(`/delivery/shipments/${shipment.id}/submit`, { evidence_ids: [f.evidence.id] });
    await f.command(`/delivery/shipments/${shipment.id}/decision`, { decision: 'approved', reason: '独立放行' }, f.reviewer);
    await open(page, id, '工程交付', '发运签收');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('放行及以后 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('在途 1', { exact: true })).toBeVisible();
    await pause(page, 3000);

    await caption(page, '步骤 5/6 · 实际发运登记 (发运人非签收验证人) -> shipped; 非指定审核人签收被服务端 403 拦截');
    await f.command(`/delivery/shipments/${shipment.id}/dispatch`, { shipped_on: daysAgo(2), tracking_no: 'E05-TRACK', evidence_ids: [f.evidence.id] });
    await mutate(page, id, `/delivery/shipments/${shipment.id}/receipt`, { received_on: daysAgo(1), receiver_name: '非法签收', acceptance: 'accepted', evidence_ids: [f.evidence.id] }, 403);
    await open(page, id, '工程交付', '发运签收');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('在途 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已签收 0', { exact: true })).toBeVisible();
    await pause(page, 2600);

    await caption(page, '步骤 6/6 · 独立验证人签收 accepted -> received -> 只读汇总实时刷新: 已签收 1 / 在途 0 / 签收闭环率 100% / 放行后签收率 100%');
    await f.command(`/delivery/shipments/${shipment.id}/receipt`, { received_on: daysAgo(1), receiver_name: '现场接收人', acceptance: 'accepted', evidence_ids: [f.evidence.id] }, f.reviewer);
    await open(page, id, '工程交付', '发运签收');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('已签收 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('签收闭环率 100%', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('放行后签收率 100%', { exact: true })).toBeVisible();
    await pause(page, 2600);

    const sc = (await api(page, 'GET', base(id) + '/delivery')).shipment_closure;
    expect(sc).toMatchObject({ total: 1, received: 1, 'released-or-beyond': 1, dispatched: 1, 'in-transit': 0, exception: 0, 'closure-pct': 100, 'receipt-pct': 100 });

    await caption(page, '演示结束 · E05 发运放行与签收闭环只读汇总 (免迁移 / 无门控 / 无新命令 / 无新路由)');
    await pause(page, 2200);

    await f.context.close();
    expect(errors).toEqual([]);
  });
});

// 拆分建 BOM 与 装配批准 两个辅助, 便于演示中一次调用完成前置 (与主 spec 同逻辑).
async function buildKittedAssemblyBomOnly(command, f) {
  const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };
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

async function approveAssemblyAndTests(command, f, refs, bom) {
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
