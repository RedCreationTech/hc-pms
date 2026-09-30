const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 延伸: 干系人可选"参与态度"枚举字段 (PMBOK 投入度评估矩阵).
// 界面"登记干系人"里可选选一个当前参与态度(未知晓/抵制/中立/支持/主导) -> 命令响应回显英文枚举值 ->
// 台账"参与态度"列以彩色中文标签徽标回显; 不选则显示灰字"未设定";
// 非法枚举经真实HTTP 400; 免迁移随 payload 持久化.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h02eg');
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

// 打开"登记干系人"表单, 填编号/名称/职责/分类/关注度/影响力; engagementLabel 给定则选参与态度.
// 停在保存前以便截图. 返回该干系人编号 code.
async function registerStakeholder(page, pid, code, engagementLabel) {
  await open(page, pid, '需求与治理', '干系人与沟通');
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

test.describe('H02 延伸 干系人参与态度可选枚举浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('界面选参与态度入台账列回显中文, 不选回退灰字未设定, 非法枚举经真实HTTP被拒', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `EG-${suffix}`, name: `参与态度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 1) 界面"登记干系人"选参与态度"支持", 保存前截图证明该可选枚举字段.
    const supporterCode = `SH-S-${suffix}`;
    await registerStakeholder(page, id, supporterCode, '支持');
    await shot(page, 'h02eg-1-dialog-engagement.png');
    const supporter = await save(page, '登记干系人');
    expect(supporter.result.engagement, '命令响应回显英文枚举值').toBe('supportive');

    // 2) 第二个干系人选"抵制".
    const resistorCode = `SH-R-${suffix}`;
    await registerStakeholder(page, id, resistorCode, '抵制');
    const resistor = await save(page, '登记干系人');
    expect(resistor.result.engagement, '命令响应回显英文枚举值').toBe('resistant');

    // 3) 第三个干系人不选参与态度 -> 留空回退未设定 (transform 把空值 dissoc 掉).
    const plainCode = `SH-P-${suffix}`;
    await registerStakeholder(page, id, plainCode, null);
    const plain = await save(page, '登记干系人');
    expect(plain.result.engagement ?? null, '未选则不回显态度').toBe(null);

    // 4) 台账"参与态度"列回显彩色中文标签, 未选定行显灰字"未设定".
    await open(page, id, '需求与治理', '干系人与沟通');
    await expect(row(page, supporterCode).locator('.ant-table-cell').filter({ hasText: /^支持$/ })).toBeVisible();
    await expect(row(page, resistorCode).locator('.ant-table-cell').filter({ hasText: /^抵制$/ })).toBeVisible();
    await expect(row(page, plainCode).locator('.ant-table-cell').filter({ hasText: /^未设定$/ })).toBeVisible();
    const table = row(page, supporterCode).locator('xpath=ancestor::table[1]');
    await table.scrollIntoViewIfNeeded();
    await page.waitForTimeout(200);
    await table.screenshot({ path: path.join(output, 'h02eg-2-ledger-engagement.png'), animations: 'disabled' });

    // 5) 读模型原样返回持久化字段 (英文枚举值 / 未设定为 null).
    const ws = await api(page, 'GET', base(id) + '/governance');
    const byCode = code => ws.stakeholders.find(s => s.code === code);
    expect(byCode(supporterCode).engagement, '支持方持久化为 supportive').toBe('supportive');
    expect(byCode(resistorCode).engagement, '抵制方持久化为 resistant').toBe('resistant');
    expect(byCode(plainCode).engagement ?? null, '未标注者无态度字段').toBe(null);

    // 6) 修订同一干系人保留编号并更新态度 (code 不可改, revision 递增).
    const v = (await api(page, 'GET', base(id))).version;
    const revised = await api(page, 'POST', base(id) + `/governance/stakeholders/${byCode(supporterCode).id}/revisions`,
      { code: supporterCode, name: `干系人${supporterCode}`, role: '验收配合', category: 'customer',
        interest: 'high', influence: 'high', engagement: 'leading', version: v });
    expect(revised.result.engagement, '修订后态度为主导').toBe('leading');
    expect(revised.result.revision, '修订号递增').toBe(2);
    await open(page, id, '需求与治理', '干系人与沟通');
    await expect(row(page, supporterCode).locator('.ant-table-cell').filter({ hasText: /^主导$/ })).toBeVisible();
    await row(page, supporterCode).scrollIntoViewIfNeeded();
    await page.waitForTimeout(200);
    await row(page, supporterCode).locator('xpath=ancestor::table[1]').screenshot({ path: path.join(output, 'h02eg-3-ledger-leading.png'), animations: 'disabled' });

    // 7) 真实HTTP拒绝非法枚举.
    const v2 = (await api(page, 'GET', base(id))).version;
    const badCode = await page.evaluate(async ({ pid, v }) => {
      const token = localStorage.getItem('ruoyi_token');
      const r = await fetch(`/api/pms/projects/${pid}/governance/stakeholders`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: 'SH-BAD', name: '非法态度', role: 'x', category: 'external',
          interest: 'high', influence: 'high', engagement: 'champion', version: v }) });
      return (await r.json()).code;
    }, { pid: id, v: v2 });
    expect(badCode, '非法参与态度应被拒').toBe(400);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
