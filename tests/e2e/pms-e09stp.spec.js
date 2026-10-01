const { test, expect } = require('@playwright/test');
const path = require('node:path');

// E09 现场任务进度只读汇总 (只读派生): 工程交付 -> 工勘与现场 页签内"现场任务进度汇总"面板,
// 交底完成后自动生成的定位/安装/调试/SAT现场任务序列, 整体闭环健康度只读聚合 (免迁移/无门控/无新命令/无新路由).
// 数据来自 delivery workspace 顶层 :site_task_progress; 逐条现场任务状态不因该汇总改变.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/e09stp');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
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
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '现场任务进度汇总', exact: true }) }).first();

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '现场任务进度汇总', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `现场审批${suffix}`, role_key: `pms_e09_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_e09_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立现场审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `E09-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `E09-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `E09-${suffix}-M1`, name: '主机#1' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, sub, machine, suffix };
}

async function toExecution(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '单机交付任务', owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22', node_id: f.machine.node_id });
  const evidence = await command('/governance/documents', { code: 'E09-EV', title: '现场闭环证据', filename: 'e09.txt', content: '合成现场记录.' });
  const requirement = await command('/governance/requirements', { code: 'E09-URS', text: '现场交付须验证', category: '交付', priority: 'required', owner_id: f.adminId });
  const charter = await command('/governance/charters', { title: '现场章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  for (const stage of ['execution', 'closure']) {
    const template = await command('/governance/gate-templates', { code: `E09-${stage}`, title: `${stage}工程评审`, stage, required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
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
  await f.command(`/delivery/${collection}/${item.id}/${action}`, { evidence_ids: [f.evidence.id], ...(collection === 'shipments' ? {} : { reviewer_id: f.userId }) });
  return f.command(`/delivery/${collection}/${item.id}/decision`, { decision: 'approved', reason: '独立核查' }, f.reviewer);
}

async function shipped(f, refs) {
  // 冻结BOM -> 齐套100% -> 齐套Gate -> 装配开工/交检 -> SIT/FAT -> 发运单 (未 dispatch).
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
  const assembly = await f.command('/delivery/assemblies', { ...refs, code: 'ASS-1', title: '主机装配', bom_id: bom.id, owner_id: f.adminId });
  await f.command(`/delivery/assemblies/${assembly.id}/start`, { evidence_ids: [f.evidence.id] });
  await approveDelivery(f, 'assemblies', assembly);
  for (const type of ['SIT', 'FAT']) {
    const t = await f.command('/delivery/tests', { ...refs, code: `${type}-1`, title: `${type}验证`, assembly_id: assembly.id, test_type: type, owner_id: f.adminId, criteria: [{ code: 'Q-1', title: '动作满足URS', required: true }] });
    await f.command(`/delivery/tests/${t.id}/results`, { checks: [{ code: 'Q-1', passed: true, actual: '通过', evidence_ids: [f.evidence.id] }], due_date: '2026-10-03' });
    await approveDelivery(f, 'tests', t);
  }
  const shipment = await f.command('/delivery/shipments', { ...refs, code: 'SHIP-1', title: '主机发运', assembly_ids: [assembly.id], consignee: '现场团队', delivery_address: '客户地址', planned_date: '2026-10-05', reviewer_id: f.userId });
  await approveDelivery(f, 'shipments', shipment);
  await f.command(`/delivery/shipments/${shipment.id}/dispatch`, { shipped_on: daysAgo(4), tracking_no: 'E09-TRACK', evidence_ids: [f.evidence.id] });
  return shipment;
}

test.describe('E09 现场任务进度只读汇总', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 交底后 4 待开工 0% -> 定位闭环后 25%, 面板与 :site_task_progress 一致', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f0 = await fixture(page, browser, '现场任务进度');
    // 现场滞后 +1 天: completed_on=today-1 => planned_start=today, 不逾期. 执行前 HTTP 配置交付要求, 保持版本一致.
    await mutate(page, f0.id, '/delivery/configuration', {
      required_stages: ['materials', 'assembly', 'quality', 'shipment'], required_test_types: ['SIT', 'FAT', 'SAT'],
      handover_deadline_days: 2, site_lag_days: 1, handover_required: true, reason: 'E2E 现场进度汇总' });
    const f = await toExecution(page, f0);
    const id = f.id;
    const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };

    // 1. 发运后交底未闭环: 无现场任务 -> 面板空态; :site_task_progress.available=false.
    const shipment = await shipped(f, refs);
    await open(page, id, '工程交付', '工勘与现场');
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无现场任务')).toBeVisible();
    await shot(page, 'e09stp-1-empty.png');
    await shotPanel(page, 'e09stp-1-empty-panel.png');
    let sp = (await api(page, 'GET', base(id) + '/delivery')).site_task_progress;
    expect(sp.available).toBe(false);
    expect(sp.total).toBe(0);
    expect(sp['closure-pct']).toBe(0);
    expect(sp['earliest-open']).toBeNull();
    expect(sp['by-key']).toHaveLength(4);
    expect(sp['by-key'].every(x => x.total === 0 && x.closed === 0)).toBe(true);

    // 2. 界面完成交底 -> 自动生成定位/安装/调试/SAT 4 条 draft -> 面板 现场任务 4 / 待开工 4 / 闭环率 0%.
    const handover = (await api(page, 'GET', base(id) + '/delivery')).handovers[0];
    await f.command(`/delivery/handovers/${handover.id}/complete`, { document_ids: [f.evidence.id], checklist_note: '交底清单 V1', completed_on: daysAgo(1) });
    await open(page, id, '工程交付', '工勘与现场');
    await expect(panel(page).getByText('现场任务 4')).toBeVisible();
    await expect(panel(page).getByText('待开工 4')).toBeVisible();
    await expect(panel(page).getByText('闭环率 0%')).toBeVisible();
    await expect(panel(page).getByText(`最早未完工计划 ${daysAgo(0)}`)).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '定位', exact: true })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: 'SAT', exact: true })).toBeVisible();
    await shot(page, 'e09stp-2-draft.png');
    await shotPanel(page, 'e09stp-2-draft-panel.png');
    sp = (await api(page, 'GET', base(id) + '/delivery')).site_task_progress;
    expect(sp).toMatchObject({ available: true, total: 4, closed: 0, 'in-progress': 0, draft: 4, 'closure-pct': 0 });
    expect(sp['earliest-open']).toBe(daysAgo(0));
    expect(sp.delayed).toBe(0);
    expect(sp['by-key'].find(x => x.key === 'positioning')).toMatchObject({ total: 1, closed: 0 });

    // 3. 顺序开工并闭环"定位" (先安装被 409, 定位开始->完成) -> 面板 已闭环 1 / 待开工 3 / 闭环率 25%.
    const siteTasks = (await api(page, 'GET', base(id) + '/delivery')).site_tasks;
    const positioning = siteTasks.find(t => t.task_key === 'positioning');
    const installation = siteTasks.find(t => t.task_key === 'installation');
    await mutate(page, id, `/delivery/site-tasks/${installation.id}/start`, { actual_start: daysAgo(0) }, 409); // 乱序禁止
    await f.command(`/delivery/site-tasks/${positioning.id}/start`, { actual_start: daysAgo(0) });
    await f.command(`/delivery/site-tasks/${positioning.id}/complete`, { actual_end: daysAgo(0), result: '定位完成', evidence_ids: [f.evidence.id] });
    await open(page, id, '工程交付', '工勘与现场');
    await expect(panel(page).getByText('已闭环 1')).toBeVisible();
    await expect(panel(page).getByText('待开工 3')).toBeVisible();
    await expect(panel(page).getByText('闭环率 25%')).toBeVisible();
    await shot(page, 'e09stp-3-closed.png');
    await shotPanel(page, 'e09stp-3-closed-panel.png');
    sp = (await api(page, 'GET', base(id) + '/delivery')).site_task_progress;
    expect(sp).toMatchObject({ total: 4, closed: 1, 'in-progress': 0, draft: 3, 'closure-pct': 25 });
    expect(sp['by-key'].find(x => x.key === 'positioning')).toMatchObject({ total: 1, closed: 1 });
    expect(sp['by-key'].find(x => x.key === 'installation')).toMatchObject({ total: 1, closed: 0 });

    // 只读汇总不改变逐条现场任务状态 (定位本身仍是 closed, 其余 draft).
    const rows = (await api(page, 'GET', base(id) + '/delivery')).site_tasks;
    expect(rows.find(t => t.id === positioning.id).status).toBe('closed');
    expect(rows.filter(t => t.task_key !== 'positioning').every(t => t.status === 'draft')).toBe(true);

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
