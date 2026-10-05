const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H09ct: 变更请求类型 (change_type) 可选枚举字段 (PMBOK 四类: 纠错性/预防性/缺陷修复/更新).
// 界面"提出项目变更"里可选选一个变更类型 -> 命令响应回显英文枚举值 ->
// 台账"变更类型"列以中文标签彩色徽标回显; 未选类型显示"未设定"; 非法枚举经真实HTTP 400;
// change_type 为变更专属字段, 追加到章程体被白名单拒绝; 免迁移随 payload 持久化.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h09ct');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const changeForm = page => modal(page, '提出项目变更');
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

// 打开"提出项目变更"填五维必填影响; 若给定 typeLabel 则再选可选变更类型; 停在保存前以便截图.
async function fillChange(page, { title, typeLabel }) {
  await drawer(page).getByRole('button', { name: '提出项目变更', exact: true }).click();
  const form = changeForm(page);
  await form.locator('#title').fill(title);
  await form.locator('#reason').fill('合同范围调整');
  await form.locator('#scope_impact').fill('新增两台设备');
  await form.locator('#schedule_impact').fill('工期相应延长');
  await form.locator('#cost_impact').fill('需要重新估价');
  await form.locator('#quality_impact').fill('追加联调测试');
  await form.locator('#resource_impact').fill('追加一名工程师');
  if (typeLabel) await choose(page, form, 'change_type', typeLabel);
}

test.describe('H09ct 变更请求类型可选枚举浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('界面选变更类型入台账列回显中文, 未选显示未设定, 非法枚举经真实HTTP被拒, 章程体拒绝', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H09CT-${suffix}`, name: `变更类型验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '变更控制');

    // 1) 界面登记一条选"预防性"(preventive)的变更, 保存前截图证明该可选枚举字段.
    const preTitle = `设备增补变更-${suffix}`;
    await fillChange(page, { title: preTitle, typeLabel: '预防性' });
    await shot(page, 'h09ct-1-dialog-type.png');
    const pre = await save(page, '提出项目变更');
    expect(pre.result.change_type, '命令响应回显英文枚举值').toBe('preventive');
    const preId = pre.result.id;

    // 2) 界面登记一条选"更新"(updates)的变更.
    const updTitle = `流程更新变更-${suffix}`;
    await fillChange(page, { title: updTitle, typeLabel: '更新' });
    const upd = await save(page, '提出项目变更');
    expect(upd.result.change_type, '命令响应回显英文枚举值').toBe('updates');
    const updId = upd.result.id;

    // 3) 界面登记一条未选类型的变更 -> 不含该键 (零回归).
    const plainTitle = `常规观察变更-${suffix}`;
    await fillChange(page, { title: plainTitle });
    const plain = await save(page, '提出项目变更');
    expect(plain.result.change_type == null, '未选类型不含该键').toBeTruthy();

    // 4) workspace 变更读模型原样回显持久化字段.
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.changes.find(c => c.id === preId).change_type, '读模型回显预防性').toBe('preventive');
    expect(ws.changes.find(c => c.id === updId).change_type, '读模型回显更新').toBe('updates');
    expect(ws.changes.find(c => c.id === plain.result.id).change_type == null, '未选类型读模型为空').toBeTruthy();

    // 5) 台账"变更类型"列: 选过类型的行回显中文标签徽标, 未选行显示"未设定".
    await open(page, id, '需求与治理', '变更控制');
    await expect(drawer(page).locator('.ant-table-cell', { hasText: '预防性' }).first()).toBeVisible();
    await expect(drawer(page).locator('.ant-table-cell', { hasText: '更新' }).first()).toBeVisible();
    await expect(drawer(page).locator('.ant-table-cell', { hasText: '未设定' }).first()).toBeVisible();
    await shot(page, 'h09ct-2-ledger-column.png');

    // 6) 真实HTTP拒绝非法变更类型枚举 (免迁移, 随 payload 校验).
    const v = (await api(page, 'GET', base(id))).version;
    const badCode = await page.evaluate(async ({ id, suffix, v }) => {
      const token = localStorage.getItem('ruoyi_token');
      const r = await fetch(`/api/pms/projects/${id}/governance/changes`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: `BAD-${suffix}`, reason: 'r', scope_impact: 's', schedule_impact: 's', cost_impact: 'c',
          quality_impact: 'q', resource_impact: 'r', change_type: 'not-a-type', version: v }) });
      return (await r.json()).code;
    }, { id, suffix, v });
    expect(badCode, '非法变更类型应被拒').toBe(400);

    // 7) 变更类型是变更专属字段: 追加到章程体被白名单拒绝 (400).
    const badCharter = await page.evaluate(async ({ id, suffix }) => {
      const token = localStorage.getItem('ruoyi_token');
      const version = (await (await fetch(`/api/pms/projects/${id}`, { headers: { Authorization: `Bearer ${token}` } })).json()).data.version;
      const r = await fetch(`/api/pms/projects/${id}/governance/charters`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: `CHA-${suffix}`, objective: 'o', scope: 's', success_criteria: 'c', sponsor_id: 1, change_type: 'corrective', version }) });
      return (await r.json()).code;
    }, { id, suffix });
    expect(badCharter, '变更类型为变更专属字段, 章程体拒绝').toBe(400);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
