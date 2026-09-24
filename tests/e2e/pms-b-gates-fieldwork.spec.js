const { test, expect } = require('@playwright/test');
const path = require('node:path');

// B/D/E 节: 关口目录与阻断检查点 (B11), DQ 关键任务 (B08), 启动会会前包与基线引用 (B05), 单机局部暂停 (B16),
// 工勘 (B07), 齐套多层卷积 (D06), 包材申请 (D03), 装配步骤明细 (E01), 发货前本地条件 (E04), 交底时限 (E07), 现场任务 (E08/E09).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/b-fieldwork');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const localDate = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; // 本地日期 (与后端所在时区的 "今天" 一致, 避免 0-8 点 UTC 跨日)
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  // 共享库累积用户/任务后长列表被 antd 虚拟滚动裁剪, 可搜索的下拉先按标签过滤再选.
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
}

async function save(page, title, expected = 200) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(expected);
  if (expected === 200) { await expect(form).toBeHidden(); await page.waitForLoadState('networkidle'); }
  return body;
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `现场审批${suffix}`, role_key: `pms_field_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_field_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立现场审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `FW-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `FW-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `FW-${suffix}-M1`, name: '主机#1' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, sub, machine, suffix };
}

async function toExecution(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '单机交付任务', owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22', node_id: f.machine.node_id });
  const evidence = await command('/governance/documents', { code: 'FW-EV', title: '现场闭环证据', filename: 'fw.txt', content: '合成现场记录.' });
  const requirement = await command('/governance/requirements', { code: 'FW-URS', text: '现场交付须验证', category: '交付', priority: 'required', owner_id: f.adminId });
  const charter = await command('/governance/charters', { title: '现场章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  for (const stage of ['execution', 'closure']) {
    const template = await command('/governance/gate-templates', { code: `FW-${stage}`, title: `${stage}工程评审`, stage, required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
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
  return { ...f, task, evidence, requirement, baseline, command };
}

async function approveDelivery(f, collection, item, action = 'submit') {
  await f.command(`/delivery/${collection}/${item.id}/${action}`, { evidence_ids: [f.evidence.id], ...(collection === 'shipments' ? {} : { reviewer_id: f.userId }) });
  return f.command(`/delivery/${collection}/${item.id}/decision`, { decision: 'approved', reason: '独立核查' }, f.reviewer);
}

test.describe('B/D/E 节 关口阻断, DQ, 启动会, 局部暂停, 工勘, 齐套卷积, 装配步骤, 发货前条件, 交底与现场任务', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('治理侧: 关口目录建立, DQ 签认与交付件失效, 启动会强制会前包与基线, 单机局部暂停冻结反馈', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await toExecution(page, await fixture(page, browser, '治理闭环'));
    const id = f.id;

    // 1. 关口目录: 界面从目录建立 齐套Gate -> 进展汇总卡片显示 阻断 装配开工.
    await open(page, id, '需求与治理', 'Gate评审');
    await drawer(page).getByRole('button', { name: '从关口目录建立', exact: true }).click();
    await choose(page, modal(page, '从关口目录建立'), 'gate_type', '零件齐套Gate');
    const kitting = (await save(page, '从关口目录建立')).data.result;
    expect(kitting.blocks).toEqual(['assembly.start']);
    await expect(drawer(page).getByText('阻断 装配开工').first()).toBeVisible();
    await shot(page, 'b-1-gate-catalog-kitting.png');

    // 2. DQ: 界面建立 DQ 关键任务 -> 填写检查 -> 提交签认 -> 审核人签认; 新版本交付件 -> "交付件已更新".
    await tab(page, 'DQ与局部暂停');
    await drawer(page).getByRole('button', { name: '建立DQ关键任务', exact: true }).click();
    const dqForm = modal(page, '建立DQ关键任务');
    await fill(dqForm, { code: 'DQ-1', title: '主机DQ编制', check_titles: '设计输入完整\n图纸编号规范' });
    await choose(page, dqForm, 'owner_id', /\/ admin$/);
    await choose(page, dqForm, 'deliverable_ids', 'FW-EV');
    const dq = (await save(page, '建立DQ关键任务')).data.result;
    expect(dq.checklist).toHaveLength(2);
    await row(page, 'DQ-1').getByRole('button', { name: '填写检查' }).click();
    const checkForm = modal(page, '填写DQ检查结果');
    await choose(page, checkForm, 'passed_D1', '检查通过');
    await choose(page, checkForm, 'passed_D2', '检查通过');
    await save(page, '填写DQ检查结果');
    await row(page, 'DQ-1').getByRole('button', { name: '提交签认' }).click();
    await choose(page, modal(page, '提交DQ签认'), 'reviewer_id', '独立现场审核人');
    await save(page, '提交DQ签认');
    await mutate(f.reviewer, id, `/governance/dqs/${dq.id}/decision`, { decision: 'approved', reason: '签认' });
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(row(page, 'DQ-1').getByText('版本有效')).toBeVisible();
    await expect(row(page, 'DQ-1').getByText('2/2')).toBeVisible();
    await mutate(page, id, `/governance/documents/${f.evidence.id}/revisions`, { code: 'FW-EV', title: '现场闭环证据', filename: 'fw.txt', content: '新版本' });
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(row(page, 'DQ-1').getByText('交付件已更新')).toBeVisible();
    await shot(page, 'b-2-dq-stale.png');

    // 3. 启动会: 无会前包/基线被 409; 带资料与基线登记后显示计划修订.
    await tab(page, '会议行动');
    await drawer(page).getByRole('button', { name: '登记项目会议', exact: true }).click();
    const meetingForm = modal(page, '登记项目会议');
    await fill(meetingForm, { title: '项目启动会', held_on: '2026-09-23', minutes: '确认主计划与售前资料' });
    await choose(page, meetingForm, 'meeting_type', '项目启动会');
    await choose(page, meetingForm, 'attendee_ids', /\/ admin$/);
    const rejected = await save(page, '登记项目会议', 409);
    expect(rejected.msg).toContain('会前包');
    await choose(page, meetingForm, 'material_ids', 'FW-EV');
    await choose(page, meetingForm, 'baseline_id', '计划修订');
    const meeting = (await save(page, '登记项目会议')).data.result;
    expect(meeting.meeting_type).toBe('kickoff');
    expect(meeting.baseline_id).toBe(f.baseline.baseline_id);
    await expect(row(page, '项目启动会').getByText(/计划修订 \d+/)).toBeVisible();
    await shot(page, 'b-3-kickoff-meeting.png');

    // 4. 局部暂停: 界面暂停主机单元 -> 单机任务反馈 409, 主计划任务不受影响 -> 恢复.
    await tab(page, 'DQ与局部暂停');
    await drawer(page).getByRole('button', { name: '局部暂停', exact: true }).click();
    const pauseForm = modal(page, '局部暂停单机/子项目');
    await choose(page, pauseForm, 'node_id', '主机单元');
    await fill(pauseForm, { reason: '客户暂缓主机单元' });
    const pause = (await save(page, '局部暂停单机/子项目')).data.result;
    expect(pause.status).toBe('active');
    await expect(row(page, '主机单元').getByText('暂停中')).toBeVisible();
    await shot(page, 'b-4-node-pause.png');
    await mutate(page, id, `/tasks/${f.task.task_id}/feedback`, { status: 'in_progress', percent_complete: 10, remaining_days: 1 }, 409);
    const other = await f.command('/tasks', { wbs_code: '2', name: '主计划任务', owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22' });
    await mutate(page, id, `/tasks/${other.task_id}/feedback`, { status: 'in_progress', percent_complete: 10, remaining_days: 1 });
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await row(page, '主机单元').getByRole('button', { name: '恢复' }).click();
    await fill(modal(page, '恢复节点执行'), { impact_note: '顺延两周' });
    await save(page, '恢复节点执行');
    await expect(row(page, '主机单元').getByText('已恢复')).toBeVisible();
    await mutate(page, id, `/tasks/${f.task.task_id}/feedback`, { status: 'in_progress', percent_complete: 10, remaining_days: 1 });
    await f.context.close();
    expect(errors).toEqual([]);
  });

  test('交付侧: 交付配置, 包材申请, 齐套多层卷积, 齐套Gate阻断开工, 装配步骤, 发货前条件, 交底时限与现场任务, 工勘', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f0 = await fixture(page, browser, '交付闭环');
    // 1. 执行前配置交付要求: 工勘1次, 发货前须入库确认, 交底+2天, 现场滞后+1天.
    await open(page, f0.id, '工程交付');
    await drawer(page).getByRole('button', { name: '配置交付要求', exact: true }).click();
    const cfg = modal(page, '配置交付验收要求');
    await cfg.locator('#required_survey_visits').fill('1');
    await choose(page, cfg, 'pre_ship_conditions', '入库/装箱已确认');
    await cfg.locator('#handover_deadline_days').fill('2');
    await cfg.locator('#site_lag_days').fill('1');
    await fill(cfg, { reason: 'E2E 现场配置' });
    await save(page, '配置交付验收要求');
    await expect(drawer(page).getByText('工勘 1 次')).toBeVisible();
    await expect(drawer(page).getByText('发货前须入库确认')).toBeVisible();
    await shot(page, 'd-1-delivery-configuration.png');
    const f = await toExecution(page, f0);
    const id = f.id;
    const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };

    // 2. 包材申请 (D03) 与齐套多层卷积 (D06).
    await open(page, id, '工程交付', '备料与BOM');
    await drawer(page).getByRole('button', { name: '新建备料申请', exact: true }).click();
    const pk = modal(page, '新建备料申请');
    await fill(pk, { code: 'PK-1', title: '出口包材申请', needed_on: '2026-10-01', packaging_spec: '出口木箱 2000x1200 熏蒸', items_0_code: 'BOX', items_0_name: '木箱', items_0_quantity: 1 });
    await choose(page, pk, 'request_type', '包材申请');
    await choose(page, pk, 'node_id', '主机#1');
    await choose(page, pk, 'owner_id', /\/ admin$/);
    await choose(page, pk, 'task_id', '单机交付任务');
    await choose(page, pk, 'requirement_ids', 'FW-URS');
    const packaging = (await save(page, '新建备料申请')).data.result;
    expect(packaging.request_type).toBe('packaging');
    await expect(row(page, 'PK-1').getByRole('cell', { name: '包材申请', exact: true })).toBeVisible();
    const material = await f.command('/delivery/material-requests', { ...refs, code: 'MR-1', title: '长周期件', request_type: 'long_lead', owner_id: f.adminId, needed_on: '2026-10-01', items: [{ code: 'M-1', name: '执行器', quantity: 2, unit: '个' }, { code: 'M-2', name: '连接线', quantity: 4, unit: '根' }] });
    await approveDelivery(f, 'material-requests', material);
    const bom = await f.command('/delivery/boms', { code: 'BOM-1', title: '受控配置', material_request_id: material.id });
    await approveDelivery(f, 'boms', bom, 'freeze');
    await f.command(`/delivery/boms/${bom.id}/kit`, { items: [{ code: 'M-1', available_quantity: 2 }, { code: 'M-2', available_quantity: 3 }], evidence_ids: [f.evidence.id] });
    await open(page, id, '工程交付', '备料与BOM');
    await expect(drawer(page).getByText('整体齐套率 50%')).toBeVisible();
    await expect(drawer(page).getByRole('cell', { name: '主机#1', exact: true })).toBeVisible();
    await drawer(page).getByText(/缺件清单 1 行/).click();
    await expect(drawer(page).getByRole('cell', { name: 'M-2', exact: true })).toBeVisible();
    await shot(page, 'd-2-kitting-rollup.png');
    const rollup = (await api(page, 'GET', base(id) + '/delivery')).kitting_rollup;
    expect(rollup.nodes.find(n => n.node_id === f.machine.node_id).kit_percent).toBe(50);
    expect(rollup.nodes.find(n => n.node_type === 'sub').kit_percent).toBe(50);

    // 3. 齐套Gate阻断装配开工 (B11) -> 通过后开工 -> 登记上岛/装配步骤 (E01).
    const kitting = await f.command('/governance/gate-templates/from-catalog', { gate_type: 'kitting' });
    await f.command(`/delivery/boms/${bom.id}/kit`, { items: [{ code: 'M-1', available_quantity: 2 }, { code: 'M-2', available_quantity: 4 }], evidence_ids: [f.evidence.id] });
    const assembly = await f.command('/delivery/assemblies', { ...refs, code: 'ASS-1', title: '主机装配', bom_id: bom.id, owner_id: f.adminId });
    await open(page, id, '工程交付', '装配交检');
    await row(page, 'ASS-1').getByRole('button', { name: '登记开工' }).click();
    await choose(page, modal(page, '登记装配开工'), 'evidence_ids', 'FW-EV');
    const blocked = await save(page, '登记装配开工', 409);
    expect(blocked.msg).toContain('阻断关口尚未通过');
    await expect(modal(page, '登记装配开工').getByRole('alert')).toContainText('阻断关口尚未通过');
    await shot(page, 'd-3-assembly-blocked-by-kitting-gate.png');
    await modal(page, '登记装配开工').getByRole('button', { name: /返\s*回/ }).click();
    const gate = await f.command('/governance/gates', { template_id: kitting.id, title: '齐套放行', reviewer_id: f.userId });
    await f.command(`/governance/gates/${gate.id}/checks`, { checks: [{ code: 'KIT-1', passed: true, evidence_ids: [f.evidence.id] }, { code: 'KIT-2', passed: true, evidence_ids: [f.evidence.id] }] });
    await f.command(`/governance/gates/${gate.id}/submit`, {});
    await f.command(`/governance/gates/${gate.id}/decision`, { decision: 'approved', reason: '齐套确认' }, f.reviewer);
    await f.command(`/delivery/assemblies/${assembly.id}/start`, { evidence_ids: [f.evidence.id] });
    await open(page, id, '工程交付', '装配交检');
    await row(page, 'ASS-1').getByRole('button', { name: '登记步骤' }).click();
    const stepForm = modal(page, '登记装配步骤');
    await fill(stepForm, { actual_date: daysAgo(3), note: '设备上岛' });
    await save(page, '登记装配步骤');
    await expect(row(page, 'ASS-1').getByText(`上岛 ${daysAgo(3)}`)).toBeVisible();
    await row(page, 'ASS-1').getByRole('button', { name: '登记步骤' }).click();
    await fill(modal(page, '登记装配步骤'), { actual_date: daysAgo(2) });
    await save(page, '登记装配步骤');
    await expect(row(page, 'ASS-1').getByText(`装配 ${daysAgo(2)}`)).toBeVisible();
    await shot(page, 'd-4-assembly-steps.png');

    // 4. 试验/发运: 发货前条件未确认 -> 放行被拒; 界面确认入库 -> 放行/发运 -> 交底任务自动生成.
    await approveDelivery(f, 'assemblies', assembly);
    for (const type of ['SIT', 'FAT']) {
      const t = await f.command('/delivery/tests', { ...refs, code: `${type}-1`, title: `${type}验证`, assembly_id: assembly.id, test_type: type, owner_id: f.adminId, criteria: [{ code: 'Q-1', title: '动作满足URS', required: true }] });
      await f.command(`/delivery/tests/${t.id}/results`, { checks: [{ code: 'Q-1', passed: true, actual: '通过', evidence_ids: [f.evidence.id] }], due_date: '2026-10-03' });
      await approveDelivery(f, 'tests', t);
    }
    const shipment = await f.command('/delivery/shipments', { ...refs, code: 'SHIP-1', title: '主机发运', assembly_ids: [assembly.id], consignee: '现场团队', delivery_address: '客户地址', planned_date: '2026-10-05', reviewer_id: f.userId });
    await open(page, id, '工程交付', '发运签收');
    await expect(row(page, 'SHIP-1').getByText('入库/装箱已确认')).toBeVisible();
    await row(page, 'SHIP-1').getByRole('button', { name: '申请放行' }).click();
    await choose(page, modal(page, '提交发运放行'), 'evidence_ids', 'FW-EV');
    const notReady = await save(page, '提交发运放行', 409);
    expect(notReady.msg).toContain('入库');
    await modal(page, '提交发运放行').getByRole('button', { name: /返\s*回/ }).click();
    await row(page, 'SHIP-1').getByRole('button', { name: '发货前条件' }).click();
    const cond = modal(page, '确认发货前条件');
    await choose(page, cond, 'warehouse_in_confirmed', '已确认');
    await fill(cond, { warehouse_note: 'WMS 入库单 IN-001' });
    await save(page, '确认发货前条件');
    await shot(page, 'd-5-preship-conditions.png');
    await approveDelivery(f, 'shipments', shipment);
    const shippedOn = daysAgo(4);
    await f.command(`/delivery/shipments/${shipment.id}/dispatch`, { shipped_on: shippedOn, tracking_no: 'FW-TRACK', evidence_ids: [f.evidence.id] });
    await open(page, id, '工程交付', '工勘与现场');
    await expect(row(page, 'HO-SHIP-1').getByText(/已逾期 \d+ 天/)).toBeVisible();
    await row(page, 'HO-SHIP-1').scrollIntoViewIfNeeded();
    await shot(page, 'e-1-handover-overdue.png');
    await row(page, 'HO-SHIP-1').getByRole('button', { name: '完成交底' }).click();
    const ho = modal(page, '完成项目交底');
    await fill(ho, { completed_on: daysAgo(1), checklist_note: '交底清单 V1' });
    await choose(page, ho, 'document_ids', 'FW-EV');
    const handover = (await save(page, '完成项目交底')).data.result;
    expect(handover.completed_late).toBe(true);
    await expect(row(page, 'HO-SHIP-1').getByText('逾期完成')).toBeVisible();
    await expect(drawer(page).getByRole('cell', { name: /现场定位/ })).toBeVisible();
    await expect(drawer(page).getByRole('cell', { name: /现场SAT/ })).toBeVisible();
    // 现场任务顺序: 先开始定位, 完成后才能开始安装.
    await row(page, '现场定位').getByRole('button', { name: '开始' }).click();
    await fill(modal(page, '开始现场任务'), { actual_start: daysAgo(0) });
    await save(page, '开始现场任务');
    await row(page, '现场定位').getByRole('button', { name: '完成' }).click();
    const done = modal(page, '完成现场任务');
    await fill(done, { actual_end: daysAgo(0), result: '定位完成' });
    await choose(page, done, 'evidence_ids', 'FW-EV');
    await save(page, '完成现场任务');
    await expect(row(page, '现场定位').getByText('已关闭')).toBeVisible();
    await shot(page, 'e-2-site-tasks.png');
    const siteTasks = (await api(page, 'GET', base(id) + '/delivery')).site_tasks;
    const commissioning = siteTasks.find(t => t.task_key === 'commissioning');
    await mutate(page, id, `/delivery/site-tasks/${commissioning.id}/start`, { actual_start: daysAgo(0) }, 409);

    // 5. 工勘 (B07): 登记 -> 提交确认 -> 审核人批准 -> 收尾阻塞消除.
    await drawer(page).getByRole('button', { name: '登记工勘任务', exact: true }).click();
    const sv = modal(page, '登记工勘任务');
    await fill(sv, { code: 'SV-1', title: '首次工勘', planned_date: '2026-10-10', deliverable: '现场勘察报告' });
    await choose(page, sv, 'owner_id', /\/ admin$/);
    const survey = (await save(page, '登记工勘任务')).data.result;
    expect(survey.visit_no).toBe(1);
    expect((await api(page, 'GET', base(id) + '/delivery')).blockers.some(b => b.includes('工勘'))).toBe(true);
    await row(page, 'SV-1').getByRole('button', { name: '提交工勘确认' }).click();
    const svSubmit = modal(page, '提交工勘确认');
    await fill(svSubmit, { actual_date: daysAgo(1), findings: '地基满足要求' });
    await choose(page, svSubmit, 'evidence_ids', 'FW-EV');
    await choose(page, svSubmit, 'reviewer_id', '独立现场审核人');
    await save(page, '提交工勘确认');
    await mutate(f.reviewer, id, `/delivery/surveys/${survey.id}/decision`, { decision: 'approved', reason: '交付物确认' });
    await open(page, id, '工程交付', '工勘与现场');
    await expect(row(page, 'SV-1').getByText('已批准')).toBeVisible();
    await shot(page, 'e-3-survey-approved.png');
    expect((await api(page, 'GET', base(id) + '/delivery')).blockers.some(b => b.includes('工勘'))).toBe(false);
    await f.context.close();
    expect(errors).toEqual([]);
  });
});
