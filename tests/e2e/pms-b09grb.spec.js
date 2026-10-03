const { test, expect } = require('@playwright/test');
const path = require('node:path');

// B09 延伸b: Gate/DQ 未通过必需检查项"全部落实"批量整改行动 (写命令 remediation-actions!, 单条 remediation-action! 的批量孪生).
//   一次调用为【每条】未通过必需检查项各生成一条独立 open 行动 (逐条记录各自 source_check_code), body 白名单仅 [:owner_id :due_date],
//   缺省负责人沿用关口审批人, 命令返回【行动记录数组】(data.result 为向量), 免迁移 (payload 存 source_gate_id/source_check_code).
// 真实HTTP建含2必需+1可选检查的关口模板 -> 从模板发起关口实例(全未通过 -> 草稿, gate_required_missing=2) ->
//   界面"全部落实"按钮打开对话框(仅负责人+到期日两个字段) -> 保存返回 2 条独立开放整改行动(R-1/R-2, 共用负责人/到期日, 均指向同一关口) ->
//   重载台账"整改中 0/2", 汇总"已落实整改 1"/"整改未完成 1"; 读模型回显 gate_remediation_total=2/open=2/in-progress + gate_closure.remediated/remediation-open;
//   真实HTTP逐条把两条整改行动转为WBS任务 -> 台账"整改完成 2/2", 汇总"整改未完成"消失(remediation-open 0), state completed;
//   另用真实HTTP验证 DQ 侧批量写命令同样生成多条 (source_dq_id + 各自 source_check_code + dq_remediation_total);
//   防御性门控(真实HTTP): 无未通过必需项的关口 -> 409, 非法 owner(非成员) -> 400, 多余键 title -> 400, 缺到期日 -> 400, 跨项目关口 -> 404, 批量后关口状态仍为 draft.
// 全程真实点击 + 真实HTTP + 真实浏览器截图, 批量写路径 + 只读派生均界面可见.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/b09grb');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;

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

