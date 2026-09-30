const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H04/B02 任务级基线进度偏差只读派生: 计划与执行 -> WBS与排程 顶部"基线进度偏差"面板与逐任务"基线偏差"列.
// 取最新一条"已批准"计划基线冻结排程, 与当前排程逐任务只读比较: 完成日延后=behind(延后), 提前=ahead(提前),
// 持平=on_baseline, 基线中不存在的任务=added(新增). 免迁移/免新kind/免新命令/免新路由, 不构成任何门控
// (偏差只读呈现, 既不阻断编辑也不阻断提交冻结). 流程: 建两条叶任务 -> admin 提交计划审批冻结 ->
// 独立审核人(pms:plan:approve)批准基线 -> 延长任务A工期使其完成日晚于基线(behind) + 新增任务C(added),
// B保持不变(持平). 期望 面板 任务3/持平1/延后1/新增1/最大完成延后>0; 列显示 A 延后(+N天)/B 持平/C 新增;
// GET /planning 服务端回显二次确认. 最后空态: 新项目有任务但无已批准基线 -> 面板"尚无已批准计划基线", 列"无基线".
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h04bv');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const base = id => `/api/pms/projects/${id}`;

async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

async function api(page, method, url, data, expected = 200, methodTimeout = 30000) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data, timeout: methodTimeout });
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

