const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H04 计划网络连通性只读汇总: 计划与执行 -> 资源与日历 页签"计划网络连通性"面板的只读概览.
// 与"关键路径敏感度"/"排程紧凑度"正交互补 —— 那两者假设依赖网络完整再看浮动分布; 本项反过来核验网络本身是否完整:
// network-connectivity 只看非汇总叶任务, 从 dependencies 边集算 接入网络叶任务数与占比, 完全未链接(既无前置又无后继)的孤立任务清单,
// 以及把依赖边按无向连通分量折叠后涉及叶任务的独立依赖链段数 component-count; 定性档 unlinked(有孤立) > fragmented(>=2段) > connected.
// 免迁移/免新kind/免新命令/免新路由, 只读派生, 不构成任何门控.
// 流程: 建项目 -> 三条叶任务 A(1) B(1) C(1) 同日开工, 仅建依赖 A->B, C 留孤立
//   -> 叶3, 依赖1, 已链接2 占 round(100*2/3)=67%, 未链接1 (C), 链段数1 -> 档 unlinked;
//   GET /planning 的 schedule_connectivity 回显一致.
//   空态: 新项目无任何任务 -> available=false -> "尚未建任务 (创建任务后方可检查依赖网络是否连贯)."
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h04nc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
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
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data, timeout: 30000 });
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

async function mutate(page, id, suffix, data = {}, expected = 200, verb = 'POST') {
  const project = await api(page, 'GET', base(id));
  return api(page, verb, base(id) + suffix, { ...data, version: project.version }, expected);
}

async function open(page, id) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await drawer(page).getByRole('tab', { name: '计划与执行', exact: true }).click();
  await page.waitForLoadState('networkidle');
  await drawer(page).getByRole('tab', { name: '资源与日历', exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function newProject(page, suffix, tag) {
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
  const adminId = options.currentUserId;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H04NC-${suffix}`, name: `网络连通性 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-21', end_date: '2026-12-31' });
  const id = project.project_id;
  await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E立项前置' });
  await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E计划前置' });
  return { id, adminId, deptId };
}

async function leafTask(page, id, code, name, owner, days) {
  const data = await mutate(page, id, '/tasks', { wbs_code: code, name, duration_days: days, start_date: '2026-09-21', owner_id: owner });
  return data.result;
}

async function dependency(page, id, predecessor, successor) {
  await mutate(page, id, '/dependencies', { predecessor_id: predecessor, successor_id: successor, dependency_type: 'FS', lag_days: 0 });
}

async function planModel(page, id) {
  return api(page, 'GET', base(id) + '/planning');
}

test.describe('H04 计划网络连通性只读汇总浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('部分链接 + 一个孤立任务 -> 连通性档位/已链接占比/链段数/未链接清单命中且服务端回显一致; 空态无任何任务', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const { id, adminId } = await newProject(page, suffix, 'orphan');

    // 三条叶任务同日开工; 只建 A->B 依赖, C 保持完全未链接 (孤立).
    const a = await leafTask(page, id, 'A', '前置甲', adminId, 1);
    const b = await leafTask(page, id, 'B', '后继乙', adminId, 1);
    const c = await leafTask(page, id, 'C', '孤立未链接任务', adminId, 1);
    await dependency(page, id, a.task_id, b.task_id);

    // 服务端只读派生回显.
    const plan = await planModel(page, id);
    const n = plan.schedule_connectivity;
    expect(n.available).toBe(true);
    expect(n['leaf-count']).toBe(3);
    expect(n['dependency-count']).toBe(1);
    expect(n['linked-count']).toBe(2);
    expect(n['linked-pct']).toBe(67); // round(100 * 2/3)
    expect(n['unlinked-count']).toBe(1);
    expect(n['component-count']).toBe(1);
    expect(n['connectivity-level']).toBe('unlinked'); // 存在孤立任务优先判 unlinked
    expect(n['unlinked-tasks'].map(t => t.task_id)).toEqual([c.task_id]);
    // 口径自洽: 叶任务 = 已链接 + 未链接; 占比 = 四舍五入(100*已链接/叶).
    expect(n['leaf-count']).toBe(n['linked-count'] + n['unlinked-count']);
    expect(n['linked-pct']).toBe(Math.round(100 * n['linked-count'] / n['leaf-count']));

    // 界面: 资源与日历页签"计划网络连通性"面板只读汇总真实可见.
    await open(page, id);
    const card = panel(page, '计划网络连通性');
    await expect(card.getByText('连通性 存在未链接任务', { exact: true })).toBeVisible();
    await expect(card.getByText('已链接占比 67%', { exact: true })).toBeVisible();
    await expect(card.getByText('依赖链段数 1 段', { exact: true })).toBeVisible();
    await expect(card.getByText('未链接任务 1', { exact: true })).toBeVisible();
    await expect(card.getByText('依赖 1 条 / 叶任务 3', { exact: true })).toBeVisible();
    await expect(card.getByText('既无前置又无后继的孤立任务 (按 WBS 升序):', { exact: true })).toBeVisible();
    await expect(card.getByText('C 孤立未链接任务', { exact: true })).toBeVisible();
    await panelShot(page, '计划网络连通性', 'h04nc-1-panel.png');

    // 空态: 新项目无任何任务 -> available=false -> 面板显"尚未建任务 (创建任务后方可检查依赖网络是否连贯)."
    const suffix2 = `${suffix}e`;
    const p2 = await newProject(page, suffix2, 'empty');
    const plan2 = await planModel(page, p2.id);
    expect(plan2.schedule_connectivity.available).toBe(false);
    expect(plan2.schedule_connectivity['leaf-count']).toBe(0);
    expect(plan2.schedule_connectivity['linked-count']).toBe(0);
    expect(plan2.schedule_connectivity['unlinked-count']).toBe(0);
    expect(plan2.schedule_connectivity['component-count']).toBe(0);
    expect(plan2.schedule_connectivity['connectivity-level']).toBeFalsy();

    await open(page, p2.id);
    const emptyPanel = panel(page, '计划网络连通性');
    await expect(emptyPanel.getByText('尚未建任务 (创建任务后方可检查依赖网络是否连贯).', { exact: true })).toBeVisible();
    await panelShot(page, '计划网络连通性', 'h04nc-2-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