// 命令写操作: 拉取当前项目版本, 带版本发命令. 返回命令结果 (批量为 data.result 数组).
async function command(page, id, suffix, data = {}, expected = 200) {
  const project = await api(page, 'GET', base(id));
  const result = await api(page, 'POST', base(id) + '/governance' + suffix, { ...data, version: project.version }, expected);
  return result && result.result;
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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function shotCard(page, card, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 合成一名具备质量审批权限且加入项目的独立审核人 (关口审核人不得为登记人 admin).
async function reviewerUser(page, suffix, deptId) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `整改批量审核${suffix}`, role_key: `brbrev_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `brbrev_${suffix}`).role_id;
  const name = `brb_rev_${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: name, nick_name: '批量整改审核人', password: `E2e!${suffix}`,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'B09 关口批量整改 E2E 合成审核人' });
  return (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id;
}

// 合成一名存在且启用但未加入任何本项目的普通用户, 用于验证批量整改非法负责人 (k/user! -> 400).
async function outsiderUser(page, suffix, deptId) {
  const name = `brb_out_${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: name, nick_name: '项目外用户', password: `E2e!${suffix}`,
    dept_id: deptId, roles: [], posts: [], status: '0', remark: 'B09 批量整改越权负责人 E2E 合成' });
  return (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id;
}

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('B09 延伸b 关口/DQ 全部落实批量整改浏览器验收', () => {
  test.setTimeout(180000);

  test('未过必需项 -> 界面全部落实生成多条 -> 台账与汇总计数 -> 逐条转任务完成 -> DQ 批量 -> 门控', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `BGRB-${suffix}`, name: `关口批量整改验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    const reviewerId = await reviewerUser(page, suffix, deptId);
    await api(page, 'POST', base(id) + '/members', { user_id: reviewerId, role: 'editor' });
    const outsiderId = await outsiderUser(page, suffix, deptId);

    // 真实HTTP建含 2 必需 + 1 可选检查的关口模板, 再从模板发起关口实例 (全未通过 -> 草稿).
    const template = await command(page, id, '/gate-templates', {
      code: `BGRB-${suffix}`, title: `批量整改模板-${suffix}`, stage: 'execution', required: true,
      checks: [{ code: 'R-1', title: '首件检验', required: true },
        { code: 'R-2', title: '绝缘耐压', required: true },
        { code: 'O-1', title: '附加归档', required: false }] });
    const gateTitle = `B09b批量主评审-${suffix}`;
    const gate = await command(page, id, '/gates', { template_id: template.id, title: gateTitle, reviewer_id: reviewerId });
    const gateAfterCreate = (await api(page, 'GET', base(id) + '/governance')).gates.find(x => x.id === gate.id);
    expect(gateAfterCreate.gate_required_missing, '初始两个必需项均未通过').toBe(2);

    // 防御性门控 (真实HTTP, 写入前抛出 -> 不产生记录):
    // (1) 无未通过必需项的关口 (非必需模板, 仅可选项) -> 409.
    const naTemplate = await command(page, id, '/gate-templates', {
      code: `BGRBN-${suffix}`, title: `无必需模板-${suffix}`, stage: 'design', required: false,
      checks: [{ code: 'O', title: '仅可选', required: false }] });
    const naGate = await command(page, id, '/gates', { template_id: naTemplate.id, title: `无必需关口-${suffix}`, reviewer_id: reviewerId });
    await command(page, id, `/gates/${naGate.id}/remediation-actions`, { due_date: '2026-12-01' }, 409);
    // (2) 非法负责人 (存在启用但非本项目成员) -> 400.
    await command(page, id, `/gates/${gate.id}/remediation-actions`, { owner_id: outsiderId, due_date: '2026-12-01' }, 400);
    // (3) 多余键 title (批量白名单仅 owner_id/due_date) -> 400.
    await command(page, id, `/gates/${gate.id}/remediation-actions`, { title: '多余', due_date: '2026-12-01' }, 400);
    // (4) 缺必填到期日 -> 400.
    await command(page, id, `/gates/${gate.id}/remediation-actions`, { owner_id: adminId }, 400);
    // (5) 跨项目关口 -> 404.
    const otherProject = await api(page, 'POST', '/api/pms/projects', { project_no: `BGROB-${suffix}`, name: `他项目-${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    // reviewer! 校验评审人对目标项目有读取资格, 故先把同一评审人加入他项目成员, 否则建关口即 403.
    await api(page, 'POST', base(otherProject.project_id) + '/members', { user_id: reviewerId, role: 'editor' });
    const otherTemplate = await command(page, otherProject.project_id, '/gate-templates', {
      code: `BGROBT-${suffix}`, title: `他项目模板-${suffix}`, stage: 'execution', required: true,
      checks: [{ code: 'R', title: '必需', required: true }] });
    const otherGate = await command(page, otherProject.project_id, '/gates', { template_id: otherTemplate.id, title: `他项目关口-${suffix}`, reviewer_id: reviewerId });
    await command(page, id, `/gates/${otherGate.id}/remediation-actions`, { due_date: '2026-12-01' }, 404);

    // 界面第一轮: 打开 Gate 台账 -> 点击"全部落实" -> 批量对话框仅负责人+到期日 -> 保存生成 2 条独立整改行动.
    await open(page, id, '需求与治理', 'Gate评审');
    const ledger = panel(page, 'Gate检查与评审');
    await expect(ledger.getByRole('columnheader', { name: '整改情况', exact: true })).toBeVisible();
    const gateRow = row(page, gateTitle);
    // 未落实整改时"整改情况"列显示红色"待落实整改".
    await expect(gateRow.getByText('待落实整改', { exact: true })).toBeVisible();
    await gateRow.getByRole('button', { name: /全\s*部\s*落\s*实/ }).click();
    const form = modal(page, '全部落实整改行动');
    // 批量对话框不应包含"整改检查项"或"整改内容"字段 (覆盖全部未过必需项, 标题自动生成).
    await expect(form.locator('#check_code')).toHaveCount(0);
    await expect(form.locator('#title')).toHaveCount(0);
    await choose(page, form, 'owner_id', 'admin');
    await fill(form, { due_date: '2026-12-01' });
    await shot(page, 'b09grb-1-dialog.png');
    const batchBody = await save(page, '全部落实整改行动');
    const acts = batchBody.data.result;
    expect(Array.isArray(acts), '批量命令 data.result 是行动数组').toBe(true);
    expect(acts.length, '两条未过必需项各生成一条独立行动').toBe(2);
    expect(new Set(acts.map(a => a.source_check_code)), '逐条记录各自检查项编码').toEqual(new Set(['R-1', 'R-2']));
    expect(acts.every(a => a.source_gate_id === gate.id), '两条均指向同一关口').toBe(true);
    expect(acts.every(a => a.status === 'open'), '两条均新建为开放项').toBe(true);
    expect(new Set(acts.map(a => a.owner_id)), '两条共用所选负责人').toEqual(new Set([adminId]));
    expect(new Set(acts.map(a => a.due_date)), '两条共用同一到期日').toEqual(new Set(['2026-12-01']));

    // 重载: "整改情况"列金色"整改中 0/2"; 汇总"已落实整改 1"/"整改未完成 1".
    await open(page, id, '需求与治理', 'Gate评审');
    const ledger2 = panel(page, 'Gate检查与评审');
    const gateRow2 = row(page, gateTitle);
    await expect(gateRow2.getByText(/整改中\s*0\/2/)).toBeVisible();
    await shotCard(page, ledger2, 'b09grb-2-ledger-inprogress.png');
    const sum = panel(page, '关口验收签核闭环汇总');
    await expect(sum.getByText(/已落实整改\s*1/)).toBeVisible();
    await expect(sum.getByText(/整改未完成\s*1/)).toBeVisible();
    await shotCard(page, sum, 'b09grb-3-summary-open.png');

    // 真实HTTP读模型回显, 与界面一致.
    let ws = await api(page, 'GET', base(id) + '/governance');
    let g1 = ws.gates.find(x => x.id === gate.id);
    expect(g1.gate_remediation_total, '批量整改总数 2').toBe(2);
    expect(g1.gate_remediation_open, '未完成整改 2').toBe(2);
    expect(g1.gate_remediation_state, '状态 in-progress').toBe('in-progress');
    expect(ws.gate_closure.remediated, '汇总已落实整改关口数 1').toBe(1);
    expect(ws.gate_closure['remediation-open'], '汇总整改未完成关口数 1').toBe(1);

    // 批量后关口状态仍为草稿 (写命令不改关口状态).
    expect(g1.status, '批量整改不改关口状态').toBe('draft');

    // 真实HTTP逐条把两条整改行动转为WBS任务(完成) -> 台账"整改完成 2/2", 汇总"整改未完成"消失.
    const actIds = acts.map(a => a.id);
    await command(page, id, `/actions/${actIds[0]}/task`, { start_date: '2026-09-23', duration_days: 2 });
    await command(page, id, `/actions/${actIds[1]}/task`, { start_date: '2026-09-23', duration_days: 2 });
    await open(page, id, '需求与治理', 'Gate评审');
    const ledger3 = panel(page, 'Gate检查与评审');
    const gateRow3 = row(page, gateTitle);
    await expect(gateRow3.getByText(/整改完成\s*2\/2/)).toBeVisible();
    await shotCard(page, ledger3, 'b09grb-4-ledger-completed.png');
    const sum3 = panel(page, '关口验收签核闭环汇总');
    await expect(sum3.getByText(/已落实整改\s*1/)).toBeVisible();
    await expect(sum3.getByText(/整改未完成/)).toHaveCount(0);

    ws = await api(page, 'GET', base(id) + '/governance');
    g1 = ws.gates.find(x => x.id === gate.id);
    expect(g1.gate_remediation_state, '全部转任务后 completed').toBe('completed');
    expect(g1.gate_remediation_open, '全部转任务后 open 归 0').toBe(0);
    expect(ws.gate_closure['remediation-open'], '完成后整改未完成关口 0').toBe(0);

    // DQ 侧批量孪生 (真实HTTP): 建含 2 未过必需 + 1 可选的 DQ -> 批量 -> 多条行动各带 source_dq_id/source_check_code + dq_remediation_total.
    const dqTitle = `B09b批量DQ-${suffix}`;
    const dq = await command(page, id, '/dqs', { code: `DQ-${suffix}`, title: dqTitle, owner_id: adminId,
      checklist: [{ code: 'D-1', title: '来料检验', required: true },
        { code: 'D-2', title: '过程巡检', required: true },
        { code: 'D-O', title: '附加记录', required: false }], deliverable_ids: [] });
    const dqActs = await command(page, id, `/dqs/${dq.id}/remediation-actions`, { owner_id: adminId, due_date: '2026-12-05' });
    expect(Array.isArray(dqActs), 'DQ 批量 data.result 是数组').toBe(true);
    expect(dqActs.length, '两条未过必需项各一条行动').toBe(2);
    expect(new Set(dqActs.map(a => a.source_check_code))).toEqual(new Set(['D-1', 'D-2']));
    expect(dqActs.every(a => a.source_dq_id === dq.id), '两条均指向同一 DQ').toBe(true);
    const dqAfter = (await api(page, 'GET', base(id) + '/governance')).dqs.find(x => x.id === dq.id);
    expect(dqAfter.dq_remediation_total, 'DQ 批量整改总数 2').toBe(2);
    expect(dqAfter.dq_remediation_state, 'DQ 状态 in-progress').toBe('in-progress');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