async function mutate(page, id, suffix, data = {}, expected = 200, verb = 'POST') {
  const project = await api(page, 'GET', base(id));
  return api(page, verb, base(id) + suffix, { ...data, version: project.version }, expected);
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
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
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

async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 通过公开 UI 新建一条叶 WBS 任务 (type 留空用默认"任务").
async function wbsTask(page, code, name, days) {
  await drawer(page).getByRole('button', { name: '新建WBS任务', exact: true }).click();
  const form = modal(page, '新建WBS任务');
  await fill(form, { wbs_code: code, name, duration_days: days, start_date: '2026-09-22' });
  await choose(page, form, 'owner_id', /\/ admin$/);
  return (await save(page, '新建WBS任务')).data.result;
}

// 独立审核人: 授予 pms:plan:approve 的角色, 新用户, 独立浏览器上下文, 以及以该上下文身份对项目发起命令的辅助函数.
async function createReviewer(page, browser, id, suffix) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `基线偏差审批${suffix}`, role_key: `pms_bv_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(item => item.role_key === `pms_bv_${suffix}`).role_id;
  const username = `pms_bv_${suffix}`;
  const password = `E2e!${suffix}`;
  const deptId = (await api(page, 'GET', '/api/pms/options')).depts.find(d => d.dept_name === '研发部门').dept_id;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立基线审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3100', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  const command = (suffix2, data) => mutate(reviewer, id, suffix2, data).then(r => r.result);
  return { userId, reviewer, context, command };
}

test.describe('H04/B02 任务基线进度偏差只读派生浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('批准基线后延长A并新增C -> 面板与逐任务基线偏差列可见, 服务端回显一致, 空态无已批准基线', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H04BV-${suffix}`, name: `基线进度偏差验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E立项前置' });
    await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E计划前置' });

    // 空态截图: 建一条叶任务但尚无已批准基线 -> 面板"尚无已批准计划基线", 列"无基线".
    await open(page, id, '计划与执行', 'WBS与排程');
    await wbsTask(page, '1', '设计基线任务A', 2);
    await wbsTask(page, '2', '测试基线任务B', 3);
    const emptyPanel = panel(page, '基线进度偏差');
    await expect(emptyPanel.getByText('尚无已批准计划基线', { exact: true })).toBeVisible();
    await expect(row(page, '设计基线任务A').locator('.ant-tag').filter({ hasText: '无基线' })).toBeVisible();
    await panelShot(page, '基线进度偏差', 'h04bv-3-empty.png');

    // 独立审核人上下文.
    const rv = await createReviewer(page, browser, id, suffix);

    // admin 提交计划审批冻结基线 (UI), 独立审核人批准基线 (API), 偏差比较的基线由此冻结.
    await open(page, id, '计划与执行', '审批与基线');
    await drawer(page).getByRole('button', { name: '提交计划审批', exact: true }).click();
    await fill(modal(page, '提交计划审批'), { comment: '冻结A/B基线排程, 供偏差只读比较' });
    await save(page, '提交计划审批');
    await expect(drawer(page).getByText('计划已冻结,等待独立审批.')).toBeVisible();
    const frozen = await api(page, 'GET', base(id) + '/planning');
    const baseline = frozen.baselines.find(b => b.status !== 'approved') || frozen.baselines[0];
    expect(baseline, '提交后应存在一条待审批基线').toBeTruthy();
    await rv.command(`/planning/baselines/${baseline.baseline_id}/review`, { decision: 'approved', comment: '独立批准基线' });

    // 制造漂移: 延长任务A工期 (完成日晚于基线 -> behind), 新增任务C (基线中不存在 -> added), B保持不变 (持平).
    const planBefore = await api(page, 'GET', base(id) + '/planning');
    const taskA = planBefore.tasks.find(t => t.wbs_code === '1');
    expect(taskA.baseline_state, '批准后未漂移前 A 应持平').toBe('on_baseline');
    await mutate(page, id, `/tasks/${taskA.task_id}`, { duration_days: 9 }, 200, 'PUT');
    await open(page, id, '计划与执行', 'WBS与排程');
    await wbsTask(page, '3', '新增交付任务C', 4);

    // 面板: 任务 3, 持平 1, 延后 1, 新增 1, 最大完成延后 >0.
    const bvar = panel(page, '基线进度偏差');
    await expect(bvar.getByText('任务 3', { exact: true })).toBeVisible();
    await expect(bvar.getByText('持平 1', { exact: true })).toBeVisible();
    await expect(bvar.getByText('延后 1', { exact: true })).toBeVisible();
    await expect(bvar.getByText('新增 1', { exact: true })).toBeVisible();
    await expect(bvar.getByText(/最大完成延后 [1-9]\d* 天/)).toBeVisible();
    await panelShot(page, '基线进度偏差', 'h04bv-1-panel.png');

    // 逐任务基线偏差列: A 延后(+N天), B 持平, C 新增.
    await expect(row(page, '设计基线任务A').locator('.ant-tag').filter({ hasText: '延后' })).toBeVisible();
    await expect(row(page, '设计基线任务A').locator('.ant-tag').filter({ hasText: /\+.*天/ })).toBeVisible();
    await expect(row(page, '测试基线任务B').locator('.ant-tag').filter({ hasText: '持平' })).toBeVisible();
    await expect(row(page, '新增交付任务C').locator('.ant-tag').filter({ hasText: '新增' })).toBeVisible();
    await shot(page, 'h04bv-2-columns.png');

    // 服务端读取时派生回显二次确认 (不落库, 键为连字符/下划线 JSON).
    const plan = await api(page, 'GET', base(id) + '/planning');
    const summary = plan.baseline_variance;
    expect(summary.available).toBe(true);
    expect(summary.total).toBe(3);
    expect(summary['on-baseline']).toBe(1);
    expect(summary.behind).toBe(1);
    expect(summary.added).toBe(1);
    expect(summary['worst-finish-slip']).toBeGreaterThan(0);
    expect(summary.baseline_id).toBe(baseline.baseline_id);
    const byCode = code => plan.tasks.find(t => t.wbs_code === code);
    expect(byCode('1').baseline_state).toBe('behind');
    expect(byCode('1').baseline_finish_variance).toBeGreaterThan(0);
    expect(byCode('2').baseline_state).toBe('on_baseline');
    expect(byCode('2').baseline_finish_variance).toBe(0);
    expect(byCode('3').baseline_state).toBe('added');
    expect(byCode('3').baseline_finish_variance).toBeNull();

    await rv.context.close();
    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
