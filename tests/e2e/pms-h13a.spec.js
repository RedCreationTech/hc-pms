const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H13a 会计期间封期 -> 费用版本门控 浏览器验收 (免迁移, 复用平台级 period-lock 配置):
//  admin 在项目费用页新建 2026-09 成本版本草稿并添加真实条目 ->
//  经真实 HTTP 锁定 2026-09 会计期间 (config period-lock) ->
//  界面刷新后: 成本版本台账出现"已封账"徽标与警告横幅 (真实可见), 提交该期间草稿命中门控 409 (弹窗内联告警, 抓拍) ->
//  经真实 HTTP 解锁期间 -> 徽标消失, 提交草稿成功 (读模型 status 回显 submitted).
//  门控事实由真实 HTTP/读模型确认, 徽标/横幅/409 内联告警均为真实浏览器界面.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h13a');
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
  await page.waitForLoadState('networkidle');
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

// 预期失败的门控提交: 断言返回码, 弹窗保持打开, 在错误内联告警可见的瞬间抓拍, 再点返回关闭.
async function saveExpectGate(page, title, code, pattern, shotFile) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: 期望 ${code}`).toBe(code);
  await expect(form).toBeVisible();
  if (shotFile) {
    await expect(form).toContainText(pattern, { timeout: 5000 });
    await page.screenshot({ path: path.join(output, shotFile), animations: 'disabled' });
  }
  await form.getByRole('button', { name: /返\s*回/ }).click();
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body;
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0, { timeout: 8000 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('H13a 会计期间封期门控浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('封期 -> 已封账徽标与横幅可见 -> 提交命中门控(409) -> 解锁后提交成功', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    // 独立财务审批人 (费用版本创建要求审批人非提交者本人).
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:finance:query', 'pms:finance:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `封期审核${suffix}`, role_key: `h13_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `h13_${suffix}`).role_id;
    const revName = `h13r_${suffix}`;
    const revPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: revName, nick_name: '封期审核人', password: revPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'H13a E2E合成审批人' });
    const reviewerId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === revName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H13A-${suffix}`, name: `会计期间封期验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: reviewerId, role: 'editor' });

    // 1) 界面新建 2026-09 成本版本草稿 + 添加一条真实条目 (封期前一切正常).
    const name = `九月预算-${suffix}`;
    await open(page, id, '项目费用', '成本与分摊');
    await drawer(page).getByRole('button', { name: '新建成本版本', exact: true }).click();
    let form = modal(page, '新建成本版本');
    await form.locator('#name').fill(name);
    await form.locator('#period').fill('2026-09');
    await form.locator('#revenue').fill('100.00');
    await choose(page, form, 'reviewer_id', revName);
    await save(page, '新建成本版本');
    await open(page, id, '项目费用', '成本与分摊');
    await expect(row(page, name)).toBeVisible();
    await row(page, name).getByRole('button', { name: '添加条目', exact: true }).click();
    form = modal(page, '添加成本条目');
    await choose(page, form, 'category', '材料');
    await form.locator('#label').fill('设备采购');
    await form.locator('#amount').fill('50.00');
    await form.locator('#source_ref').fill(`INV-${suffix}`);
    await save(page, '添加成本条目');
    await open(page, id, '项目费用', '成本与分摊');
    await expect(drawer(page).getByText('已封账', { exact: true })).toHaveCount(0);
    await shot(page, 'h13a-1-cost-draft-before-lock.png');

    // 2) 真实 HTTP 锁定 2026-09 会计期间 (平台级 period-lock 配置).
    const lock = await api(page, 'POST', '/api/pms/config/period-lock', { period: '2026-09', reason: '月结封账, E2E 验证.' });
    const lockId = lock.config_id || lock.id;
    expect(lock.status, 'period-lock 创建即锁定').toBe('locked');

    // 3) 界面刷新: 成本版本台账出现"已封账"徽标与警告横幅 (真实可见).
    await open(page, id, '项目费用', '成本与分摊');
    await expect(drawer(page).getByText('已封账', { exact: true }).first()).toBeVisible();
    await expect(drawer(page).getByText(/已封账会计期间: 2026-09/)).toBeVisible();
    await shot(page, 'h13a-2-locked-badge-alert.png');

    // 4) 界面提交该期间草稿 -> 命中封期门控, 真实 409, 弹窗内联告警 (抓拍), 弹窗不关闭.
    await row(page, name).getByRole('button', { name: '提交审批', exact: true }).click();
    const gated = await saveExpectGate(page, '提交成本版本审批', 409, /封账|2026-09/, 'h13a-3-submit-gated-409.png');
    expect(gated.msg, '门控理由指向封账期间').toMatch(/封账|2026-09/);

    // 5) 真实 HTTP 回显: 草稿仍在, 状态未变 (门控未产生副作用).
    let fin = await api(page, 'GET', base(id) + '/finance');
    const draft = fin.cost_versions.find(v => v.name === name);
    expect(draft.status).toBe('draft');
    expect(fin.locked_periods).toContain('2026-09');

    // 6) 解锁 2026-09 -> 徽标消失, 界面提交成功.
    await api(page, 'POST', `/api/pms/config/period-lock/${lockId}/retire`, { reason: '重开月结, E2E 验证.' });
    await open(page, id, '项目费用', '成本与分摊');
    await expect(drawer(page).getByText('已封账', { exact: true })).toHaveCount(0);
    await shot(page, 'h13a-4-unlocked-badge-gone.png');
    await row(page, name).getByRole('button', { name: '提交审批', exact: true }).click();
    await save(page, '提交成本版本审批');
    fin = await api(page, 'GET', base(id) + '/finance');
    expect(fin.cost_versions.find(v => v.name === name).status).toBe('submitted');
    await open(page, id, '项目费用', '成本与分摊');
    await shot(page, 'h13a-5-submitted-after-unlock.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
