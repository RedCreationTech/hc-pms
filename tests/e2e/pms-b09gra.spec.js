const { test, expect } = require('@playwright/test');
const path = require('node:path');

// B09 延伸: Gate 未通过的必需检查项"落实整改行动"(写命令, 复用行动类型与既有完成/转任务生命周期, 免迁移, payload 存 source_gate_id/source_check_code)
//   + Gate 台账"整改情况"只读派生列 + 关口验收签核闭环汇总"已落实整改/整改未完成"计数 + 门控.
// 真实HTTP建含2必需+1可选检查的关口模板 -> 从模板发起关口实例(全未通过 -> 草稿, gate_required_missing=2) ->
//   界面"落实整改"按钮打开对话框 -> 指定 R-2 + 责任人 admin + 到期日 -> 保存生成一条开放整改行动(来源关口 + source_check_code R-2) ->
//   重载台账"整改情况"列金色"整改中", 汇总"已落实整改 1"/"整改未完成 1";
//   真实HTTP把该整改行动转为WBS任务(converted=完成) -> 重载台账"整改完成", 汇总"整改未完成"消失(remediation-open 0);
//   真实HTTP读模型回显: gate.gate_remediation_total/state + action.source_gate_id/source_check_code + gate_closure.remediated/remediation-open, 与界面一致;
//   防御性门控(真实HTTP): 无未通过必需项的关口 -> 409, 非法 check_code -> 400, 缺到期日 -> 400, 跨项目关口 -> 404 (写入前抛出不产生记录).
// 全程真实点击 + 真实HTTP + 真实浏览器截图, 免迁移写路径 + 只读派生均界面可见.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/b09gra');
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

