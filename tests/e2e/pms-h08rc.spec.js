const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H08 延伸(三): 风险应对策略覆盖度只读派生洞察 (免迁移, 无新命令/新kind).
// 界面登记若干选/不选应对策略的风险 -> "风险与问题"页签新增只读"风险应对覆盖度"面板:
// 按每个风险最新有效版本聚合 PMI 四类策略计数 + 已声明覆盖率百分比 + 未设定计数; 再声明一条后覆盖率上升.
// 只读派生不改变风险状态 (既有风险仍"登记"态, 策略不漂移).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h08rc');
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

// 同表单多个 antd Select 的下拉面板会同时留在 DOM, 用 combobox 自身 aria-controls 过滤其下拉避免串台.
async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 打开"登记项目风险"填必填项 (2x3=6 不触发升级); 若给定 strategyLabel 则再选应对策略.
async function fillRisk(page, { title, strategyLabel }) {
  await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
  const form = modal(page, '登记项目风险');
  await form.locator('#title').fill(title);
  await form.locator('#probability').fill('2');
  await form.locator('#impact').fill('3');
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#mitigation').fill('持续监控并预留纠偏窗口.');
  await form.locator('#due_date').fill('2026-10-20');
  if (strategyLabel) await choose(page, form, 'response_strategy', strategyLabel);
}

test.describe('H08 延伸 风险应对覆盖度只读洞察浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('覆盖度面板按最新风险聚合四类策略与覆盖率, 再声明一条后覆盖率上升', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RCC-${suffix}`, name: `风险应对覆盖度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '风险与问题');

    // 1) 界面登记三条风险: 转移 / 规避 / 不选应对策略.
    await fillRisk(page, { title: `供应中断风险-${suffix}`, strategyLabel: '转移' });
    await save(page, '登记项目风险');
    await fillRisk(page, { title: `技术选型风险-${suffix}`, strategyLabel: '规避' });
    await save(page, '登记项目风险');
    await fillRisk(page, { title: `常规观察风险-${suffix}` });
    await save(page, '登记项目风险');

    // 2) 覆盖度面板: 总数3, 已声明2 -> 67%, 未设定1, 转移·1 规避·1 减轻·0 接受·0.
    await open(page, id, '需求与治理', '风险与问题');
    await expect(drawer(page).getByText('风险应对覆盖度', { exact: true })).toBeVisible();
    await expect(drawer(page).getByText(/风险总数\s*3/)).toBeVisible();
    await expect(drawer(page).getByText(/已声明应对策略\s*67%/)).toBeVisible();
    await expect(drawer(page).getByText(/未设定\s*1/)).toBeVisible();
    await expect(drawer(page).getByText(/转移\s*·\s*1/)).toBeVisible();
    await expect(drawer(page).getByText(/规避\s*·\s*1/)).toBeVisible();
    await expect(drawer(page).getByText(/减轻\s*·\s*0/)).toBeVisible();
    await expect(drawer(page).getByText(/接受\s*·\s*0/)).toBeVisible();
    await shot(page, 'h08rc-1-coverage-panel.png');

    // 3) 真实HTTP读模型回显 risk_response_coverage.
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.risk_response_coverage.total, '覆盖度分母=最新有效版本风险数').toBe(3);
    expect(ws.risk_response_coverage.declared, '已声明数').toBe(2);
    expect(ws.risk_response_coverage.undeclared, '未声明数').toBe(1);
    expect(ws.risk_response_coverage['coverage-pct'], '覆盖率百分比').toBe(67);
    expect(ws.risk_response_coverage['by-strategy'].find(s => s.strategy === 'transfer').count, '转移计数').toBe(1);
    expect(ws.risk_response_coverage['by-strategy'].find(s => s.strategy === 'avoid').count, '规避计数').toBe(1);

    // 4) 界面再登记一条声明"减轻"的风险 -> 覆盖率升到75%, 总数4, 减轻·1.
    await fillRisk(page, { title: `质量整改风险-${suffix}`, strategyLabel: '减轻' });
    await save(page, '登记项目风险');
    await open(page, id, '需求与治理', '风险与问题');
    await expect(drawer(page).getByText(/风险总数\s*4/)).toBeVisible();
    await expect(drawer(page).getByText(/已声明应对策略\s*75%/)).toBeVisible();
    await expect(drawer(page).getByText(/减轻\s*·\s*1/)).toBeVisible();
    await expect(drawer(page).getByText(/未设定\s*1/)).toBeVisible();
    await shot(page, 'h08rc-2-after-declare.png');

    // 5) 只读派生不改变风险状态: 真实HTTP既有风险仍登记态且策略不漂移.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.risk_response_coverage['coverage-pct'], '再声明后覆盖率75%').toBe(75);
    const supply = ws2.risks.find(r => r.title === `供应中断风险-${suffix}`);
    expect(supply.status, '既有风险仍为登记态(只读派生不改状态)').toBe('open');
    expect(supply.response_strategy, '应对策略不漂移').toBe('transfer');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
