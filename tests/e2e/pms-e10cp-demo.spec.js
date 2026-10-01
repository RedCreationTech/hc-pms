const { test, expect } = require('@playwright/test');
const path = require('node:path');

// E10 收尾清单闭环进度只读汇总 - 演示录像: 真实浏览器操作 (建项目 -> 空态面板 -> 界面新增必需检查/逾期移交/经验
// -> 完成检查绑定证据 -> 面板聚合刷新), 录制 webm 后转 mp4 供端到端报告内嵌.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '收尾闭环进度', exact: true }) }).first();

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
async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
}
async function fill(page, form, values) {
  for (const [key, value] of Object.entries(values)) { await form.locator(`#${key}`).click(); await form.locator(`#${key}`).fill(String(value)); await page.waitForTimeout(250); }
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

test.describe('E10 收尾闭环进度 演示', () => {
  test.setTimeout(360000);

  test('演示: 空态 -> 检查/移交/经验 -> 完成检查 -> 面板聚合', async ({ page }, testInfo) => {
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `E10D-${suffix}`, name: `收尾闭环演示 ${suffix}`,
      project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
    const id = project.project_id;
    const evidence = (await mutate(page, id, '/governance/documents', { code: 'E10-EV', title: '结项交付证据', filename: 'e10.txt', content: '合成结项证据.' })).result;
    const checkTitle = `收尾检查-${suffix}`;
    const handoffTitle = `遗留移交-${suffix}`;

    await open(page, id, '结项与移交');
    await panel(page).scrollIntoViewIfNeeded();
    await page.waitForTimeout(1500);

    await drawer(page).getByRole('button', { name: '添加检查项', exact: true }).click();
    await fill(page, modal(page, '添加收尾检查项'), { title: checkTitle });
    await save(page, '添加收尾检查项');
    await page.waitForTimeout(500);

    await open(page, id, '结项与移交');
    await drawer(page).getByRole('button', { name: '新增移交事项', exact: true }).click();
    const hform = modal(page, '新增交付移交');
    await fill(page, hform, { title: handoffTitle, due_date: '2000-01-01' });
    await choose(page, hform, 'owner_id', /\/ admin$/);
    await save(page, '新增交付移交');
    await page.waitForTimeout(500);

    await open(page, id, '结项与移交');
    await drawer(page).getByRole('button', { name: '登记项目经验', exact: true }).click();
    await fill(page, modal(page, '登记项目经验'), { title: `交付经验-${suffix}`, category: '流程', content: '证据先行登记, 结项更顺畅.' });
    await save(page, '登记项目经验');
    await page.waitForTimeout(500);

    await open(page, id, '结项与移交');
    await row(page, checkTitle).getByRole('button', { name: '确认完成', exact: true }).click();
    const cform = modal(page, '完成收尾检查');
    await choose(page, cform, 'evidence_ref', 'E10-EV');
    await fill(page, cform, { comment: '已核对交付证据.' });
    await save(page, '完成收尾检查');
    await page.waitForTimeout(500);

    await open(page, id, '结项与移交');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('收尾闭环率 50%', { exact: true })).toBeVisible();
    await page.waitForTimeout(2500);

    const dest = path.resolve(__dirname, '../../reports/e10cp/e10cp-demo.webm');
    await page.video().saveAs(dest);
    await page.waitForTimeout(300);
  });
});