// 命令写操作: 拉取当前项目版本, 带版本发命令. 返回命令结果记录 (data.result).
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
  await api(page, 'POST', '/api/system/role', { role_name: `关口审核${suffix}`, role_key: `brmrev_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `brmrev_${suffix}`).role_id;
  const name = `brm_rev_${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: name, nick_name: '整改落实审核人', password: `E2e!${suffix}`,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'B09 关口整改落实 E2E 合成审核人' });
  return (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id;
}

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('B09 延伸 关口检查不通过落实整改行动浏览器验收', () => {
  test.setTimeout(180000);

  test('未通过必需项 -> 界面落实整改 -> 台账整改情况列与汇总计数 -> 转任务完成 -> 门控', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `BGR-${suffix}`, name: `关口整改落实验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    const reviewerId = await reviewerUser(page, suffix, deptId);
    await api(page, 'POST', base(id) + '/members', { user_id: reviewerId, role: 'editor' });

    // 真实HTTP建含 2 必需 + 1 可选检查的关口模板, 再从模板发起关口实例 (全未通过 -> 草稿).
    // 注意: 命令写响应 data.result 是【原始存储记录】, 不含只读派生键 (gate_required_missing 仅在 /governance 读模型里算),
    // 故须回读工作区并按 id 找到该关口再断言派生键.
    const template = await command(page, id, '/gate-templates', {
      code: `BGR-${suffix}`, title: `整改落实模板-${suffix}`, stage: 'execution', required: true,
      checks: [{ code: 'R-1', title: '首件检验', required: true },
        { code: 'R-2', title: '绝缘耐压', required: true },
        { code: 'O-1', title: '附加归档', required: false }] });
    const gateTitle = `B09整改主评审-${suffix}`;
    const gate = await command(page, id, '/gates', { template_id: template.id, title: gateTitle, reviewer_id: reviewerId });
    const gateAfterCreate = (await api(page, 'GET', base(id) + '/governance')).gates.find(x => x.id === gate.id);
    expect(gateAfterCreate.gate_required_missing, '初始两个必需项均未通过').toBe(2);

    // 防御性门控 (真实HTTP, 写入前抛出 -> 不产生记录):
    // (1) 无未通过必需项的关口 (非必需模板, 仅可选项, 仍草稿) -> 409.
    const naTemplate = await command(page, id, '/gate-templates', {
      code: `BGN-${suffix}`, title: `无必需模板-${suffix}`, stage: 'design', required: false,
      checks: [{ code: 'O', title: '仅可选', required: false }] });
    const naGate = await command(page, id, '/gates', { template_id: naTemplate.id, title: `无必需关口-${suffix}`, reviewer_id: reviewerId });
    await command(page, id, `/gates/${naGate.id}/remediation-action`, { due_date: '2026-12-01' }, 409);
    // (2) 非法 check_code (可选/不存在) -> 400.
    await command(page, id, `/gates/${gate.id}/remediation-action`, { check_code: 'ZZZ', due_date: '2026-12-01' }, 400);
    // (3) 缺必填到期日 -> 400.
    await command(page, id, `/gates/${gate.id}/remediation-action`, { title: '缺期整改' }, 400);
    // (4) 跨项目关口 -> 404.
    const otherProject = await api(page, 'POST', '/api/pms/projects', { project_no: `BGRO-${suffix}`, name: `他项目-${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    // reviewer! 校验评审人对目标项目有读取资格, 故须先把同一评审人加入他项目成员, 否则建关口即 403.
    await api(page, 'POST', base(otherProject.project_id) + '/members', { user_id: reviewerId, role: 'editor' });
    const otherTemplate = await command(page, otherProject.project_id, '/gate-templates', {
      code: `BGOT-${suffix}`, title: `他项目模板-${suffix}`, stage: 'execution', required: true,
      checks: [{ code: 'R', title: '必需', required: true }] });
    const otherGate = await command(page, otherProject.project_id, '/gates', { template_id: otherTemplate.id, title: `他项目关口-${suffix}`, reviewer_id: reviewerId });
    await command(page, id, `/gates/${otherGate.id}/remediation-action`, { due_date: '2026-12-01' }, 404);

    // 界面第一轮: 打开 Gate 台账 -> 点击"落实整改" -> 指定 R-2 + admin + 到期日 -> 保存.
    await open(page, id, '需求与治理', 'Gate评审');
    const ledger = panel(page, 'Gate检查与评审');
    await expect(ledger.getByRole('columnheader', { name: '整改情况', exact: true })).toBeVisible();
    const gateRow = row(page, gateTitle);
    // 未落实整改时"整改情况"列显示红色"待落实整改" (有未通过必需项).
    await expect(gateRow.getByText('待落实整改', { exact: true })).toBeVisible();
    await gateRow.getByRole('button', { name: /落\s*实\s*整\s*改/ }).click();
    const form = modal(page, '落实整改行动');
    await choose(page, form, 'check_code', 'R-2');
    await fill(form, { title: `补做绝缘耐压复测-${suffix}` });
    await choose(page, form, 'owner_id', 'admin');
    await fill(form, { due_date: '2026-12-01' });
    await shot(page, 'b09gra-1-dialog.png');
    const action = (await save(page, '落实整改行动')).data.result;
    expect(action.source_gate_id, '整改行动记录来源关口').toBe(gate.id);
    expect(action.source_check_code, '整改行动绑定所选必需检查项 R-2').toBe('R-2');
    expect(action.status, '整改行动新建为开放项').toBe('open');

    // 重载: "整改情况"列金色"整改中"; 汇总"已落实整改 1"/"整改未完成 1".
    await open(page, id, '需求与治理', 'Gate评审');
    const ledger2 = panel(page, 'Gate检查与评审');
    const gateRow2 = row(page, gateTitle);
    await expect(gateRow2.getByText(/整改中/)).toBeVisible();
    await shotCard(page, ledger2, 'b09gra-2-ledger-inprogress.png');
    const sum = panel(page, '关口验收签核闭环汇总');
    await expect(sum.getByText(/已落实整改\s*1/)).toBeVisible();
    await expect(sum.getByText(/整改未完成\s*1/)).toBeVisible();
    await shotCard(page, sum, 'b09gra-3-summary-open.png');

    // 真实HTTP读模型回显, 与界面一致.
    let ws = await api(page, 'GET', base(id) + '/governance');
    let g1 = ws.gates.find(x => x.id === gate.id);
    expect(g1.gate_remediation_total, '已落实整改总数 1').toBe(1);
    expect(g1.gate_remediation_open, '未完成整改 1').toBe(1);
    expect(g1.gate_remediation_state, '状态 in-progress').toBe('in-progress');
    let act = ws.actions.find(x => x.id === action.id);
    expect(act.source_gate_id, 'HTTP 回显来源关口').toBe(gate.id);
    expect(act.source_check_code, 'HTTP 回显检查项编码').toBe('R-2');
    expect(ws.gate_closure.remediated, '汇总已落实整改 1').toBe(1);
    expect(ws.gate_closure['remediation-open'], '汇总整改未完成 1').toBe(1);

    // 真实HTTP把整改行动转为WBS任务(converted=完成) -> 重载台账"整改完成", 汇总"整改未完成"消失.
    await command(page, id, `/actions/${action.id}/task`, { start_date: '2026-09-23', duration_days: 2 });
    await open(page, id, '需求与治理', 'Gate评审');
    const ledger3 = panel(page, 'Gate检查与评审');
    const gateRow3 = row(page, gateTitle);
    await expect(gateRow3.getByText(/整改完成\s*1\/1/)).toBeVisible();
    await shotCard(page, ledger3, 'b09gra-4-ledger-completed.png');
    const sum3 = panel(page, '关口验收签核闭环汇总');
    await expect(sum3.getByText(/已落实整改\s*1/)).toBeVisible();
    await expect(sum3.getByText(/整改未完成/)).toHaveCount(0);

    ws = await api(page, 'GET', base(id) + '/governance');
    g1 = ws.gates.find(x => x.id === gate.id);
    expect(g1.gate_remediation_state, '转任务后 completed').toBe('completed');
    expect(g1.gate_remediation_open, '转任务后 open 归 0').toBe(0);
    expect(ws.gate_closure['remediation-open'], '完成后整改未完成 0').toBe(0);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
