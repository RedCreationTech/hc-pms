const { test, expect } = require('@playwright/test');
const path = require('node:path');

const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../..');
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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('A08 项目成员任命书浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(120000);

  test('在治理工作台签发不可变成员任命书并核对团队快照摘要', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const project = await api(page, 'POST', '/api/pms/projects', {
      project_no: `APPT-${suffix}`, name: `任命书验收 / ${suffix}`, project_type: 'line',
      manager_id: options.currentUserId, dept_id: deptId,
      start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 服务端签发一份, 验证列表读取到不可变版本
    const issued = await api(page, 'POST', base(id) + '/governance/appointments',
      { issued_on: '2026-09-22', note: 'API签发基准', version: (await api(page, 'GET', base(id))).version });
    expect(issued.result.revision).toBe(1);
    expect(issued.result.status).toBe('issued');
    expect(issued.result.snapshot_sha256).toMatch(/^[0-9a-f]{64}$/);

    // 打开治理工作台 -> 成员任命页签, 通过界面再签发一份
    await open(page, id, '需求与治理', '成员任命');
    await expect(row(page, 'APPT')).toBeVisible();
    await drawer(page).getByRole('button', { name: '签发任命书', exact: true }).click();
    const form = modal(page, '签发项目成员任命书');
    await form.locator('#issued_on').fill('2026-10-01');
    await form.locator('#note').fill('界面签发任命');
    await form.getByRole('button', { name: /保\s*存/ }).click();
    await expect(form).toBeHidden();

    // 列表应保留两个不可变版本 V1 与 V2
    await expect(drawer(page).locator('tbody tr:visible').filter({ hasText: 'APPT' })).toHaveCount(2);
    const gov = await api(page, 'GET', base(id) + '/governance');
    const appts = gov.appointments.filter(a => a.code === 'APPT');
    expect(appts.map(a => a.revision).sort()).toEqual([1, 2]);
    expect(appts.every(a => a.content === undefined), '列表不返回正文').toBeTruthy();
    expect(appts.every(a => typeof a.snapshot_sha256 === 'string')).toBeTruthy();

    // 打开 V2 查看任命书正文, 校验团队快照摘要一致并可下载
    await drawer(page).getByRole('button', { name: '查看任命书', exact: true }).first().click();
    const view = page.getByRole('dialog', { name: /项目成员任命书 V\d/ });
    await expect(view.getByText('团队快照SHA256')).toBeVisible();
    await expect(view.locator('pre')).toContainText('项目成员任命书');
    const dl = page.waitForEvent('download');
    await view.getByRole('button', { name: '下载此版本', exact: true }).click();
    const download = await dl;
    expect(download.suggestedFilename()).toMatch(/^appointment-v\d+\.txt$/);
    await shot(page, 'pms-appointment-preview.png');

    expect(errors, '页面不应出现未捕获的 JavaScript 错误').toEqual([]);
  });
});
