const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H10 延伸: DQ 检验方法与执行角色交叉覆盖只读派生 (免迁移, 读模型计算, 无新kind/命令/路由/门控).
// 真实HTTP建交付件 -> 界面建三条 DQ: DQ1 检验+质检员 / DQ2 试验(无角色) / DQ3 测量+质检员 ->
//   "DQ 检验方法与执行角色覆盖"面板: 方法已覆盖 100% (3/3) / 角色已覆盖 67% (2/3) / 方法与角色齐备 67% (2/3) /
//   有方法未指派角色 1 / 未声明方法 0 / 未指派角色 1 / 四档方法标签 (检验·1项 / 测量·1项 / 试验·1项 / 文件评审·未使用) /
//   执行角色: 质检员 (2 项 · 已签认 0) ->
//   真实HTTP GET /governance 回显 dq_method_coverage 各字段与界面一致, 且逐条 DQ 不含 dq_method_coverage (不回写).
// 全程真实点击 + 真实HTTP + 真实浏览器截图.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h10mrc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
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

async function command(page, id, suffix, data = {}, expected = 200) {
  const project = await api(page, 'GET', base(id));
  const result = await api(page, 'POST', base(id) + '/governance' + suffix, { ...data, version: project.version }, expected);
  return result && result.result;
}

async function open(page, id, section, subsection) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await drawer(page).getByRole('tab', { name: section, exact: true }).click();
  await page.waitForLoadState('networkidle');
  if (subsection) {
    await drawer(page).getByRole('tab', { name: subsection, exact: true }).click();
    await page.waitForLoadState('networkidle');
  }
}

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
}

async function save(page, title, expected = 200) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(expected);
  if (expected === 200) { await expect(form).toBeHidden(); await page.waitForLoadState('networkidle'); }
  return body;
}

async function shotCard(page, card, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function createDq(page, suffix, code, title, docCode, method, role) {
  await drawer(page).getByRole('button', { name: '建立DQ关键任务', exact: true }).click();
  const form = modal(page, '建立DQ关键任务');
  await fill(form, { code: `${code}-${suffix}`, title, check_titles: '关键尺寸测量到位' });
  await choose(page, form, 'owner_id', 'admin');
  await choose(page, form, 'deliverable_ids', docCode);
  if (method) await choose(page, form, 'check_method', method);
  if (role) await form.locator('#responsible_role').fill(role);
  await save(page, '建立DQ关键任务');
}

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('H10 延伸 DQ 检验方法与执行角色覆盖浏览器验收', () => {
  test.setTimeout(180000);

  test('方法×角色交叉覆盖面板与真实HTTP读模型一致且不回写逐条DQ', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `MRC-${suffix}`, name: `DQ方法角色覆盖验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const docCode = `MRCDOC-${suffix}`;
    await command(page, id, '/documents', { code: docCode, title: `交付件-${suffix}`, filename: '交付.txt', content: '确定版本交付件\n' });

    // 面板初态: 无 DQ -> 占位.
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    const cover = panel(page, 'DQ 检验方法与执行角色覆盖');
    await expect(cover).toContainText('暂无 DQ 关键任务');

    // 界面登记三条 DQ: 覆盖两种方法 + 一个角色, 另有一条有方法无角色.
    await createDq(page, suffix, 'MRDQ1', `覆盖1-${suffix}`, docCode, '检验', '质检员');
    await createDq(page, suffix, 'MRDQ2', `覆盖2-${suffix}`, docCode, '试验', null);
    await createDq(page, suffix, 'MRDQ3', `覆盖3-${suffix}`, docCode, '测量', '质检员');

    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(cover).toContainText('DQ 总数 3');
    await expect(cover).toContainText('方法已覆盖 100% (3/3)');
    await expect(cover).toContainText('角色已覆盖 67% (2/3)');
    await expect(cover).toContainText('方法与角色齐备 67% (2/3)');
    await expect(cover).toContainText('有方法未指派角色 1');
    await expect(cover).toContainText('未指派角色 1');
    await expect(cover).toContainText('检验 · 1 项 · 已签认 0');
    await expect(cover).toContainText('测量 · 1 项 · 已签认 0');
    await expect(cover).toContainText('试验 · 1 项 · 已签认 0');
    await expect(cover).toContainText('文件评审 · 未使用');
    await expect(cover).toContainText('执行角色: 质检员 (2 项 · 已签认 0)');
    await shotCard(page, cover, 'h10mrc-1-coverage.png');

    // 真实HTTP读模型回显, 与界面同源.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const c = ws.dq_method_coverage;
    expect(c.available, 'available').toBe(true);
    expect(c.total, 'total').toBe(3);
    expect(c['method-covered'], 'method-covered').toBe(3);
    expect(c['role-covered'], 'role-covered').toBe(2);
    expect(c['full-covered'], 'full-covered').toBe(2);
    expect(c['method-missing-role'], 'method-missing-role').toBe(1);
    expect(c['role-missing-method'], 'role-missing-method').toBe(0);
    expect(c['no-method'], 'no-method').toBe(0);
    expect(c['no-role'], 'no-role').toBe(1);
    expect(c['method-usage-pct'], 'method-usage-pct').toBe(100);
    expect(c['role-usage-pct'], 'role-usage-pct').toBe(67);
    expect(c['full-usage-pct'], 'full-usage-pct').toBe(67);
    expect(c.methods.map(m => m.method), '方法固定顺序').toEqual(['inspection', 'measurement', 'test', 'documentation-review']);
    expect(c.methods.map(m => m.count), '逐方法 DQ 数').toEqual([1, 1, 1, 0]);
    expect(c.methods.map(m => m.omitted), '逐方法未使用标记').toEqual([false, false, false, true]);
    expect(c.roles.map(r => r.role), '执行角色出场').toEqual(['质检员']);
    expect(c.roles[0].count, '角色分组 DQ 数').toBe(2);
    expect(ws.dqs.every(d => !('dq_method_coverage' in d)), '逐条 DQ 不因只读派生漂移').toBe(true);
    expect(ws.dqs.find(d => d.code === `MRDQ1-${suffix}`).check_method, '源 DQ 检验方法原样').toBe('inspection');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});