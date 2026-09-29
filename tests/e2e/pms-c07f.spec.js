const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C07f 会议纪要受控发布: 已登记(草稿)纪要须提交给具备质量审批权限的独立审批人核验, 批准形成已发布纪要, 驳回退回登记态可补充重提.
// 沿用文档/关口/章程的"提交-独立裁决"闭环(免迁移, 元数据随 payload 持久化). 完整闭环: admin 提交发布(in_review, 界面"发布审批中"徽标)
// -> 独立审批人(第二真实上下文)驳回(退回 recorded 草稿) -> admin 补充后重提(in_review) -> 审批人批准(已发布 approved).
// 写门控事实(自审批 403, 非指定审批人 403, 审批中不可重复提交 409, 已发布不可重提 409)由后端 SQLite 用例覆盖;
// 浏览器侧核验界面可见的按钮/徽标/状态翻转与服务端回显.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c07f');
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

// 抽屉内靠下的会议纪要台账需先滚动进视口再截, 否则截图漏掉台账.
async function rowShot(page, text, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const target = row(page, text);
  await target.scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function meetingRow(page, id, mid) {
  const gov = await api(page, 'GET', base(id) + '/governance');
  return gov.meetings.find(m => m.id === mid);
}

// admin 对指定纪要点击"提交发布", 在对话框选择独立审批人并保存.
async function submitRelease(page, meeting, reviewerName) {
  await row(page, meeting.title).getByRole('button', { name: '提交发布', exact: true }).click();
  const form = modal(page, '提交会议纪要发布审批');
  await choose(page, form, 'reviewer_id', reviewerName);
  await save(page, '提交会议纪要发布审批');
}

// 审批人在对话框填写裁决意见并保存.
async function decideRelease(page, meeting, buttonName, dialogTitle, reason) {
  await row(page, meeting.title).getByRole('button', { name: buttonName, exact: true }).click();
  const form = modal(page, dialogTitle);
  await form.locator('#reason').fill(reason);
  await save(page, dialogTitle);
}

test.describe('C07f 会议纪要受控发布浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('提交发布 -> 独立审批驳回 -> 补充重提 -> 批准发布, 徽标与状态界面可见并服务端回显', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    // 合成独立审批人(含 pms:quality:approve 的最小角色 + 第二用户), 不改内置账号.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `纪要发布审批${suffix}`, role_key: `mrel_ap_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `mrel_ap_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const approverName = `mrel_${suffix}`;
    const approverPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '纪要发布独立审批人', password: approverPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C07f发布E2E合成审批人' });
    const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C07F-${suffix}`, name: `会议纪要受控发布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });

    const meeting = (await mutate(page, id, '/governance/meetings',
      { title: `阶段评审纪要-${suffix}`, held_on: '2026-09-12', minutes: '形成阶段结论与遗留事项清单.', attendee_ids: [adminId, approverId] })).result;
    const mid = meeting.id;

    // 阶段1: admin 提交发布 -> in_review, 台账"发布审批中"徽标界面可见.
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, meeting.title).getByRole('button', { name: '提交发布', exact: true })).toBeVisible();
    await submitRelease(page, meeting, approverName);
    const submitted = await meetingRow(page, id, mid);
    expect(submitted.status).toBe('in_review');
    expect(submitted.reviewer_id).toBe(approverId);
    expect(submitted.submitted_by).toBe(adminId);
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, meeting.title).getByText('发布审批中', { exact: true })).toBeVisible();
    await rowShot(page, meeting.title, 'c07f-1-in-review-badge.png');

    // 阶段2: 审批人驳回 -> 退回 recorded(草稿), 提交发布按钮重现.
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const approver = await context.newPage();
    approver.on('pageerror', error => errors.push(error.message));
    await login(approver, approverName, approverPwd);
    await open(approver, id, '需求与治理', '会议行动');
    await expect(row(approver, meeting.title).getByRole('button', { name: '批准发布', exact: true })).toBeVisible();
    await decideRelease(approver, meeting, '驳回', '驳回纪要发布', '缺少明确的阶段结论与责任人, 请补充后重提.');
    const rejected = await meetingRow(page, id, mid);
    expect(rejected.status).toBe('recorded');
    expect(rejected.release_decision).toBe('rejected');

    // 阶段3: admin 补充后重提 -> in_review, 审批人批准发布 -> approved(已发布).
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, meeting.title).getByRole('button', { name: '提交发布', exact: true })).toBeVisible();
    await submitRelease(page, meeting, approverName);
    expect((await meetingRow(page, id, mid)).status).toBe('in_review');

    await open(approver, id, '需求与治理', '会议行动');
    await row(approver, meeting.title).getByRole('button', { name: '批准发布', exact: true }).click();
    const approveForm = modal(approver, '正式批准会议纪要');
    await approveForm.locator('#reason').fill('纪要完整, 结论清晰, 批准正式归档发布.');
    await shot(approver, 'c07f-2-approver-release-dialog.png');
    await save(approver, '正式批准会议纪要');

    const released = await meetingRow(page, id, mid);
    expect(released.status).toBe('approved');
    expect(released.release_decision).toBe('approved');
    expect(released.released_by).toBe(approverId);

    // 服务端回显确认界面状态与存储一致: 已发布徽标可见, 提交发布按钮不再出现.
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, meeting.title).getByText('已发布', { exact: true })).toBeVisible();
    await expect(row(page, meeting.title).getByRole('button', { name: '提交发布', exact: true })).toHaveCount(0);
    await rowShot(page, meeting.title, 'c07f-3-released.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
    await context.close();
  });
});
