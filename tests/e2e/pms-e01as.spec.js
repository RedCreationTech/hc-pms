const { test, expect } = require('@playwright/test');
const path = require('node:path');

// E01 装配执行闭环只读汇总 (只读派生): 工程交付 -> 装配交检 页签内"装配执行进度汇总"面板,
// 汇编装配上岛/装配/单机交检/连线交检/下岛/交接逐步执行与独立交检的整体闭环健康度
// (免迁移/无门控/无新命令/无新路由). 数据来自 delivery workspace 顶层 :assembly_execution_progress;
// 逐条装配记录的状态与步骤不因该汇总改变.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/e01as');
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
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '装配执行进度汇总', exact: true }) }).first();

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '装配执行进度汇总', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `装配交检审批${suffix}`, role_key: `pms_e01_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_e01_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立交检审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `E01-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `E01-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `E01-${suffix}-M1`, name: '主机#1' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, sub, machine, suffix };
}

async function toExecution(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '单机装配任务', owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22', node_id: f.machine.node_id });
  const evidence = await command('/governance/documents', { code: 'E01-EV', title: '装配交检证据', filename: 'e01.txt', content: '合成装配记录.' });
  const requirement = await command('/governance/requirements', { code: 'E01-URS', text: '装配须逐步交检', category: '交付', priority: 'required', owner_id: f.adminId });
  const charter = await command('/governance/charters', { title: '装配章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  for (const stage of ['execution', 'closure']) {
    const template = await command('/governance/gate-templates', { code: `E01-${stage}`, title: `${stage}工程评审`, stage, required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
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

const STEPS = ['on_island', 'assembling', 'unit_inspection', 'wiring_inspection', 'off_island', 'handover'];

// 冻结BOM -> 齐套100% -> 齐套Gate (assembly.start 阻断项) -> 返回 bom, 此时尚无装配记录.
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

async function makeAssembly(f, refs, bom, code, title) {
  return f.command('/delivery/assemblies', { ...refs, code, title, bom_id: bom.id, owner_id: f.adminId });
}

// 顺序登记前 n 个步骤 (日期不倒退, 不晚于今天).
async function recordSteps(f, assembly, n) {
  for (let i = 0; i < n; i += 1) {
    await f.command(`/delivery/assemblies/${assembly.id}/steps`, { step: STEPS[i], actual_date: daysAgo(n - i), note: `步骤${STEPS[i]}` });
  }
}

test.describe('E01 装配执行闭环只读汇总', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 三装配(全步/2步/草稿) -> 一台独立交检通过, 面板与 :assembly_execution_progress 一致', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f0 = await fixture(page, browser, '装配执行进度');
    const f = await toExecution(page, f0);
    const id = f.id;
    const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };

    // 冻结BOM + 齐套Gate 放行 (assembly.start 阻断已解除), 但此时尚未建立任何装配.
    const bom = await kittedAssemblyBom(f, refs);

    // 1. 尚无装配记录 -> 面板空态; :assembly_execution_progress.available=false, by-step 7 行全 0.
    await open(page, id, '工程交付', '装配交检');
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无装配记录')).toBeVisible();
    await shot(page, 'e01as-1-empty.png');
    await shotPanel(page, 'e01as-1-empty-panel.png');
    let ap = (await api(page, 'GET', base(id) + '/delivery')).assembly_execution_progress;
    expect(ap.available).toBe(false);
    expect(ap.total).toBe(0);
    expect(ap['closure-pct']).toBe(0);
    expect(ap['step-pct']).toBe(0);
    expect(ap['by-step']).toHaveLength(7);
    expect(ap['by-step'].map(x => x.key)).toEqual(['not_started', ...STEPS]);
    expect(ap['by-step'].every(x => x.total === 0)).toBe(true);

    // 2. 建三台装配: A 开工+登记全部6步(未提交) -> in_progress 全步; B 开工+2步 -> in_progress; C 保持 draft.
    const a = await makeAssembly(f, refs, bom, 'ASS-A', '主机装配A');
    const b = await makeAssembly(f, refs, bom, 'ASS-B', '主机装配B');
    const c = await makeAssembly(f, refs, bom, 'ASS-C', '主机装配C');
    await f.command(`/delivery/assemblies/${a.id}/start`, { evidence_ids: [f.evidence.id] });
    await recordSteps(f, a, 6);
    await f.command(`/delivery/assemblies/${b.id}/start`, { evidence_ids: [f.evidence.id] });
    await recordSteps(f, b, 2);

    await open(page, id, '工程交付', '装配交检');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('装配 3', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('草稿 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('装配中 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已交检通过 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('交检闭环率 0%', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('步骤完成度 44%', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('全步骤完成 1/3', { exact: true })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '上岛', exact: true })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '交接', exact: true })).toBeVisible();
    await shot(page, 'e01as-2-inprogress.png');
    await shotPanel(page, 'e01as-2-inprogress-panel.png');
    ap = (await api(page, 'GET', base(id) + '/delivery')).assembly_execution_progress;
    expect(ap).toMatchObject({ available: true, total: 3, draft: 1, 'in-progress': 2, 'in-review': 0, approved: 0, 'closure-pct': 0, 'fully-stepped': 1, 'step-pct': 44 });
    expect(ap['by-step'].map(x => x.total)).toEqual([1, 0, 1, 0, 0, 0, 1]); // not_started(C) / assembling(B) / handover(A)

    // 3. A 提交独立交检并被审核人批准 -> approved; 面板 已交检通过 1 / 装配中 1 / 闭环率 33%, 步骤完成度仍 44%.
    await f.command(`/delivery/assemblies/${a.id}/submit`, { reviewer_id: f.userId, evidence_ids: [f.evidence.id] });
    await f.command(`/delivery/assemblies/${a.id}/decision`, { decision: 'approved', reason: '独立交检通过' }, f.reviewer);
    await open(page, id, '工程交付', '装配交检');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('装配 3', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('草稿 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('装配中 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已交检通过 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('交检闭环率 33%', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('步骤完成度 44%', { exact: true })).toBeVisible();
    await shot(page, 'e01as-3-approved.png');
    await shotPanel(page, 'e01as-3-approved-panel.png');
    ap = (await api(page, 'GET', base(id) + '/delivery')).assembly_execution_progress;
    expect(ap).toMatchObject({ total: 3, draft: 1, 'in-progress': 1, 'in-review': 0, approved: 1, 'closure-pct': 33, 'fully-stepped': 1, 'step-pct': 44 });
    expect(ap['by-step'].map(x => x.total)).toEqual([1, 0, 1, 0, 0, 0, 1]);

    // 只读汇总不改变逐条装配状态与步骤 (A 仍 approved 且保留 6 步, B 仍 in_progress/2 步, C 仍 draft/0 步).
    const rows = (await api(page, 'GET', base(id) + '/delivery')).assemblies;
    expect(rows.find(x => x.id === a.id).status).toBe('approved');
    expect(rows.find(x => x.id === a.id).steps).toHaveLength(6);
    expect(rows.find(x => x.id === b.id).status).toBe('in_progress');
    expect(rows.find(x => x.id === b.id).steps).toHaveLength(2);
    expect(rows.find(x => x.id === c.id).status).toBe('draft');
    expect(rows.find(x => x.id === c.id).steps || []).toHaveLength(0);

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
