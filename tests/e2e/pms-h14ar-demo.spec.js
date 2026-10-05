const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H14 收尾归档就绪度分类只读汇总 - 演示录像: 真实浏览器操作 (建项目 -> 结项与移交页签 -> 就绪度面板五类全阻塞 0%
// -> 界面新增必需检查项 -> 完成检查绑定证据 -> 刷新 -> 收尾清单类翻就绪, 归档就绪度 0%->20%), 录制 webm 后转 mp4 供报告内嵌.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '收尾归档就绪度', exact: true }) }).first();

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
async function mutate(page, id, suffix, data = {}, expected = 200) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version }, expected);
}
async function tab(page, name) {
  await drawer(page).getByRole('tab', { name, exact: true }).click();
  await page.waitForLoadState('networkidle');
}
async function open(page, id, section) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  if (section) await tab(page, section);
}
async function fill(page, form, values) {
  for (const [key, value] of Object.entries(values)) { await form.locator(`#${key}`).click(); await form.locator(`#${key}`).fill(String(value)); await page.waitForTimeout(250); }
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

test.use({ viewport: { width: 1600, height: 1000 }, video: { mode: 'on', size: { width: 1600, height: 1000 } } });

test.describe('H14 收尾归档就绪度 演示', () => {
  test.setTimeout(600000);

  test('演示: 五类全阻塞 0% -> 新增并完成必需检查 -> 收尾清单翻就绪 20%', async ({ page }, testInfo) => {
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H14D-${suffix}`, name: `归档就绪度演示 ${suffix}`,
      project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
    const id = project.project_id;
    const evidence = (await mutate(page, id, '/governance/documents', { code: 'H14-EV', title: '结项交付证据', filename: 'h14.txt', content: '合成结项证据.' })).result;
    const checkTitle = `归档就绪度检查-${suffix}`;

    await open(page, id, '结项与移交');
    await panel(page).scrollIntoViewIfNeeded();
    await page.waitForTimeout(2500); // 起点: 五类全部有阻塞, 归档就绪度 0%

    await drawer(page).getByRole('button', { name: '添加检查项', exact: true }).click();
    await fill(page, modal(page, '添加收尾检查项'), { title: checkTitle });
    await save(page, '添加收尾检查项');
    await page.waitForTimeout(600);

    await open(page, id, '结项与移交');
    await drawer(page).getByRole('button', { name: '确认完成', exact: true }).first().scrollIntoViewIfNeeded();
    await page.waitForTimeout(400);
    await drawer(page).getByRole('button', { name: '确认完成', exact: true }).first().click();
    const cform = modal(page, '完成收尾检查');
    await choose(page, cform, 'evidence_ref', 'H14-EV');
    await fill(page, cform, { comment: '已核对交付证据, 收尾清单已具备.' });
    await save(page, '完成收尾检查');
    await page.waitForTimeout(600);

    await open(page, id, '结项与移交');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('归档就绪度 20%', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('就绪类别 1 / 5', { exact: true })).toBeVisible();
    await page.waitForTimeout(3000); // 终点: 收尾清单类翻就绪, 归档就绪度 0%->20%

    const dest = path.resolve(__dirname, '../../reports/h14ar/h14ar-demo.webm');
    await page.video().saveAs(dest);
  });
});
