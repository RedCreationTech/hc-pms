const { test, expect } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');

// C05 文档批量下载: 界面登记两份证据文档->点击批量下载触发真实ZIP下载->校验文件名与PK头, 并以真实HTTP验证空ID被拒.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c05');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
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

async function fill(page, title, values) {
  const form = modal(page, title);
  for (const [key, value] of Object.entries(values)) {
    await form.locator(`#${key}`).fill(value);
  }
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
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function registerDocument(page, id, code, filename, content) {
  await drawer(page).getByRole('button', { name: '登记证据文档', exact: true }).click();
  await fill(page, '登记证据文档', { code, title: `${code} 记录`, filename, content });
  return save(page, '登记证据文档');
}

test.describe('C05 文档批量下载浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 }, acceptDownloads: true });
  test.setTimeout(180000);

  test('界面登记两份证据后批量下载ZIP, 空ID经真实HTTP被拒', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C05-${suffix}`, name: `文档批量下载验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 进入 需求与治理 -> 证据版本, 界面登记两份真实文本证据.
    await open(page, id, '需求与治理', '证据版本');
    const docA = await registerDocument(page, id, `DOC-A-${suffix}`, 'design-a.txt', '设计评审正文A\n含首尾空格 ');
    await registerDocument(page, id, `DOC-B-${suffix}`, 'design-b.txt', '设计评审正文B\n第二份独立版本');

    // 等待读模型刷新出两份文档后再截图, 保证台账与"批量下载"入口同框.
    await expect(drawer(page).getByText(`DOC-A-${suffix}`, { exact: false }).first()).toBeVisible();
    await expect(drawer(page).getByText(`DOC-B-${suffix}`, { exact: false }).first()).toBeVisible();

    // 截图1: 证据版本页签出现"批量下载"入口, 两份文档列于台账.
    await expect(drawer(page).getByRole('button', { name: '批量下载', exact: true })).toBeVisible();
    await shot(page, 'c05-1-documents-tab.png');

    // 真实HTTP拒绝路径: 空ID数组 400.
    const rejectEmpty = await page.evaluate(async ({ id }) => {
      const token = localStorage.getItem('ruoyi_token');
      const r = await fetch(`/api/pms/projects/${id}/governance/documents/batch-download`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ record_ids: [] }) });
      return (await r.json()).code;
    }, { id });
    expect(rejectEmpty, '空ID批量下载应被拒').toBe(400);

    // 点击批量下载, 捕获真实ZIP下载事件.
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      drawer(page).getByRole('button', { name: '批量下载', exact: true }).click()
    ]);
    expect(download.suggestedFilename()).toBe('证据文档.zip');
    const zipPath = path.join(output, 'batch-download.zip');
    await download.saveAs(zipPath);

    // 校验落盘文件为真实ZIP(PK头)且非空.
    const stat = fs.statSync(zipPath);
    expect(stat.size, 'ZIP应非空').toBeGreaterThan(80);
    const head = fs.openSync(zipPath, 'r');
    const buf = Buffer.alloc(2);
    fs.readSync(head, buf, 0, 2, 0);
    fs.closeSync(head);
    expect(buf.toString('latin1'), 'ZIP应以PK魔数开头').toBe('PK');

    // 截图2: 下载完成后界面保持稳定.
    await shot(page, 'c05-2-after-download.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
