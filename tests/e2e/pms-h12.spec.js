const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H12 承诺成本 + 预算控制 浏览器验收:
//  admin 建立"已批准预算基线"(经第二审批人独立批准) -> 登记承诺草稿 -> 界面提交命中系统默认阻断规则(真实门控 409) ->
//  强制放行提交(override) -> 审批人上下文独立批准 -> 部分转实付(剩余守恒) -> 预算占用评估面板可见 ->
//  项目层新增预算控制规则在规则台账以"项目层"标签可见. 全程真实浏览器点击 + 真实 HTTP 门控.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h12');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
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

// 命令响应信封: 记录在 data.result, 聚合版本在 data.project_version (用于下一次乐观锁).
async function command(page, method, url, data, expected = 200) {
  const d = await api(page, method, url, data, expected);
  return { result: d.result, version: d.project_version };
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
  await page.waitForLoadState('networkidle');
}

async function save(page, title) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body.data;
}

// 预期失败的提交(命中门控): 断言返回码, 弹窗保持打开, 再点取消关闭.
// 传入 shotFile 时, 在弹窗仍打开且错误提示可见的瞬间抓拍, 真实呈现门控失败.
async function saveExpectCode(page, title, code, shotFile) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: 期望 ${code}`).toBe(code);
  await expect(form).toBeVisible();
  if (shotFile) {
    await expect(form).toContainText(/阻断|120%/, { timeout: 5000 });
    await page.screenshot({ path: path.join(output, shotFile), animations: 'disabled' });
  }
  await form.getByRole('button', { name: /返\s*回/ }).click();
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body;
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  // 等待瞬时提示(含成功/失败 toast)自动消散, 避免把 antd 消息拍进定妆截图.
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0, { timeout: 8000 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('H12 承诺成本与预算控制浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('登记 -> 提交命中阻断(409) -> 强制放行 -> 独立批准 -> 部分转实付 -> 占用面板与项目层规则可见', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    // 1) 合成一个只读+财务审批角色与独立审批人(不改动任何系统内置账号).
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:finance:query', 'pms:finance:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `财务承诺审核${suffix}`, role_key: `fin_com_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `fin_com_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const reviewerName = `fc_${suffix}`;
    const reviewerPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: reviewerName, nick_name: '财务承诺审核人', password: reviewerPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'H12 E2E合成审批人' });
    const reviewerId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === reviewerName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H12-${suffix}`, name: `承诺预算验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: reviewerId, role: 'viewer' });

    // 第二浏览器上下文: 独立审批人亲自核验并批准.
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const reviewer = await context.newPage();
    reviewer.on('pageerror', error => errors.push(error.message));
    await login(reviewer, reviewerName, reviewerPwd);

    // 2) admin 建立"已批准预算基线"=1000.00 CNY (经审批人独立批准), 使承诺评估可比.
    let v = (await api(page, 'GET', base(id))).version;
    const created = await command(page, 'POST', base(id) + '/cost-versions',
      { version: v, kind: 'budget', period: '2026-09', currency: 'CNY', name: `预算基线-${suffix}`, revenue: '0', reviewer_id: reviewerId });
    const budgetId = created.result.id; v = created.version;
    const entryd = await command(page, 'POST', `${base(id)}/cost-versions/${budgetId}/entries`,
      { version: v, category: 'material', label: '设备采购预算', amount: '1000.00', source_ref: 'BUDGET-BASE' });
    v = entryd.version;
    const subd = await command(page, 'POST', `${base(id)}/cost-versions/${budgetId}/submit`, { version: v });
    v = subd.version;
    // 审批人独立批准预算基线.
    const rv = (await api(reviewer, 'GET', base(id))).version;
    await command(reviewer, 'POST', `${base(id)}/cost-versions/${budgetId}/review`, { version: rv, decision: 'approved', reason: '预算基线合理, 批准.' });

    // 3) admin 界面登记承诺草稿: 结算总额 1200.00 CNY (将达 120% 占用, 触发系统默认阻断规则).
    const code = `CMT-${suffix}`;
    await open(page, id, '项目费用', '承诺与预算控制');
    // 预算基线批准后, 占用评估面板显示可比状态.
    await expect(drawer(page).getByText('已批准预算 CNY 1000.00', { exact: true })).toBeVisible();
    await drawer(page).getByRole('button', { name: '登记承诺', exact: true }).click();
    let form = modal(page, '登记承诺');
    await form.locator('#code').fill(code);
    await choose(page, form, 'kind', '采购');
    await form.locator('#supplier').fill('华东设备供应商');
    await choose(page, form, 'currency', 'CNY');
    await form.locator('#gross').fill('1200.00');
    await choose(page, form, 'base_currency', 'CNY');
    await form.locator('#exchange_rate').fill('1');
    await form.locator('#description').fill('产线关键设备采购承诺, 验收后转实付.');
    await choose(page, form, 'reviewer_id', reviewerName);
    const c1 = (await save(page, '登记承诺')).result;
    expect(c1.status, '新登记承诺为草稿').toBe('draft');
    expect(c1.base, '本位金额规范化回显').toBe('1200.00');
    await expect(row(page, code)).toBeVisible();
    await expect(row(page, code).getByText('草稿', { exact: true })).toBeVisible();
    await shot(page, 'h12-1-commitment-draft.png');

    // 4) 界面提交 -> 命中系统默认"阻断@100%" -> 真实门控 409, 弹窗不关闭.
    await row(page, code).getByRole('button', { name: '提交', exact: true }).click();
    form = modal(page, '提交承诺');
    await choose(page, form, 'baseline', '预算');
    await form.locator('#reason').fill('常规提交, 预期被预算阻断.');
    const blocked = await saveExpectCode(page, '提交承诺', 409, 'h12-2-blocked-409.png');
    expect(blocked.msg, '阻断理由包含占用率').toMatch(/120%|阻断/);

    // 5) 强制放行提交(override_block): 记录理由, 提交成功进入待审批.
    await row(page, code).getByRole('button', { name: '强制放行', exact: true }).click();
    form = modal(page, '强制放行提交');
    await choose(page, form, 'baseline', '预算');
    await form.locator('#reason').fill('总经理特批: 战略设备锁定产能, 超预算放行.');
    await save(page, '强制放行提交');
    await open(page, id, '项目费用', '承诺与预算控制');
    await expect(row(page, code).getByText('待审批', { exact: true })).toBeVisible();
    // 提交后占用率进入 120%, 面板显示"触发阻断"决策与触发规则标签.
    await expect(drawer(page).getByText('已承诺 1200.00', { exact: true })).toBeVisible();
    await expect(drawer(page).getByText('占用率 120% · 触发阻断', { exact: true })).toBeVisible();
    // admin 非指定审批人, 不出现批准入口.
    await expect(row(page, code).getByRole('button', { name: '批准', exact: true })).toHaveCount(0);
    await shot(page, 'h12-3-override-submitted-panel.png');

    // 6) 独立审批人第二上下文批准承诺 -> 已批准.
    await open(reviewer, id, '项目费用', '承诺与预算控制');
    await expect(row(reviewer, code).getByRole('button', { name: '批准', exact: true })).toBeVisible();
    await row(reviewer, code).getByRole('button', { name: '批准', exact: true }).click();
    form = modal(reviewer, '批准承诺');
    await form.locator('#reason').fill('已独立核对采购合同与放行理由, 同意.');
    await save(reviewer, '批准承诺');
    await open(reviewer, id, '项目费用', '承诺与预算控制');
    await expect(row(reviewer, code).getByText('已批准', { exact: true })).toBeVisible();
    await shot(reviewer, 'h12-4-reviewer-approved.png');

    // 7) admin 部分转实付 700.00 (本位) -> 剩余 500.00, 状态保持已批准, 占用仍计入.
    await open(page, id, '项目费用', '承诺与预算控制');
    await expect(row(page, code).getByRole('button', { name: '转实付', exact: true })).toBeVisible();
    await row(page, code).getByRole('button', { name: '转实付', exact: true }).click();
    form = modal(page, '转实付');
    await form.locator('#amount').fill('700.00');
    const rel = (await save(page, '转实付')).result;
    expect(rel.status, '部分释放仍为已批准').toBe('approved');
    expect(rel.released, '已释放=700.00').toBe('700.00');
    expect(rel.remaining, '剩余=500.00').toBe('500.00');
    await open(page, id, '项目费用', '承诺与预算控制');
    await expect(row(page, code).getByText('已批准', { exact: true })).toBeVisible();
    await shot(page, 'h12-5-partial-release.png');

    // 8) 项目层新增预算控制规则 -> 规则台账以"项目层"标签可见, 与系统默认并存.
    await drawer(page).getByRole('button', { name: '新增项目规则', exact: true }).click();
    form = modal(page, '新增项目预算控制规则');
    await choose(page, form, 'baseline', '预算');
    await form.locator('#threshold_pct').fill('95');
    await choose(page, form, 'action', '需上级审批');
    await form.locator('#note').fill('项目层: 预算占用达95%需上级审批.');
    const rule = (await save(page, '新增项目预算控制规则')).result;
    expect(rule.project_id, '项目层规则绑定本项目').toBeTruthy();
    await open(page, id, '项目费用', '承诺与预算控制');
    // 同时可见: 系统默认(预算 100 阻断) 与新增项目层(95 需上级审批).
    await expect(row(page, '需上级审批').first()).toBeVisible();
    await expect(drawer(page).getByText('项目层', { exact: true }).first()).toBeVisible();
    await expect(drawer(page).getByText('系统默认', { exact: true }).first()).toBeVisible();
    await shot(page, 'h12-6-budget-rules-visible.png');

    // 9) 真实 HTTP 回显: 承诺状态/释放/占用一致, 阻断不可绕过独立审批人.
    const fin = await api(page, 'GET', base(id) + '/finance');
    const cmt = fin.commitments.find(c => c.id === c1.id);
    expect(cmt.status).toBe('approved');
    expect(cmt.released).toBe('700.00');
    expect(cmt.control_note, 'control_note 记录了放行备注').toMatch(/放行|特批|战略/);
    expect(fin.budget_control.budget.comparable, '预算可比').toBeTruthy();
    expect(fin.budget_control.budget.consumed_minor).toBe(120000);

    await context.close();
    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
