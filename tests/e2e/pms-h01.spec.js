const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H01 章程初始预算: 界面登记可选初始预算(金额规范化/币种缺省CNY)-> 台账显示"金额 币种"-> 读模型回显;
// 未填预算记为"未设定"; 非法金额经真实HTTP被拒; 预算为章程专属字段, 修订形成新不可变版本.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h01');
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

// 发起人 与 预算币种 是表单里两个下拉; 按 form-item 标签定位各自 .ant-select, 再点可见下拉的选项, 避免串台.
async function pickSelect(page, form, label, optionText) {
  await form.locator('.ant-form-item', { hasText: label }).first().locator('.ant-select').click();
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
  const option = optionText
    ? dropdown.locator('.ant-select-item-option-content', { hasText: optionText }).first()
    : dropdown.locator('.ant-select-item-option-content').first();
  await option.click();
  await page.waitForLoadState('networkidle');
}

// 登记项目章程; budget/currency 为空则留空(可选字段).
async function registerCharter(page, { title, objective, scope, criteria, sponsor, budget, currency }) {
  const title_ = '编制项目章程';
  await drawer(page).getByRole('button', { name: title_, exact: true }).click();
  const form = modal(page, title_);
  await form.locator('#title').fill(title);
  await form.locator('#objective').fill(objective);
  await form.locator('#scope').fill(scope);
  await form.locator('#success_criteria').fill(criteria);
  await pickSelect(page, form, '发起人', sponsor);
  if (budget) await form.locator('#initial_budget').fill(budget);
  if (currency) await pickSelect(page, form, '预算币种', currency);
  return save(page, title_);
}

test.describe('H01 章程初始预算浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('界面登记带初始预算的章程, 规范化与币种回显, 修订不漂移, 非法金额经真实HTTP被拒', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H01-${suffix}`, name: `章程初始预算验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '章程');

    const title = `涉密产线章程-${suffix}`;
    const common = { title, objective: '建成并验证产线', criteria: 'SAT全部通过', sponsor: 'admin' };

    // 1) 界面登记: 金额规范化为两位小数 + 显式选USD币种 (同时驱动金额输入框与币种下拉).
    const created = await registerCharter(page, { ...common, scope: '设备与培训', budget: '88.9', currency: 'USD' });
    const rid1 = created.result.id;
    expect(created.result.initial_budget, '命令响应回显规范化金额').toBe('88.90');
    expect(created.result.budget_currency, '显式币种USD').toBe('USD');
    expect(created.result.revision, '首版revision为1').toBe(1);

    // 2) 修订(未填币种) -> 缺省CNY; 修订(未填金额) -> 不含预算. 逐版派生, 旧版本预算不漂移.
    const r2 = await api(page, 'POST', `${base(id)}/governance/charters/${rid1}/revisions`, {
      title, objective: '建成并验证产线', scope: '设备与培训(扩容)', success_criteria: 'SAT全部通过',
      sponsor_id: adminId, initial_budget: '120000.5', version: (await api(page, 'GET', base(id))).version });
    expect(r2.result.revision, '第二版').toBe(2);
    expect(r2.result.initial_budget).toBe('120000.50');
    expect(r2.result.budget_currency, '修订未选币种缺省CNY').toBe('CNY');

    const r3 = await api(page, 'POST', `${base(id)}/governance/charters/${r2.result.id}/revisions`, {
      title, objective: '建成并验证产线', scope: '设备与培训(取消预算)', success_criteria: 'SAT全部通过',
      sponsor_id: adminId, version: (await api(page, 'GET', base(id))).version });
    expect(r3.result.revision, '第三版').toBe(3);
    expect(r3.result.initial_budget == null, '第三版不含预算').toBeTruthy();

    // 读模型: 三个不可变版本并存, 旧版本预算保持原值 (无漂移).
    const ws = await api(page, 'GET', base(id) + '/governance');
    const v1 = ws.charters.find(c => c.id === rid1);
    const v2 = ws.charters.find(c => c.id === r2.result.id);
    const v3 = ws.charters.find(c => c.id === r3.result.id);
    expect([v1.initial_budget, v1.budget_currency], '首版USD不漂移').toEqual(['88.90', 'USD']);
    expect([v2.initial_budget, v2.budget_currency], '第二版CNY').toEqual(['120000.50', 'CNY']);
    expect(v3.initial_budget == null, '第三版无预算').toBeTruthy();

    // 台账界面: 重新进入以拉取最新读模型(API 修订不触发前端自动刷新), 三行分别显示"金额 币种", 取消预算的行显示"未设定".
    await open(page, id, '需求与治理', '章程');
    await expect(drawer(page).getByText('88.90 USD', { exact: true })).toBeVisible();
    await expect(drawer(page).getByText('120000.50 CNY', { exact: true })).toBeVisible();
    await expect(drawer(page).getByText('未设定', { exact: true }).first()).toBeVisible();
    await shot(page, 'h01-1-charter-budgets.png');

    // 真实HTTP拒绝路径: 超过两位小数的金额 400; 非法币种 400.
    const badAmount = await page.evaluate(async ({ id, suffix }) => {
      const token = localStorage.getItem('ruoyi_token');
      const version = (await (await fetch(`/api/pms/projects/${id}`, { headers: { Authorization: `Bearer ${token}` } })).json()).data.version;
      const r = await fetch(`/api/pms/projects/${id}/governance/charters`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: `BAD-${suffix}`, objective: 'o', scope: 's', success_criteria: 'c', sponsor_id: 1, initial_budget: '1.234', version }) });
      return (await r.json()).code;
    }, { id, suffix });
    expect(badAmount, '超过两位小数的金额应被拒').toBe(400);

    const badCurrency = await page.evaluate(async ({ id, suffix }) => {
      const token = localStorage.getItem('ruoyi_token');
      const version = (await (await fetch(`/api/pms/projects/${id}`, { headers: { Authorization: `Bearer ${token}` } })).json()).data.version;
      const r = await fetch(`/api/pms/projects/${id}/governance/charters`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: `BAD-${suffix}`, objective: 'o', scope: 's', success_criteria: 'c', sponsor_id: 1, initial_budget: '10', budget_currency: 'RUB', version }) });
      return (await r.json()).code;
    }, { id, suffix });
    expect(badCurrency, '非法币种应被拒').toBe(400);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
