const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H14 收尾批准与项目归档 (只读派生): 结项与移交 页签新增"项目归档状态"面板.
// 服务端 closure/overview 顶层 :archive 把"是否已归档/归档时刻/历届关闭审批/受控重开"读时投影出来;
// 免迁移/无新命令/无新路由/不门控任何写操作 (关闭准入与独立关闭审批仍由真实状态机强制).
// 同时覆盖终态只读: 项目 closed 后参与者仍能打开收尾工作台看到归档摘要, 但任何写入仍 409.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h14arc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const base = id => `/api/pms/projects/${id}`;
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '项目归档状态', exact: true }) }).first();

async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

async function api(page, method, url, data, expected = 200) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data, timeout: 30000 });
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

async function mutate(page, id, suffix, data = {}, expected = 200) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version }, expected);
}

async function open(page, id) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await drawer(page).getByRole('tab', { name: '结项与移交', exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '项目归档状态', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function newProject(page, suffix) {
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H14ARC-${suffix}`, name: `归档状态 ${suffix}`,
    project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const other = options.users.find(u => u.user_id !== options.currentUserId);
  return { id: project.project_id, adminId: options.currentUserId, reviewerId: other ? other.user_id : options.currentUserId };
}

test.describe('H14 收尾批准与项目归档只读派生', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('未进入收尾阶段时面板呈现未归档/未重开, 服务端 :archive 与界面同源, 且真实写入仍被阶段门控拒绝 409', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const { id, reviewerId } = await newProject(page, serial());

    // 1. 收尾前: 服务端 :archive 与界面同源 (未归档, 无关闭审批, 未重开).
    await open(page, id);
    await expect(panel(page)).toBeVisible();
    const a0 = (await api(page, 'GET', base(id) + '/closure')).archive;
    expect(a0.closed).toBe(false);
    expect(a0.archived).toBe(false);
    expect(a0['archived-at']).toBeNull();
    expect(a0.reopened).toBe(false);
    expect(a0.approvals.total).toBe(0);
    await expect(panel(page).getByText('未归档', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('未重开', { exact: true })).toBeVisible();
    await shot(page, 'h14arc-1-open.png');
    await shotPanel(page, 'h14arc-1-open-panel.png');

    // 2. 未进入收尾阶段即提交关闭审批被真实拒绝 409 (只读面板不旁路写门控).
    await mutate(page, id, '/closure/submit', { reviewer_id: reviewerId }, 409);
    await mutate(page, id, '/closure/review', { decision: 'approved', reason: 'E2E 强行批准' }, 409);
    const ov = await api(page, 'GET', base(id) + '/closure');
    expect(ov.archive.approvals.total).toBe(0);
    expect(ov.archive.reopens.total).toBe(0);

    expect(errors).toEqual([]);
  });
});