const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H08 延伸: 风险类别 (risk_category) 可选 RBS 枚举字段.
// 界面"登记项目风险"里可选选一个风险类别(技术/外部/组织/进度/成本/质量) -> 命令响应回显英文枚举值 ->
// 台账"风险类别"列以中文标签徽标回显; 未选类别的行显示"未设定"; 非法枚举经真实HTTP 400; 免迁移随 payload 持久化.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h08rcat');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
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

// 打开"登记项目风险"填必填项; 若给定 categoryLabel 则再选风险类别; 停在保存前以便截图.
async function fillRisk(page, { title, categoryLabel }) {
  await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
  const form = modal(page, '登记项目风险');
  await form.locator('#title').fill(title);
  await form.locator('#probability').fill('2');
  await form.locator('#impact').fill('3');
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#mitigation').fill('持续监控并预留纠偏窗口.');
  await form.locator('#due_date').fill('2026-10-20');
  if (categoryLabel) await choose(page, form, 'risk_category', categoryLabel);
}

test.describe('H08 延伸 风险类别可选枚举浏览器验收', () => {
  test.setTimeout(180000);

  test('界面选风险类别入台账列回显中文, 未选显示未设定, 非法枚举经真实HTTP被拒', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RCT-${suffix}`, name: `风险类别验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '风险与问题');

    // 1) 界面登记带风险类别的风险 (2x3=6 不触发升级), 保存前截图证明该可选枚举字段.
    const withTitle = `第三方接口依赖风险-${suffix}`;
    await fillRisk(page, { title: withTitle, categoryLabel: '外部' });
    await shot(page, 'h08rcat-1-dialog-category.png');
    const created = await save(page, '登记项目风险');
    expect(created.result.risk_category, '命令响应回显英文枚举值').toBe('external');
    expect(created.result.escalated, '2x3=6 不触发升级').toBe(false);
    const rid = created.result.id;

    // 2) 界面登记未选类别的风险 -> 不含该键.
    const plainTitle = `常规观察风险-${suffix}`;
    await fillRisk(page, { title: plainTitle });
    const plain = await save(page, '登记项目风险');
    expect(plain.result.risk_category == null, '未选类别不含该键').toBeTruthy();

    // 3) 读模型原样返回持久化字段.
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.risks.find(r => r.id === rid).risk_category, '读模型回显类别').toBe('external');
    expect(ws.risks.find(r => r.id === plain.result.id).risk_category == null, '未选类别读模型为空').toBeTruthy();

    // 4) 台账"风险类别"列: 选过类别的行回显中文标签"外部", 未选行显示"未设定".
    await open(page, id, '需求与治理', '风险与问题');
    await expect(drawer(page).locator('.ant-tag', { hasText: '外部' }).first()).toBeVisible();
    await expect(drawer(page).locator('.ant-table-cell', { hasText: '未设定' }).first()).toBeVisible();
    await shot(page, 'h08rcat-2-ledger-column.png');

    // 5) 真实HTTP拒绝非法枚举.
    const v = (await api(page, 'GET', base(id))).version;
    const badCode = await page.evaluate(async ({ id, suffix, v }) => {
      const token = localStorage.getItem('ruoyi_token');
      const r = await fetch(`/api/pms/projects/${id}/governance/risks`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: `BAD-${suffix}`, probability: 2, impact: 3, owner_id: 1, mitigation: 'm', due_date: '2026-10-20', risk_category: 'not-a-real-category', version: v }) });
      return (await r.json()).code;
    }, { id, suffix, v });
    expect(badCode, '非法风险类别应被拒').toBe(400);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
