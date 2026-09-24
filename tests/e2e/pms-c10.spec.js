const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C10 典型风险库一键实例化: 界面从内置风险库选择条目 -> 服务端按库中标准概率x影响评分并套用措施/阶段 ->
// 高风险(供应5x5=25)自动进入超阈值升级待独立确认并标记"来源=风险库", 中低风险(技术方案3x3=9)不升级 ->
// 台账"来源"列以紫色标签区分风险库条目. 全程真实浏览器, 不新增治理记录类型(免迁移).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c10');
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

test.describe('C10 典型风险库一键实例化浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('风险库选用 -> 高风险自动升级并标记来源, 低风险不升级', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C10-${suffix}`, name: `风险库实例化验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 从风险库选用高风险条目 (供应类 5x5=25, 应自动升级到 steering 层待独立确认).
    await open(page, id, '需求与治理', '风险与问题');
    await drawer(page).getByRole('button', { name: '从典型风险库选用', exact: true }).click();
    let form = modal(page, '从典型风险库选用');
    await choose(page, form, 'template_key', '关键物料断供');
    await choose(page, form, 'owner_id', 'admin');
    await form.locator('#due_date').fill('2026-10-20');
    const high = await save(page, '从典型风险库选用');
    expect(high.result.title, '继承风险库标准标题').toBe('关键物料断供');
    expect(high.result.score, '评分按库中概率x影响').toBe(25);
    expect(high.result.escalated, '超阈值自动升级').toBe(true);
    expect(high.result.escalation_state).toBe('pending');
    expect(high.result.escalation_level).toBe('steering');
    expect(high.result.source_key).toBe('supply-outage');
    expect(high.result.source_category).toBe('supply');
    expect(high.result.stage, '继承风险库适用阶段').toBe('采购');

    // 从风险库选用中低风险条目 (技术方案 3x3=9, 不触发升级).
    await open(page, id, '需求与治理', '风险与问题');
    await drawer(page).getByRole('button', { name: '从典型风险库选用', exact: true }).click();
    form = modal(page, '从典型风险库选用');
    await choose(page, form, 'template_key', '关键技术方案不成熟');
    await choose(page, form, 'owner_id', 'admin');
    await form.locator('#due_date').fill('2026-11-01');
    const low = await save(page, '从典型风险库选用');
    expect(low.result.score).toBe(9);
    expect(low.result.escalated, '未达阈值不升级').toBe(false);
    expect(low.result.source_key).toBe('tech-uncertainty');

    // 台账: 高风险行显示"待升级确认 / steering" 且"来源"列标记"风险库".
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, '关键物料断供').getByText('待升级确认 / steering')).toBeVisible();
    await expect(row(page, '关键物料断供').getByText('风险库')).toBeVisible();
    await shot(page, 'c10-1-library-escalated.png');
    // 低风险行显示"未触发" 且"来源"列同样标记"风险库".
    await expect(row(page, '关键技术方案不成熟').getByText('未触发')).toBeVisible();
    await expect(row(page, '关键技术方案不成熟').getByText('风险库')).toBeVisible();
    await shot(page, 'c10-2-library-low.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
