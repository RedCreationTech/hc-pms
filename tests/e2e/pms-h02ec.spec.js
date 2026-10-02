const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: 干系人参与态度(PMBOK 投入度)覆盖度只读派生洞察 (免迁移, 无新命令/新kind).
// 界面"登记干系人"里为不同干系人选参与态度(未知晓/抵制/中立/支持/主导) -> "干系人与沟通"页签新增只读"参与态度覆盖度"面板:
// 按每个干系人最新有效版本聚合五类参与态度计数 + 已声明覆盖率百分比 + 未设定计数; 再声明一条后覆盖率上升.
// 只读派生不改变干系人状态 (既有干系人仍 active, 态度不漂移).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h02ec');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
// shared/panel 渲染 <section> 内含 <h3> 标题; "未设定"标签在台账 engagement-cell 逐行也出现, 须按面板作用域定位避免 strict-mode 串台.
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
const base = id => `/api/pms/projects/${id}`;

// test.use 必须置于文件顶层 (放进 describe 会强制新 worker 报错).
test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

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
  const option = dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first();
  await expect(option).toBeVisible();
  await option.click();
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

// 打开"登记干系人"填必填项; engagementLabel 给定则选参与态度 (未知晓/抵制/中立/支持/主导).
async function registerStakeholder(page, code, engagementLabel) {
  await drawer(page).getByRole('button', { name: '登记干系人', exact: true }).click();
  const form = modal(page, '登记干系人');
  await expect(form).toBeVisible();
  await form.locator('#code').fill(code);
  await form.locator('#name').fill(`干系人${code}`);
  await form.locator('#role').fill('需求方接口');
  await choose(page, form, 'category', '客户');
  await choose(page, form, 'interest', 'high');
  await choose(page, form, 'influence', 'high');
  if (engagementLabel) await choose(page, form, 'engagement', engagementLabel);
}

test.describe('H02 参与态度覆盖度只读洞察浏览器验收', () => {
  test.setTimeout(180000);

  test('覆盖度面板按最新干系人聚合五类态度与覆盖率, 再声明一条后覆盖率上升', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `EC-${suffix}`, name: `参与态度覆盖度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '干系人与沟通');

    // 1) 界面登记四位干系人: 支持 / 主导 / 抵制 / 不选参与态度.
    await registerStakeholder(page, `EC-S-${suffix}`, '支持');
    await save(page, '登记干系人');
    await registerStakeholder(page, `EC-L-${suffix}`, '主导');
    await save(page, '登记干系人');
    await registerStakeholder(page, `EC-R-${suffix}`, '抵制');
    await save(page, '登记干系人');
    await registerStakeholder(page, `EC-P-${suffix}`, null);
    await save(page, '登记干系人');

    // 2) 覆盖度面板: 总数4, 已声明3 -> 75%, 未设定1; 支持·1 主导·1 抵制·1 未知晓·0 中立·0.
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov = panel(page, '参与态度覆盖度');
    await expect(cov).toBeVisible();
    await expect(cov.getByText(/干系人总数\s*4/)).toBeVisible();
    await expect(cov.getByText(/已声明参与态度\s*75%/)).toBeVisible();
    await expect(cov.getByText(/未设定\s*1/)).toBeVisible();
    await expect(cov.getByText(/支持\s*·\s*1/)).toBeVisible();
    await expect(cov.getByText(/主导\s*·\s*1/)).toBeVisible();
    await expect(cov.getByText(/抵制\s*·\s*1/)).toBeVisible();
    await expect(cov.getByText(/未知晓\s*·\s*0/)).toBeVisible();
    await expect(cov.getByText(/中立\s*·\s*0/)).toBeVisible();
    await shot(page, 'h02ec-1-coverage-panel.png');

    // 3) 真实HTTP读模型回显 stakeholder_engagement_coverage.
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.stakeholder_engagement_coverage.total, '覆盖度分母=最新有效版本干系人数').toBe(4);
    expect(ws.stakeholder_engagement_coverage.declared, '已声明数').toBe(3);
    expect(ws.stakeholder_engagement_coverage.undeclared, '未声明数').toBe(1);
    expect(ws.stakeholder_engagement_coverage['coverage-pct'], '覆盖率百分比').toBe(75);
    const byE = k => ws.stakeholder_engagement_coverage['by-engagement'].find(c => c.engagement === k).count;
    expect(byE('supportive'), '支持计数').toBe(1);
    expect(byE('leading'), '主导计数').toBe(1);
    expect(byE('resistant'), '抵制计数').toBe(1);
    expect(byE('unaware'), '未知晓计数').toBe(0);
    expect(byE('neutral'), '中立计数').toBe(0);

    // 4) 界面再登记一位声明"中立"的干系人 -> 覆盖率升到80%, 总数5, 中立·1.
    await open(page, id, '需求与治理', '干系人与沟通');
    await registerStakeholder(page, `EC-N-${suffix}`, '中立');
    await save(page, '登记干系人');
    await open(page, id, '需求与治理', '干系人与沟通');
    const cov2 = panel(page, '参与态度覆盖度');
    await expect(cov2.getByText(/干系人总数\s*5/)).toBeVisible();
    await expect(cov2.getByText(/已声明参与态度\s*80%/)).toBeVisible();
    await expect(cov2.getByText(/中立\s*·\s*1/)).toBeVisible();
    await expect(cov2.getByText(/未设定\s*1/)).toBeVisible();
    await shot(page, 'h02ec-2-after-declare.png');

    // 5) 只读派生不改变干系人状态: 真实HTTP既有干系人仍 active 且态度不漂移.
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.stakeholder_engagement_coverage['coverage-pct'], '再声明后覆盖率80%').toBe(80);
    const supporter = ws2.stakeholders.find(s => s.code === `EC-S-${suffix}`);
    expect(supporter.status, '既有干系人仍为 active (只读派生不改状态)').toBe('active');
    expect(supporter.engagement, '参与态度不漂移').toBe('supportive');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
