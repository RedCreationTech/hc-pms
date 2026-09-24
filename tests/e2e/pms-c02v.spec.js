const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C02 延伸: 需求验证方式 (verification_method) 可选枚举字段 (免迁移).
// "新增URS需求"里可选选一个验证方式(测试/检验/演示/分析) -> 命令响应回显英文枚举值 ->
// 台账"验证方式"列以中文标签徽标回显; 未选的行显示"未设定"; 非法枚举经真实HTTP 400;
// 批量导入(五列CSV)不受影响, 导入行不含验证方式键. 修订版可改验证方式, 原版保持原值.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c02v');
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
  // 共享库累积用户/任务后长列表被 antd 虚拟滚动裁剪, 可搜索的下拉先按标签过滤再选.
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

// 打开"新增URS需求"填必填项; 若给定 methodLabel 则再选验证方式; 停在保存前以便截图.
async function fillRequirement(page, { code, text, methodLabel }) {
  await drawer(page).getByRole('button', { name: '新增URS需求', exact: true }).click();
  const form = modal(page, '新增URS需求');
  await form.locator('#code').fill(code);
  await form.locator('#text').fill(text);
  await form.locator('#category').fill('功能');
  await choose(page, form, 'owner_id', 'admin');
  if (methodLabel) await choose(page, form, 'verification_method', methodLabel);
}

test.describe('C02 延伸 需求验证方式可选枚举浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('界面选验证方式入台账列回显中文, 未选显示未设定, 修订改值原版保持, 非法枚举经真实HTTP被拒', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RVM-${suffix}`, name: `需求验证方式验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', 'URS与追踪');

    // 1) 界面登记带验证方式的需求 (测试), 保存前截图证明该可选枚举字段.
    const withCode = `URS-VM-${suffix}`;
    await fillRequirement(page, { code: withCode, text: '控制器固件须支持远程升级.', methodLabel: '测试' });
    await shot(page, 'c02v-1-dialog-method.png');
    const created = await save(page, '新增URS需求');
    expect(created.result.verification_method, '命令响应回显英文枚举值').toBe('test');
    const rid = created.result.id;

    // 2) 界面登记未选验证方式的需求 -> 不含该键.
    const plainCode = `URS-PLAIN-${suffix}`;
    await fillRequirement(page, { code: plainCode, text: '面板须达到防水等级.' });
    const plain = await save(page, '新增URS需求');
    expect(plain.result.verification_method == null, '未选验证方式不含该键').toBeTruthy();

    // 3) 读模型原样返回持久化字段.
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.requirements.find(r => r.id === rid).verification_method, '读模型回显验证方式').toBe('test');
    expect(ws.requirements.find(r => r.id === plain.result.id).verification_method == null, '未选验证方式读模型为空').toBeTruthy();

    // 4) 修订版改验证方式为"演示", 原版仍保持"测试".
    const v = (await api(page, 'GET', base(id))).version;
    const rev = await api(page, 'POST', base(id) + `/governance/requirements/${rid}/revisions`,
      { code: withCode, text: '控制器固件须支持远程升级 (补充).', category: '功能', priority: 'required', owner_id: adminId, verification_method: 'demonstration', version: v });
    expect(rev.result.verification_method, '修订回显新验证方式').toBe('demonstration');
    expect(rev.result.revision, '修订版本递增').toBe(2);
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.requirements.find(r => r.id === rid).verification_method, '原版仍为测试').toBe('test');

    // 5) 台账"验证方式"列: 修订版回显"演示", 未选行显示"未设定".
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(drawer(page).locator('.ant-table-cell', { hasText: '演示' }).first()).toBeVisible();
    await expect(drawer(page).locator('.ant-table-cell', { hasText: '未设定' }).first()).toBeVisible();
    await shot(page, 'c02v-2-ledger-column.png');

    // 6) 批量导入五列CSV不受影响, 导入行不含验证方式键.
    const v2 = (await api(page, 'GET', base(id))).version;
    const imported = await api(page, 'POST', base(id) + '/governance/requirements/import',
      { csv: `code,text,category,priority,owner_id\nURS-CSV-${suffix},批量导入需求,功能,required,${adminId}\n`, version: v2 });
    expect(imported.result.rows.length, '批量导入一条').toBe(1);
    const ws3 = await api(page, 'GET', base(id) + '/governance');
    expect(ws3.requirements.find(r => r.code === `URS-CSV-${suffix}`).verification_method == null, '导入行不含验证方式').toBeTruthy();

    // 7) 真实HTTP拒绝非法枚举.
    const v3 = (await api(page, 'GET', base(id))).version;
    const badCode = await page.evaluate(async ({ id, suffix, v3, adminId }) => {
      const token = localStorage.getItem('ruoyi_token');
      const r = await fetch(`/api/pms/projects/${id}/governance/requirements`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: `BAD-${suffix}`, text: '非法验证方式', category: '功能', priority: 'required', owner_id: adminId, verification_method: 'vibes', version: v3 }) });
      return (await r.json()).code;
    }, { id, suffix, v3, adminId });
    expect(badCode, '非法验证方式应被拒').toBe(400);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
