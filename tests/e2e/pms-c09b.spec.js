const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C09 问题逾期预警(只读增强): 界面登记一个到期日已过且严重度为阻断的问题 -> 台账"逾期预警"列同时出现
// "阻断级"(高关注)与"已逾期"标记; 再登记一个远期一般问题 -> 不出现任何逾期预警. 由 issue-read-model
// 依据服务器日期计算, 不新增写命令/迁移. 全程真实浏览器.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c09b');
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

async function version(page, id) {
  const project = await api(page, 'GET', base(id));
  return project.version;
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

test.describe('C09 问题逾期预警浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('逾期阻断问题出现预警标记, 远期一般问题不预警', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C09B-${suffix}`, name: `问题逾期预警验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 逾期阻断问题: 到期日在过去, 严重度阻断.
    const lateTitle = `关键产线物料缺料阻塞-${suffix}`;
    await open(page, id, '需求与治理', '风险与问题');
    await drawer(page).getByRole('button', { name: '登记项目问题', exact: true }).click();
    let form = modal(page, '登记项目问题');
    await form.locator('#title').fill(lateTitle);
    await choose(page, form, 'severity', '阻断');
    await choose(page, form, 'owner_id', 'admin');
    await form.locator('#due_date').fill('2026-01-10');
    await save(page, '登记项目问题');

    // 远期一般问题: 到期日在未来, 严重度一般.
    const futureTitle = `文档格式待统一-${suffix}`;
    await open(page, id, '需求与治理', '风险与问题');
    await drawer(page).getByRole('button', { name: '登记项目问题', exact: true }).click();
    form = modal(page, '登记项目问题');
    await form.locator('#title').fill(futureTitle);
    await choose(page, form, 'severity', '一般');
    await choose(page, form, 'owner_id', 'admin');
    await form.locator('#due_date').fill('2099-12-31');
    await save(page, '登记项目问题');

    // 台账: 逾期阻断问题在"逾期预警"列同时出现"阻断级"与"已逾期".
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, lateTitle).getByText('已逾期', { exact: true })).toBeVisible();
    await expect(row(page, lateTitle).getByText('阻断级')).toBeVisible();
    await shot(page, 'c09b-1-overdue-blocker.png');
    // 远期一般问题: 无逾期预警.
    await expect(row(page, futureTitle).getByText('已逾期', { exact: true })).toHaveCount(0);
    await expect(row(page, futureTitle).getByText('阻断级')).toHaveCount(0);
    await shot(page, 'c09b-2-clear.png');

    // 服务端读模型二次确认.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const late = gov.issues.find(i => i.title === lateTitle);
    const future = gov.issues.find(i => i.title === futureTitle);
    expect(late.issue_overdue, '过期未关闭判为逾期').toBe(true);
    expect(late.issue_critical, '阻断级判为重点关注').toBe(true);
    expect(future.issue_overdue, '远期问题不逾期').toBe(false);
    expect(future.issue_critical, '一般问题非阻断').toBe(false);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
