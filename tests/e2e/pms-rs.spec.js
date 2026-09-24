const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H08 延伸: 风险复评期间重新评分并重算超阈值升级门控.
// 界面登记 3x5=15 风险(未达阈值, 不升级) -> 用"提交复评"对话框同时提议新评分 5x5=25 并指定独立审批人 ->
// 台账"复审重评"列显示"15 → 25 待批准"(服务端回显 review_proposed_score=25, 而 score 仍为 15, escalated 仍 false) ->
// 由独立审批人(第二个真实浏览器上下文的 token)对复评作出批准决定 -> 评分落定为 25 且自动重算触发升级门控
// ("待升级确认 / steering", 复审重评列清空为"—"). 批准后的进一步确认缓解处置沿用 H08 已覆盖路径, 本用例只证明重评闭环.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/rs');
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

async function mutateData(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version });
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
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function riskRow(page, id, rid) {
  const gov = await api(page, 'GET', base(id) + '/governance');
  return gov.risks.find(r => r.id === rid);
}

test.describe('风险复评重新评分并重算升级门控浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('复评提议上调评分 -> 待批准列 -> 独立审批人批准 -> 评分落定并重触发升级门控', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    // 合成一个只读且具备质量审批权限的独立审批人, 不改动任何系统内置账号.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `复评重评审批${suffix}`, role_key: `rs_ap_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `rs_ap_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const approverName = `rsap_${suffix}`;
    const approverPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '复评重评独立审批人', password: approverPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: '复评重评 E2E合成独立审批人' });
    const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RS-${suffix}`, name: `风险复评重评验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });

    // 证据文档: 复评须附真实证据版本.
    const evidence = (await mutateData(page, id, '/governance/documents',
      { code: `RS-${suffix}`, title: '供应商产能复评记录', filename: 'capacity.txt', content: '来料产能与交付节奏复评记录已归档.' })).result;

    // 界面登记 3x5=15 风险 (低于阈值16, 登记时不触发升级).
    const title = `关键物料到货延迟-${suffix}`;
    await open(page, id, '需求与治理', '风险与问题');
    await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
    const riskForm = modal(page, '登记项目风险');
    await riskForm.locator('#title').fill(title);
    await riskForm.locator('#probability').fill('3');
    await riskForm.locator('#impact').fill('5');
    await choose(page, riskForm, 'owner_id', 'admin');
    await riskForm.locator('#mitigation').fill('例会持续跟踪并预留纠偏窗口.');
    await riskForm.locator('#due_date').fill('2026-10-10');
    const created = await save(page, '登记项目风险');
    const rid = created.result.id;
    expect(created.result.score).toBe(15);
    expect(created.result.escalated, '登记时 15 分不触发升级').toBe(false);

    // 提交复评: 复评结论=继续跟踪, 同时提议把概率/影响上调到 5x5, 指定独立审批人并附证据.
    await row(page, title).getByRole('button', { name: '提交复评', exact: true }).click();
    const reviewForm = modal(page, '提交风险复评');
    await choose(page, reviewForm, 'outcome', '继续跟踪');
    await reviewForm.locator('#review_note').fill('季度复评发现供应商产能下滑, 需上调发生概率与影响程度.');
    await reviewForm.locator('#probability').fill('5');
    await reviewForm.locator('#impact').fill('5');
    await reviewForm.locator('#next_review_date').fill('2026-11-01');
    await choose(page, reviewForm, 'evidence_ids', `RS-${suffix}`);
    await choose(page, reviewForm, 'reviewer_id', approverName);
    const submitted = await save(page, '提交风险复评');

    // 提交只是"提议", 评分未变, 升级仍未触发, 但回显提议评分 25.
    expect(submitted.result.status).toBe('in_review');
    expect(submitted.result.score, '批准前评分维持原值').toBe(15);
    expect(submitted.result.escalated, '批准前不触发升级').toBe(false);
    expect(submitted.result.review_proposed_score, '回显待批准的重评分').toBe(25);
    expect(submitted.result.review_proposed_probability).toBe(5);
    expect(submitted.result.review_proposed_impact).toBe(5);

    // 截图1: 台账"复审重评"列显示"15 → 25 待批准".
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, title).getByText('15 → 25 待批准')).toBeVisible();
    await shot(page, 'rs-1-proposed-rescore.png');

    // 独立审批人在第二个真实上下文登录并批准该复评决定(真实HTTP, 带审批人 token).
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const approver = await context.newPage();
    approver.on('pageerror', error => errors.push(error.message));
    await login(approver, approverName, approverPwd);
    const decided = await mutateData(approver, id, `/governance/risks/${rid}/decision`,
      { decision: 'approved', reason: '确认供应商产能下滑, 同意按 5x5 重评并升级处置.' });

    // 评分落定为 25, 自动重算触发升级门控(steering), 提议临时键清理.
    expect(decided.result.score, '批准后评分重算为 25').toBe(25);
    expect(decided.result.probability).toBe(5);
    expect(decided.result.impact).toBe(5);
    expect(decided.result.escalated, '重算后超阈值自动升级').toBe(true);
    expect(decided.result.escalation_state).toBe('pending');
    expect(decided.result.escalation_level).toBe('steering');
    expect(decided.result.review_proposed_score, '提议临时键已清理').toBeNull();

    // 截图2: 台账评分 25, 超阈值升级"待升级确认 / steering", 复审重评列已无待批准项.
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, title).getByText('待升级确认 / steering')).toBeVisible();
    await expect(row(page, title).getByText('15 → 25 待批准')).toHaveCount(0);
    await shot(page, 'rs-2-approved-escalated.png');

    // 服务端读模型二次确认(与界面一致).
    const row2 = await riskRow(page, id, rid);
    expect(row2.score).toBe(25);
    expect(row2.escalated).toBe(true);
    expect(row2.escalation_state).toBe('pending');
    expect(row2.review_proposed_score ?? null, '读模型已无待批准重评').toBeNull();

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
    await context.close();
  });
});
