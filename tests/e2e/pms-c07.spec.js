const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C07 会议行动完成与独立核验: 逾期标记 + 提交完成(证据+独立审批人) + 审批人独立核验关闭, 双真实浏览器上下文.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c07');
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

async function mutate(page, id, suffix, data = {}) {
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

async function actionRow(page, id, aid) {
  const gov = await api(page, 'GET', base(id) + '/governance');
  return gov.actions.find(a => a.id === aid);
}

test.describe('C07 会议行动完成与独立核验浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('逾期行动 -> 提交完成(证据+独立审批人) -> 审批人独立核验关闭', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    // 合成一个只读审批角色和独立审批用户, 不改动任何系统内置账号.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `行动核验${suffix}`, role_key: `act_rev_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `act_rev_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const reviewerName = `act_${suffix}`;
    const reviewerPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: reviewerName, nick_name: '行动独立核验人', password: reviewerPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C07 E2E合成核验人' });
    const reviewerId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === reviewerName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C07-${suffix}`, name: `会议行动闭环验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: reviewerId, role: 'viewer' });

    // 证据文档版本 + 会议纪要 + 到期日为过去(逾期)的行动, 责任人设为 admin(提交人).
    const evidence = (await mutate(page, id, '/governance/documents',
      { code: `ACT-${suffix}`, title: '接线图实测记录', filename: 'wiring.txt', content: '实测记录: 线序核对通过, 绝缘阻值合格, 现场照片存档.' })).result;
    const meeting = (await mutate(page, id, '/governance/meetings',
      { title: '周例会', held_on: '2026-09-10', minutes: '决定安排专人整改现场接线并留档核验.', attendee_ids: [adminId] })).result;
    const action = (await mutate(page, id, `/governance/meetings/${meeting.id}/actions`,
      { title: '补齐接线图', owner_id: adminId, due_date: '2026-09-01' })).result;
    const aid = action.id;
    expect(action.status).toBe('open');

    // 截图1: admin 在治理 -> 会议行动页签看到逾期未关闭的行动带"已逾期"标记和"提交完成"入口.
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, '补齐接线图')).toBeVisible();
    await expect(row(page, '补齐接线图').getByText('已逾期')).toBeVisible();
    await expect(row(page, '补齐接线图').getByRole('button', { name: '提交完成', exact: true })).toBeVisible();
    await shot(page, 'c07-1-overdue-open.png');

    // admin UI 提交完成: 填写结果说明, 选择证据版本和独立审批人.
    await row(page, '补齐接线图').getByRole('button', { name: '提交完成', exact: true }).click();
    const form = modal(page, '提交行动完成');
    await form.locator('#result').fill('已按规范补全接线图, 附实测记录.');
    await choose(page, form, 'evidence_ids', `ACT-${suffix}`);
    await choose(page, form, 'reviewer_id', reviewerName);
    await save(page, '提交行动完成');

    // 截图2: 提交后进入待审核(in_review), admin 非核验人故无批准按钮, 逾期标记仍在(未关闭).
    const submitted = (await actionRow(page, id, aid));
    expect(submitted.status).toBe('in_review');
    expect(submitted.reviewer_id).toBe(reviewerId);
    expect(submitted.submitted_by).toBe(adminId);
    expect(submitted.action_overdue, '未关闭前仍应逾期').toBe(true);
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, '补齐接线图').getByRole('button', { name: '批准关闭' })).toHaveCount(0);
    await shot(page, 'c07-2-in-review.png');

    // 独立审批人第二浏览器上下文登录, 亲自核验通过并关闭.
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const reviewer = await context.newPage();
    reviewer.on('pageerror', error => errors.push(error.message));
    await login(reviewer, reviewerName, reviewerPwd);
    await open(reviewer, id, '需求与治理', '会议行动');
    // 截图3: 核验人视角出现"批准关闭"入口.
    await expect(row(reviewer, '补齐接线图').getByRole('button', { name: '批准关闭', exact: true })).toBeVisible();
    await shot(reviewer, 'c07-3-reviewer-approve.png');
    await row(reviewer, '补齐接线图').getByRole('button', { name: '批准关闭', exact: true }).click();
    const decForm = modal(reviewer, '核验通过并关闭行动');
    await decForm.locator('#reason').fill('已独立核对实测记录与证据版本, 同意关闭行动.');
    await save(reviewer, '核验通过并关闭行动');

    // 核验后关闭, 逾期标记消除.
    const closed = await actionRow(page, id, aid);
    expect(closed.status).toBe('closed');
    expect(closed.verified_by).toBe(reviewerId);
    expect(closed.action_overdue, '关闭后不再逾期').toBe(false);
    await open(reviewer, id, '需求与治理', '会议行动');
    await expect(row(reviewer, '补齐接线图').getByText('已逾期')).toHaveCount(0);
    // 截图4: 关闭后带完成说明且无逾期标记.
    await shot(reviewer, 'c07-4-closed.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
    await context.close();
  });
});
