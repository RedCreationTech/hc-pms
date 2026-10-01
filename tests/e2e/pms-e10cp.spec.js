const { test, expect } = require('@playwright/test');
const path = require('node:path');

// E10 收尾清单闭环进度只读汇总 (只读派生): 结项与移交 页签内"收尾闭环进度"面板, 聚合收尾检查项与
// 交付移交项的完成进度/必需未完成/逾期未完成/经验数与关闭审批状态 (免迁移/无门控/无新命令/无新路由).
// 数据来自 closure workspace 顶层 :progress; 逐条完成/提交/审批仍由服务端状态机与证据门控在写入时强制.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/e10cp');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '收尾闭环进度', exact: true }) }).first();

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

async function mutate(page, id, suffix, data = {}, expected = 200) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version }, expected);
}

async function tab(page, name) {
  await drawer(page).getByRole('tab', { name, exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function open(page, id, section) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  if (section) await tab(page, section);
}

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
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

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '收尾闭环进度', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, label) {
  const suffix = serial();
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `E10-${suffix}`, name: `${label} ${suffix}`,
    project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  // 结项证据必须是已登记不可变文档版本; 经真实 HTTP 建一份, 供界面完成检查时选择.
  const evidence = (await mutate(page, id, '/governance/documents', { code: 'E10-EV', title: '结项交付证据', filename: 'e10.txt', content: '合成结项证据.' })).result;
  return { id, suffix, adminId: options.currentUserId, evidence };
}

test.describe('E10 收尾清单闭环进度只读汇总', () => {
  test.setTimeout(240000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 检查(必需,完成)+移交(必需,逾期未完成)+经验, 面板与 :progress 一致且只读不门控', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await fixture(page, '收尾闭环进度');
    const id = f.id;
    const checkTitle = `收尾检查-${f.suffix}`;
    const handoffTitle = `遗留移交-${f.suffix}`;

    // 1. 尚无收尾事项 -> 面板空态.
    await open(page, id, '结项与移交');
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无收尾事项, 添加检查项或移交事项后跟踪结项进度')).toBeVisible();
    await shot(page, 'e10cp-1-empty.png');
    await shotPanel(page, 'e10cp-1-empty-panel.png');
    let p = (await api(page, 'GET', base(id) + '/closure')).progress;
    expect(p.available).toBe(false);
    expect(p.total).toBe(0);
    expect(p['closure-pct']).toBe(0);
    expect(p['approval-state']).toBe('none');

    // 2. 界面新增: 1 必需检查项 (transform 强制 required), 1 逾期移交项, 1 条经验.
    await drawer(page).getByRole('button', { name: '添加检查项', exact: true }).click();
    await fill(modal(page, '添加收尾检查项'), { title: checkTitle });
    await save(page, '添加收尾检查项');

    await open(page, id, '结项与移交');
    await drawer(page).getByRole('button', { name: '新增移交事项', exact: true }).click();
    const hform = modal(page, '新增交付移交');
    await fill(hform, { title: handoffTitle, due_date: '2000-01-01' });
    await choose(page, hform, 'owner_id', /\/ admin$/);
    await save(page, '新增交付移交');

    await open(page, id, '结项与移交');
    await drawer(page).getByRole('button', { name: '登记项目经验', exact: true }).click();
    await fill(modal(page, '登记项目经验'), { title: `交付经验-${f.suffix}`, category: '流程', content: '证据先行登记, 结项更顺畅.' });
    await save(page, '登记项目经验');

    // 完成检查项 (需绑定已登记证据版本 + 完成说明), 移交项保持未完成 (逾期).
    await open(page, id, '结项与移交');
    await row(page, checkTitle).getByRole('button', { name: '确认完成', exact: true }).click();
    const cform = modal(page, '完成收尾检查');
    await choose(page, cform, 'evidence_ref', 'E10-EV');
    await fill(cform, { comment: '已核对交付证据.' });
    await save(page, '完成收尾检查');

    // 3. 聚合面板: 收尾项2/已完成1/待完成1/必需未完成1(移交)/逾期未完成1/经验1/关闭审批未提交/闭环率50%.
    await open(page, id, '结项与移交');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('收尾项 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已完成 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('待完成 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('必需未完成 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('逾期未完成 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('经验 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('关闭审批 未提交', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('收尾闭环率 50%', { exact: true })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '收尾检查', exact: true })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '遗留移交', exact: true })).toBeVisible();
    await shot(page, 'e10cp-2-aggregated.png');
    await shotPanel(page, 'e10cp-2-aggregated-panel.png');

    p = (await api(page, 'GET', base(id) + '/closure')).progress;
    expect(p).toMatchObject({ available: true, total: 2, checks: 1, handoffs: 1, completed: 1, open: 1,
      required: 2, 'required-open': 1, 'overdue-open': 1, lessons: 1, 'approval-state': 'none', 'closure-pct': 50 });
    expect(p['by-kind'].map(x => x.key)).toEqual(['check', 'handoff']);
    expect(p['by-kind'][0]).toMatchObject({ total: 1, completed: 1, 'completed-pct': 100 });
    expect(p['by-kind'][1]).toMatchObject({ total: 1, completed: 0, 'completed-pct': 0 });

    // 4. 门控仍在: 只读汇总不放宽写入约束 -> 项目非 closing 时提交关闭审批真实 409.
    await mutate(page, id, '/closure/submit', { reviewer_id: f.adminId }, 409);

    expect(errors).toEqual([]);
  });
});
