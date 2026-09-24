const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 干系人权力-利益象限只读洞察: 界面"登记干系人"按影响力(权力)与关注度(利益)组合
// -> "干系人识别"台账"管理策略"列派生四象限徽标(重点管理/保持满意/保持知会/持续监控) ->
// 未绑定项目成员责任人的干系人追加"未绑定责任人"提示. 复用既有 stakeholder 类型与只读读模型, 免迁移.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h02d');
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
  // 共享库累积用户/任务后长列表被 antd 虚拟滚动裁剪, 可搜索的下拉先按标签过滤再选.
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

// 界面登记一名干系人: 编号唯一, 按传入的影响力/关注度选择象限, bind-owner 决定是否绑定项目成员责任人.
async function registerStakeholder(page, opts) {
  await drawer(page).getByRole('button', { name: '登记干系人', exact: true }).click();
  const form = modal(page, '登记干系人');
  await form.locator('#code').fill(opts.code);
  await form.locator('#name').fill(opts.name);
  await form.locator('#role').fill(opts.role);
  await choose(page, form, 'category', opts.categoryLabel);
  await choose(page, form, 'influence', opts.influence);
  await choose(page, form, 'interest', opts.interest);
  if (opts.bindOwner) await choose(page, form, 'owner_id', 'admin');
  return (await save(page, '登记干系人')).result;
}

test.describe('H02 干系人权力-利益象限浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('登记不同权力-利益组合 -> 管理策略四象限徽标 + 未绑定责任人提示', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H02D-${suffix}`, name: `干系人象限验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    await open(page, id, '需求与治理', '干系人与沟通');

    // 四象限: 高权力高利益=重点管理, 高权力低利益=保持满意, 低权力高利益=保持知会, 低低=持续监控.
    const keySh = await registerStakeholder(page, { code: `SH-KEY-${suffix}`, name: '客户决策人', role: '验收决策',
      categoryLabel: '客户', influence: 'high', interest: 'high', bindOwner: true });
    const satSh = await registerStakeholder(page, { code: `SH-SAT-${suffix}`, name: '监管机构', role: '合规审查',
      categoryLabel: '监管方', influence: 'high', interest: 'low', bindOwner: true });
    const infSh = await registerStakeholder(page, { code: `SH-INF-${suffix}`, name: '终端用户', role: '使用反馈',
      categoryLabel: '客户', influence: 'low', interest: 'high', bindOwner: true });
    // 低权力低利益且不绑定责任人 -> 持续监控 + 未绑定责任人.
    const monSh = await registerStakeholder(page, { code: `SH-MON-${suffix}`, name: '一般供应商', role: '备件供应',
      categoryLabel: '供应商', influence: 'medium', interest: 'medium', bindOwner: false });

    await open(page, id, '需求与治理', '干系人与沟通');
    await expect(row(page, keySh.code).getByText('重点管理')).toBeVisible();
    await expect(row(page, satSh.code).getByText('保持满意')).toBeVisible();
    await expect(row(page, infSh.code).getByText('保持知会')).toBeVisible();
    await expect(row(page, monSh.code).getByText('持续监控')).toBeVisible();
    await expect(row(page, monSh.code).getByText('未绑定责任人')).toBeVisible();
    // 已绑定责任人的三行不应出现"未绑定责任人".
    await expect(row(page, keySh.code).getByText('未绑定责任人')).toHaveCount(0);
    await shot(page, 'h02d-1-quadrants.png');

    // 服务端只读读模型二次确认象限与绑定标记.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const byId = rid => gov.stakeholders.find(s => s.id === rid);
    expect(byId(keySh.id).stakeholder_quadrant).toBe('manage-close');
    expect(byId(satSh.id).stakeholder_quadrant).toBe('keep-satisfied');
    expect(byId(infSh.id).stakeholder_quadrant).toBe('keep-informed');
    expect(byId(monSh.id).stakeholder_quadrant).toBe('monitor');
    expect(byId(keySh.id).stakeholder_unbound, '已绑定责任人不为未绑定').toBe(false);
    expect(byId(monSh.id).stakeholder_unbound, '未选责任人的干系人标记为未绑定').toBe(true);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
