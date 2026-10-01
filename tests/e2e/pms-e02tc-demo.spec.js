const { test, expect } = require('@playwright/test');

// E02/E03 试验执行闭环只读汇总 — 完整功能演示录像 (真实浏览器, 带旁白字幕叠加).
// 展示: 建立并批准一台装配 -> 建四台试验 (SIT-1 全流程批准 / FAT-1 记录结果并提交 -> 检验中 / FAT-2 记录结果 -> 待提交 / SAT-1 仅建立 -> 草稿)
//       -> 面板"试验4/草稿1/待提交1/检验中1/已批准1/已驳回0/已登记结果3/必检达标3\/4/批准闭环率25%" + SIT/FAT/SAT 分布
//       -> 再批准 FAT-1 + 提交并批准 FAT-2 -> 已批准3 / 检验中0 / 闭环率75% / FAT 批准率100% (SAT 仍草稿封顶).
// 只读派生不门控写操作, 结果登记/提交/签核仍由服务端状态机与证据门控在写入时强制.
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
    let el = document.getElementById('e02-demo-caption');
    if (!el) {
      el = document.createElement('div');
      el.id = 'e02-demo-caption';
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
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '试验执行闭环汇总', exact: true }) }).first();

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `试验签核审批${suffix}`, role_key: `pms_e02d_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_e02d_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立试验审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `E02D-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `E02D-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `E02D-${suffix}-M1`, name: '主机#1' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, sub, machine, suffix };
}

async function toExecution(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '单机装配任务', owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22', node_id: f.machine.node_id });
  const evidence = await command('/governance/documents', { code: 'E02-EV', title: '试验交检证据', filename: 'e02.txt', content: '合成试验记录.' });
  const requirement = await command('/governance/requirements', { code: 'E02-URS', text: '试验须逐项签核', category: '交付', priority: 'required', owner_id: f.adminId });
  const charter = await command('/governance/charters', { title: '试验章程', objective: '受控交付', scope: '合成系统', success_criteria: '证据闭环', sponsor_id: f.adminId });
  await command(`/governance/charters/${charter.id}/submit`, { reviewer_id: f.userId });
  await command(`/governance/charters/${charter.id}/decision`, { decision: 'approved', reason: '独立确认' }, f.reviewer);
  for (const stage of ['execution', 'closure']) {
    const template = await command('/governance/gate-templates', { code: `E02-${stage}`, title: `${stage}工程评审`, stage, required: true, checks: [{ code: 'C1', title: '实际证据齐全', required: true }] });
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

async function approvedAssembly(f, refs, bom) {
  const assembly = await f.command('/delivery/assemblies', { ...refs, code: 'ASS-1', title: '执行装配', bom_id: bom.id, owner_id: f.adminId });
  await f.command(`/delivery/assemblies/${assembly.id}/start`, { evidence_ids: [f.evidence.id] });
  await approveDelivery(f, 'assemblies', assembly);
  return assembly;
}

const CRITERIA = [{ code: 'Q-1', title: '动作满足URS', required: true }];
const resultsBody = f => ({ checks: [{ code: 'Q-1', passed: true, actual: '通过', evidence_ids: [f.evidence.id] }], due_date: '2026-10-03' });

async function makeTest(f, assembly, refs, code, type) {
  return f.command('/delivery/tests', { ...refs, code, title: `${type}验证`, assembly_id: assembly.id, test_type: type, owner_id: f.adminId, criteria: CRITERIA });
}

test.use({
  viewport: { width: 1600, height: 1000 },
  video: { mode: 'on', size: { width: 1600, height: 1000 } },
});

test.describe('E02/E03 试验执行闭环只读汇总 — 完整功能演示', () => {
  test.setTimeout(300000);

  test('四态草稿/待提交/检验中/已批准 + SIT/FAT/SAT 分布 -> 逐条批准刷新闭环率', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    await caption(page, 'E02/E03 试验执行闭环只读汇总 · 真实浏览器功能演示');
    await pause(page, 2000);

    await caption(page, '步骤 1/5 · 建项目 + 独立试验审核人 + 执行前置 + 冻结BOM齐套 + 批准一台装配');
    const f0 = await fixture(page, browser, '试验执行闭环演示');
    const f = await toExecution(page, f0);
    const id = f.id;
    const refs = { task_id: f.task.task_id, requirement_ids: [f.requirement.id] };
    const bom = await kittedAssemblyBom(f, refs);
    const assembly = await approvedAssembly(f, refs, bom);
    await pause(page, 600);

    await caption(page, '步骤 2/5 · 打开 工程交付 › 质量试验, 尚无试验 -> 面板显示引导空态');
    await open(page, id, '工程交付', '质量试验');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无试验记录, 装配通过后建立 SIT/FAT/SAT 质量试验')).toBeVisible();
    await pause(page, 2600);

    await caption(page, '步骤 3/5 · 建四台试验: SIT-1 全流程批准 / FAT-1 检验中 / FAT-2 待提交 / SAT-1 草稿 -> 闭环率 25%');
    const sit1 = await makeTest(f, assembly, refs, 'SIT-1', 'SIT');
    await f.command(`/delivery/tests/${sit1.id}/results`, resultsBody(f));
    await f.command(`/delivery/tests/${sit1.id}/submit`, { reviewer_id: f.userId, evidence_ids: [f.evidence.id] });
    await f.command(`/delivery/tests/${sit1.id}/decision`, { decision: 'approved', reason: '独立签核通过' }, f.reviewer);
    const fat1 = await makeTest(f, assembly, refs, 'FAT-1', 'FAT');
    await f.command(`/delivery/tests/${fat1.id}/results`, resultsBody(f));
    await f.command(`/delivery/tests/${fat1.id}/submit`, { reviewer_id: f.userId, evidence_ids: [f.evidence.id] });
    const fat2 = await makeTest(f, assembly, refs, 'FAT-2', 'FAT');
    await f.command(`/delivery/tests/${fat2.id}/results`, resultsBody(f));
    const sat1 = await makeTest(f, assembly, refs, 'SAT-1', 'SAT');
    await open(page, id, '工程交付', '质量试验');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('试验 4', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('草稿 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('待提交 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('检验中 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已批准 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('批准闭环率 25%', { exact: true })).toBeVisible();
    await pause(page, 3000);

    await caption(page, '步骤 4/5 · 只读汇总不门控写操作: 已批准的 SIT-1 再次提交 -> 真实 HTTP 命中状态守卫 409');
    await mutate(page, id, `/delivery/tests/${sit1.id}/submit`, { reviewer_id: f.userId, evidence_ids: [f.evidence.id] }, 409);
    await pause(page, 1800);

    await caption(page, '步骤 5/5 · 再批准 FAT-1 + 提交并批准 FAT-2 -> 已批准3 / 检验中0 / 闭环率75% / FAT 批准率100% (SAT 仍草稿封顶)');
    await f.command(`/delivery/tests/${fat1.id}/decision`, { decision: 'approved', reason: '独立签核' }, f.reviewer);
    await f.command(`/delivery/tests/${fat2.id}/submit`, { reviewer_id: f.userId, evidence_ids: [f.evidence.id] });
    await f.command(`/delivery/tests/${fat2.id}/decision`, { decision: 'approved', reason: '补交后签核' }, f.reviewer);
    await open(page, id, '工程交付', '质量试验');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('已批准 3', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('检验中 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('批准闭环率 75%', { exact: true })).toBeVisible();
    await pause(page, 3000);

    const te = (await api(page, 'GET', base(id) + '/delivery')).test_execution;
    expect(te).toMatchObject({ total: 4, draft: 1, ready: 0, 'in-review': 0, approved: 3, rejected: 0, open: 1, 'closure-pct': 75 });

    await caption(page, '演示结束 · E02/E03 试验执行闭环只读汇总 (免迁移 / 无门控 / 无新命令 / 无新路由)');
    await pause(page, 2200);

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
