const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C02 延伸(二): 需求验证方式覆盖度只读派生洞察 (免迁移, 无新命令/新kind).
// 界面登记若干带/不带验证方式的需求 -> "URS与追踪"页签新增只读"验证方式覆盖度"面板:
// 按每个编号最新版本聚合四类方法计数 + 已声明覆盖率百分比 + 未设定计数; 修订补声明后覆盖率上升而总数不变.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c02v2');
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
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

async function fillRequirement(page, { code, text, methodLabel }) {
  await drawer(page).getByRole('button', { name: '新增URS需求', exact: true }).click();
  const form = modal(page, '新增URS需求');
  await form.locator('#code').fill(code);
  await form.locator('#text').fill(text);
  await form.locator('#category').fill('功能');
  await choose(page, form, 'owner_id', 'admin');
  if (methodLabel) await choose(page, form, 'verification_method', methodLabel);
}

test.describe('C02 延伸 需求验证方式覆盖度只读洞察浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('覆盖度面板按最新版本聚合四类方法与覆盖率, 修订补声明后覆盖率升至100%而总数不变', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RVC-${suffix}`, name: `需求验证覆盖度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', 'URS与追踪');

    // 1) 界面登记三条需求: 测试 / 检验 / 不选验证方式.
    await fillRequirement(page, { code: `URS-A-${suffix}`, text: '控制器固件须支持远程升级.', methodLabel: '测试' });
    const a = await save(page, '新增URS需求');
    await fillRequirement(page, { code: `URS-B-${suffix}`, text: '面板须达到防水等级.', methodLabel: '检验' });
    await save(page, '新增URS需求');
    await fillRequirement(page, { code: `URS-C-${suffix}`, text: '整机噪声须低于阈值.' });
    await save(page, '新增URS需求');

    // 2) 覆盖度面板: 总数3, 已声明2 -> 67%, 未设定1, 测试·1 检验·1 演示·0 分析·0.
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(drawer(page).getByText('验证方式覆盖度', { exact: true })).toBeVisible();
    await expect(drawer(page).getByText(/最新版本需求\s*3/)).toBeVisible();
    await expect(drawer(page).getByText(/已声明验证方式\s*67%/)).toBeVisible();
    await expect(drawer(page).getByText(/未设定\s*1/)).toBeVisible();
    await expect(drawer(page).getByText(/测试\s*·\s*1/)).toBeVisible();
    await expect(drawer(page).getByText(/检验\s*·\s*1/)).toBeVisible();
    await expect(drawer(page).getByText(/演示\s*·\s*0/)).toBeVisible();
    await expect(drawer(page).getByText(/分析\s*·\s*0/)).toBeVisible();
    await shot(page, 'c02v2-1-coverage-panel.png');

    // 3) 真实HTTP读模型回显 verification_coverage.
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.verification_coverage.total, '覆盖度分母=最新版本需求数').toBe(3);
    expect(ws.verification_coverage.declared, '已声明数').toBe(2);
    expect(ws.verification_coverage.undeclared, '未声明数').toBe(1);
    expect(ws.verification_coverage['coverage-pct'], '覆盖率百分比').toBe(67);
    expect(ws.verification_coverage['by-method'].find(m => m.method === 'test').count, '测试计数').toBe(1);

    // 4) 修订给"未声明"的 URS-C 补上验证方式"演示" -> 覆盖率升至100%, 总数仍3, 未设定标签消失.
    const v = (await api(page, 'GET', base(id))).version;
    const rev = await api(page, 'POST', base(id) + `/governance/requirements/${(ws.requirements.find(r => r.code === `URS-C-${suffix}`)).id}/revisions`,
      { code: `URS-C-${suffix}`, text: '整机噪声须低于阈值 (补验证方式).', category: '功能', priority: 'desired', owner_id: adminId, verification_method: 'demonstration', version: v });
    expect(rev.result.verification_method, '修订回显演示').toBe('demonstration');

    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(drawer(page).getByText(/最新版本需求\s*3/)).toBeVisible();
    await expect(drawer(page).getByText(/已声明验证方式\s*100%/)).toBeVisible();
    await expect(drawer(page).getByText(/演示\s*·\s*1/)).toBeVisible();
    await expect(drawer(page).getByText(/未设定\s*1/)).toHaveCount(0);
    await shot(page, 'c02v2-2-coverage-full.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
