const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C07 会议行动闭环率只读汇总面板: 治理 -> 会议行动页签顶部只读聚合面板按全部会议/预防行动统计
// 闭环情况(closed 或 converted 视为已闭环), 逾期仅计未闭环且到期日已过者; 只读派生, 不构成门控.
// 造 5 条行动覆盖五档: 转任务(converted)/核验关闭(closed)/逾期开放/逾期开放/未到期开放,
// 期望 total 5 closed 2 open 3 converted 1 overdue 2 闭环率 40%. 面板徽标界面可见, 并以 GET /governance
// 服务端回显 action_closure 二次确认. 关闭动作复用 c07 的独立核验人(第二真实浏览器上下文).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c07ac');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
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

// 面板截图: 归集面板在抽屉自身滚动容器内, 需 scrollIntoViewIfNeeded 后取元素截图.
async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function actionRow(page, id, aid) {
  const gov = await api(page, 'GET', base(id) + '/governance');
  return gov.actions.find(a => a.id === aid);
}

async function addMeetingAction(page, id, meetingId, title, due) {
  return (await mutate(page, id, `/governance/meetings/${meetingId}/actions`,
    { title, owner_id: (await api(page, 'GET', '/api/pms/options')).currentUserId, due_date: due })).result;
}

test.describe('C07 会议行动闭环率只读汇总面板浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('混合五档行动 -> 面板闭环率界面可见并经服务端二次确认', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    // 合成独立核验人(含 pms:quality:approve 的最小角色 + 第二用户), 不改内置账号.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `闭环核验${suffix}`, role_key: `cls_rev_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `cls_rev_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const reviewerName = `cls_${suffix}`;
    const reviewerPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: reviewerName, nick_name: '闭环独立核验人', password: reviewerPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C07闭环率E2E合成核验人' });
    const reviewerId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === reviewerName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C07AC-${suffix}`, name: `会议行动闭环率验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: reviewerId, role: 'viewer' });

    const evidence = (await mutate(page, id, '/governance/documents',
      { code: `CLS-${suffix}`, title: '闭环实测记录', filename: 'closure.txt', content: '实测记录: 交付物核对通过, 现场留档.' })).result;
    const meeting = (await mutate(page, id, '/governance/meetings',
      { title: '闭环评审例会', held_on: '2026-09-10', minutes: '安排专人负责整改并统一留痕核验.', attendee_ids: [adminId] })).result;

    // 五档行动: 转任务/核验关闭/逾期开放x2/未到期开放.
    const tConv = `转任务行动-${suffix}`;
    const tClosed = `核验关闭行动-${suffix}`;
    const tOverA = `逾期开放甲-${suffix}`;
    const tOverB = `逾期开放乙-${suffix}`;
    const tFuture = `未到期开放-${suffix}`;
    const aConv = await addMeetingAction(page, id, meeting.id, tConv, '2020-01-01');
    const aClosed = await addMeetingAction(page, id, meeting.id, tClosed, '2020-01-01');
    const aOverA = await addMeetingAction(page, id, meeting.id, tOverA, '2020-01-01');
    const aOverB = await addMeetingAction(page, id, meeting.id, tOverB, '2020-01-01');
    const aFuture = await addMeetingAction(page, id, meeting.id, tFuture, '2099-01-01');

    // 关闭1: admin UI 转该行动为WBS任务(converted).
    await open(page, id, '需求与治理', '会议行动');
    await row(page, tConv).getByRole('button', { name: '转为WBS任务', exact: true }).click();
    const convForm = modal(page, '会议行动转WBS任务');
    await convForm.locator('#start_date').fill('2026-09-23');
    await save(page, '会议行动转WBS任务');
    expect((await actionRow(page, id, aConv.id)).status).toBe('converted');

    // 关闭2: admin UI 提交完成(证据+独立核验人) -> 第二上下文核验人批准关闭(closed).
    await open(page, id, '需求与治理', '会议行动');
    await row(page, tClosed).getByRole('button', { name: '提交完成', exact: true }).click();
    const compForm = modal(page, '提交行动完成');
    await compForm.locator('#result').fill('已按规范补全并附实测记录.');
    await choose(page, compForm, 'evidence_ids', `CLS-${suffix}`);
    await choose(page, compForm, 'reviewer_id', reviewerName);
    await save(page, '提交行动完成');
    expect((await actionRow(page, id, aClosed.id)).status).toBe('in_review');

    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const reviewer = await context.newPage();
    reviewer.on('pageerror', error => errors.push(error.message));
    await login(reviewer, reviewerName, reviewerPwd);
    await open(reviewer, id, '需求与治理', '会议行动');
    await expect(row(reviewer, tClosed).getByRole('button', { name: '批准关闭', exact: true })).toBeVisible();
    await row(reviewer, tClosed).getByRole('button', { name: '批准关闭', exact: true }).click();
    const decForm = modal(reviewer, '核验通过并关闭行动');
    await decForm.locator('#reason').fill('已独立核对实测记录与证据版本, 同意关闭行动.');
    await save(reviewer, '核验通过并关闭行动');
    expect((await actionRow(page, id, aClosed.id)).status).toBe('closed');

    // 面板截图1: admin 视角"会议行动闭环率"面板五枚徽标.
    await open(page, id, '需求与治理', '会议行动');
    const clPanel = panel(page, '会议行动闭环率');
    await expect(clPanel.getByText('行动总数 5', { exact: true })).toBeVisible();
    await expect(clPanel.getByText(/已闭环 40%/)).toBeVisible();
    await expect(clPanel.getByText('未完成 3', { exact: true })).toBeVisible();
    await expect(clPanel.getByText('逾期未闭环 2', { exact: true })).toBeVisible();
    await expect(clPanel.getByText('转任务 1', { exact: true })).toBeVisible();
    await panelShot(page, '会议行动闭环率', 'c07ac-1-panel.png');

    // 截图2: 同页签行动台账带各自状态/逾期标记.
    await shot(page, 'c07ac-2-actions.png');

    // 服务端回显二次确认(读取时派生, 不落库).
    const gov = await api(page, 'GET', base(id) + '/governance');
    const cl = gov.action_closure;
    expect(cl.total).toBe(5);
    expect(cl.closed).toBe(2);
    expect(cl.open).toBe(3);
    expect(cl.converted).toBe(1);
    expect(cl.overdue).toBe(2);
    expect(cl['closure-pct']).toBe(40);
    expect(cl.overdue).toBeLessThanOrEqual(cl.open);

    // 空态截图3: 另建一个项目, 无任何行动时面板显示空态提示.
    const empty = await api(page, 'POST', '/api/pms/projects', { project_no: `C07AC0-${suffix}`, name: `闭环率空态 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    await open(page, empty.project_id, '需求与治理', '会议行动');
    const emptyPanel = panel(page, '会议行动闭环率');
    await expect(emptyPanel.getByText('暂无行动项', { exact: false })).toBeVisible();
    await expect(emptyPanel.getByText('行动总数 5', { exact: true })).toHaveCount(0);
    await panelShot(page, '会议行动闭环率', 'c07ac-3-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
    await context.close();
  });
});
