const { test, expect } = require('@playwright/test');
const path = require('node:path');

// E09 现场任务进度只读汇总 — 完整功能演示录像 (真实浏览器, 带旁白字幕叠加).
// 展示: 交付配置 -> 发运后交底 -> 界面完成交底自动生成定位/安装/调试/SAT -> 现场任务进度汇总面板
//       从"空态" -> "4 待开工 闭环率 0%" -> 顺序开工并闭环"定位" -> "已闭环 1 闭环率 25%", 只读派生不门控写操作.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
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

// 旁白字幕叠加 (仅用于录像, 不进入产品代码).
async function caption(page, text) {
  await page.evaluate(t => {
    let el = document.getElementById('e09-demo-caption');
    if (!el) {
      el = document.createElement('div');
      el.id = 'e09-demo-caption';
      el.style.cssText = 'position:fixed;left:50%;bottom:28px;transform:translateX(-50%);z-index:99999;'
        + 'max-width:80%;padding:14px 26px;border-radius:12px;background:rgba(17,24,39,0.92);color:#fff;'
        + 'font-size:20px;line-height:1.5;font-weight:600;box-shadow:0 8px 30px rgba(0,0,0,0.35);text-align:center;'
        + 'font-family:"PingFang SC","Microsoft YaHei",sans-serif;pointer-events:none;';
      document.body.appendChild(el);
    }
    el.textContent = t;
  }, text);
}

const pause = (page, ms = 1400) => page.waitForTimeout(ms);
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '现场任务进度汇总', exact: true }) }).first();

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `现场审批${suffix}`, role_key: `pms_e09d_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_e09d_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立现场审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `E09D-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `E09D-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `E09D-${suffix}-M1`, name: '主机#1' });
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

test.use({
  viewport: { width: 1600, height: 1000 },
  video: { mode: 'on', size: { width: 1600, height: 1000 } },
});

test.describe('E09 现场任务进度只读汇总 — 完整功能演示', () => {
  test.setTimeout(300000);

  test('交付配置 -> 完成交底 -> 现场任务进度汇总 空态/待开工/闭环 全流程', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    await caption(page, 'E09 现场任务进度只读汇总 · 真实浏览器功能演示');
    await pause(page, 2000);

    const f0 = await fixture(page, browser, '现场任务进度演示');
    await caption(page, '步骤 1/6 · 执行前配置交付要求: 交底期限 2 天, 现场滞后 1 天');
    await mutate(page, f0.id, '/delivery/configuration', {
      required_stages: ['materials', 'assembly', 'quality', 'shipment'], required_test_types: ['SIT', 'FAT', 'SAT'],
      handover_deadline_days: 2, site_lag_days: 1, handover_required: true, reason: 'E2E 现场进度演示' });
    const f = await toExecution(page, f0);
    const id = f.id;
    const refs = { task_id: f.task.task_id, requirement_ids: f.requirement.id ? [f.requirement.id] : [] };

    await caption(page, '步骤 2/6 · 推进到执行: 冻结BOM -> 齐套100% -> 齐套Gate -> 装配交检 -> SIT/FAT -> 发运并登记实际发运');
    await shipped(f, refs);
    await pause(page, 800);

    await caption(page, '步骤 3/6 · 打开 工程交付 › 工勘与现场, 查看"现场任务进度汇总"面板');
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无现场任务')).toBeVisible();
    await caption(page, '当前状态 · 交底尚未闭环 -> 无现场任务 -> 面板显示引导空态 (available=false)');
    await pause(page, 2600);

    await caption(page, '步骤 4/6 · 界面点击"完成交底", 自动生成 定位/安装/调试/SAT 四条现场任务');
    await row(page, 'HO-SHIP-1').scrollIntoViewIfNeeded();
    await row(page, 'HO-SHIP-1').getByRole('button', { name: '完成交底' }).click();
    const ho = modal(page, '完成项目交底');
    await fill(ho, { completed_on: daysAgo(1), checklist_note: '交底清单 V1' });
    await choose(page, ho, 'document_ids', 'E09-EV');
    await save(page, '完成项目交底');
    await pause(page, 800);
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('现场任务 4')).toBeVisible();
    await expect(panel(page).getByText('待开工 4')).toBeVisible();
    await expect(panel(page).getByText('闭环率 0%')).toBeVisible();
    await caption(page, '当前状态 · 4 条待开工, 闭环率 0%, 最早未完工计划 = 完成交底 + 滞后 1 天');
    await pause(page, 3000);

    await caption(page, '步骤 5/6 · 现场任务须按序执行: 先"开始"再"完成"定位环节');
    await row(page, '现场定位').scrollIntoViewIfNeeded();
    await row(page, '现场定位').getByRole('button', { name: '开始' }).click();
    await fill(modal(page, '开始现场任务'), { actual_start: daysAgo(0) });
    await save(page, '开始现场任务');
    await pause(page, 600);
    await row(page, '现场定位').getByRole('button', { name: '完成' }).click();
    const done = modal(page, '完成现场任务');
    await fill(done, { actual_end: daysAgo(0), result: '定位完成' });
    await choose(page, done, 'evidence_ids', 'E09-EV');
    await save(page, '完成现场任务');
    await pause(page, 800);

    await caption(page, '步骤 6/6 · 闭环"定位"后, 只读汇总面板实时刷新为 已闭环 1 / 待开工 3 / 闭环率 25%');
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('已闭环 1')).toBeVisible();
    await expect(panel(page).getByText('闭环率 25%')).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '定位', exact: true })).toBeVisible();
    await caption(page, '验证 · 面板数字与后端 :site_task_progress 只读派生一致, 逐条现场任务状态不受汇总影响');
    await pause(page, 3000);

    const sp = (await api(page, 'GET', base(id) + '/delivery')).site_task_progress;
    expect(sp).toMatchObject({ total: 4, closed: 1, draft: 3, 'closure-pct': 25 });

    await caption(page, '演示结束 · E09 现场任务进度只读汇总 (免迁移 / 无门控 / 无新命令)');
    await pause(page, 2200);

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
