const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H10 延伸: DQ 编制时可选预先声明"检验方法"(枚举)与"执行角色"(自由文本) (免迁移, payload 存字段, 无新kind/命令/路由).
// 真实HTTP建交付件文档 -> 界面"建立DQ关键任务": 选检验方法"检验"(inspection)+填执行角色"计量工程师" ->
//   台账"检验方法"列显示蓝色标签"检验", "执行角色"列显示"计量工程师"; 汇总面板显示"检验方法已声明 1"+"执行角色已指定 1";
//   另建一条未声明任何字段的 DQ -> 其"检验方法"/"执行角色"两列均显示"未设定" (零回归), 汇总计数仍各为 1;
//   真实HTTP GET /governance 回显 dq1.check_method=inspection / dq1.responsible_role=计量工程师 / dq2 无此二键 /
//   dq_summary.methods-declared=1 / dq_summary.roles-declared=1, 与界面一致;
//   防御性: 非法检验方法 (check_method=audit) 经真实HTTP建 DQ -> 400 (写入前抛出, 不产生记录).
// 全程真实点击 + 真实HTTP + 真实浏览器截图, 免迁移读模型仅界面可见, 未声明时零回归.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h10dqmr');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
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

// 命令写操作: 拉取当前项目版本, 带版本发命令. 返回命令结果记录 (data.result).
async function command(page, id, suffix, data = {}, expected = 200) {
  const project = await api(page, 'GET', base(id));
  const result = await api(page, 'POST', base(id) + '/governance' + suffix, { ...data, version: project.version }, expected);
  return result && result.result;
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
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
}

async function save(page, title, expected = 200) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(expected);
  if (expected === 200) { await expect(form).toBeHidden(); await page.waitForLoadState('networkidle'); }
  return body;
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function shotCard(page, card, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('H10 延伸 DQ 检验方法与执行角色声明浏览器验收', () => {
  test.setTimeout(180000);

  test('声明检验方法+执行角色 -> 台账徽标/文本与汇总计数可见, 未声明零回归, 非法方法 400', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `MR-${suffix}`, name: `DQ检验方法角色声明验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const doc = await command(page, id, '/documents', { code: `MRDOC-${suffix}`, title: `交付件-${suffix}`, filename: '交付.txt', content: '确定版本交付件\n' });

    // 防御性门控 (真实HTTP, 写入前抛出 -> 不产生记录): 非法检验方法 -> 400.
    await command(page, id, '/dqs', { code: `MRBAD-${suffix}`, title: `非法方法-${suffix}`, owner_id: adminId,
      checklist: [{ code: 'D1', title: '检查项', required: true }], deliverable_ids: [doc.id], check_method: 'audit' }, 400);

    // 界面第一轮: 建立 DQ 并声明检验方法"检验" + 执行角色"计量工程师".
    const dq1Title = `H10检验方法主任务-${suffix}`;
    const dq2Title = `H10未声明任务-${suffix}`;
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await drawer(page).getByRole('button', { name: '建立DQ关键任务', exact: true }).click();
    let form = modal(page, '建立DQ关键任务');
    await fill(form, { code: `MRDQ1-${suffix}`, title: dq1Title, check_titles: '关键尺寸测量到位\n外观检验合格' });
    await choose(page, form, 'owner_id', 'admin');
    await choose(page, form, 'deliverable_ids', `MRDOC-${suffix}`);
    await choose(page, form, 'check_method', '检验');
    await form.locator('#responsible_role').fill('计量工程师');
    await shot(page, 'h10dqmr-1-dialog.png');
    const dq1 = (await save(page, '建立DQ关键任务')).data.result;

    // 界面第二轮: 建一条完全不声明的 DQ (零回归基线).
    await drawer(page).getByRole('button', { name: '建立DQ关键任务', exact: true }).click();
    form = modal(page, '建立DQ关键任务');
    await fill(form, { code: `MRDQ2-${suffix}`, title: dq2Title, check_titles: '包装防护到位' });
    await choose(page, form, 'owner_id', 'admin');
    await choose(page, form, 'deliverable_ids', `MRDOC-${suffix}`);
    await save(page, '建立DQ关键任务');
    const wsList = await api(page, 'GET', base(id) + '/governance');
    const dq2 = wsList.dqs.find(x => x.title === dq2Title);

    // 重载: 声明项在台账显示蓝色"检验"标签 + "计量工程师"文本; 未声明项两列均"未设定".
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    const ledger = panel(page, 'DQ 编制与确认');
    await expect(ledger.getByRole('columnheader', { name: '检验方法', exact: true })).toBeVisible();
    await expect(ledger.getByRole('columnheader', { name: '执行角色', exact: true })).toBeVisible();
    await expect(row(page, dq1Title).getByText('检验', { exact: true })).toBeVisible();
    await expect(row(page, dq1Title).getByText('计量工程师', { exact: true })).toBeVisible();
    await expect(row(page, dq2Title).getByText('未设定', { exact: true })).toHaveCount(2);
    await shotCard(page, ledger, 'h10dqmr-2-ledger.png');

    // 汇总面板: 已声明计数各为 1.
    const sum = panel(page, 'DQ 质量检查闭环汇总');
    await expect(sum.getByText(/检验方法已声明\s*1/)).toBeVisible();
    await expect(sum.getByText(/执行角色已指定\s*1/)).toBeVisible();
    await shotCard(page, sum, 'h10dqmr-3-summary.png');

    // 真实HTTP读模型回显, 与界面一致.
    let ws = await api(page, 'GET', base(id) + '/governance');
    let d1 = ws.dqs.find(x => x.id === dq1.id);
    let d2 = ws.dqs.find(x => x.id === dq2.id);
    expect(d1.check_method, '检验方法枚举原样回显').toBe('inspection');
    expect(d1.responsible_role, '执行角色文本原样回显').toBe('计量工程师');
    expect('check_method' in d2, '未声明 DQ 不含 check_method 键').toBe(false);
    expect('responsible_role' in d2, '未声明 DQ 不含 responsible_role 键').toBe(false);
    expect(ws.dq_summary['methods-declared'], '已声明检验方法 DQ 数 1').toBe(1);
    expect(ws.dq_summary['roles-declared'], '已指定执行角色 DQ 数 1').toBe(1);

    // 空白执行角色 (真实HTTP "   ") 视为未声明, 不落键.
    const dqBlank = await command(page, id, '/dqs', { code: `MRDQ3-${suffix}`, title: `空白角色-${suffix}`, owner_id: adminId,
      checklist: [{ code: 'D1', title: '检查项', required: true }], deliverable_ids: [doc.id], check_method: 'test', responsible_role: '   ' });
    expect(dqBlank.check_method, '空白仍保留合法检验方法').toBe('test');
    expect('responsible_role' in dqBlank, '空白执行角色不落键').toBe(false);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
