const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H04 依赖类型结构只读汇总: 计划与执行 -> 资源与日历 页签"依赖类型结构"面板的只读概览.
// 与"计划网络连通性"/"排程紧凑度"/"关键路径敏感度"正交互补 —— 那三者假设依赖网络完整再看拓扑与浮动分布;
// 本项看依赖编排的"结构": 四类逻辑依赖 FS(完成-开始)/SS(开始-开始)/FF(完成-完成)/SF(开始-完成) 的占比.
// dependency-type-mix 只统计带有效类型的依赖, FS 是最串行的编排, SS/FF/SF 代表重叠或并行, parallel=SS+FF+SF,
// parallel-pct=round(100*parallel/total), lagged 为非零间隔 (缓冲/提前) 的依赖条数, serialization-level 档:
// 无有效类型 -> nil; parallel=0 -> fully-serial; parallel-pct<25 -> mostly-serial; <50 -> mixed; 否则 parallel-heavy.
// 免迁移/免新kind/免新命令/免新路由, 只读派生, 不构成任何门控.
// 流程: 建项目 -> 五条叶任务 A B C D E 同日开工, 依赖 A->B FS, B->C FS, C->D SS, D->E FF(lag 2)
//   -> 类型依赖4, FS2, parallel2 占 round(100*2/4)=50% -> 档 parallel-heavy; lagged1 占 25%; by-type [2,1,1,0];
//   GET /planning 的 dependency_type_mix 回显一致.
//   空态: 新项目无任何依赖 -> available=false -> "尚无依赖 (建立任务间 FS/SS/FF/SF 依赖后方可分析并行度)."
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h04dt');
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
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H04DT-${suffix}`, name: `依赖类型结构 / ${suffix}`,
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

async function dependency(page, id, predecessor, successor, type, lag) {
  await mutate(page, id, '/dependencies', { predecessor_id: predecessor, successor_id: successor, dependency_type: type, lag_days: lag });
}

async function planModel(page, id) {
  return api(page, 'GET', base(id) + '/planning');
}

test.describe('H04 依赖类型结构只读汇总浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('FS+FS+SS+FF(带lag) -> 并行度档位/并行占比/FS占比/缓冲占比/逐类明细命中且服务端回显一致; 空态无任何依赖', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const { id, adminId } = await newProject(page, suffix, 'mix');

    // 五条叶任务同日开工; 依赖链 A->B FS, B->C FS, C->D SS, D->E FF(lag 2, 缓冲).
    const a = await leafTask(page, id, 'A', '前置甲', adminId, 1);
    const b = await leafTask(page, id, 'B', '后继乙', adminId, 1);
    const c = await leafTask(page, id, 'C', '并行开始丙', adminId, 1);
    const d = await leafTask(page, id, 'D', '并行汇合丁', adminId, 1);
    const e = await leafTask(page, id, 'E', '完成完成戊', adminId, 1);
    await dependency(page, id, a.task_id, b.task_id, 'FS', 0);
    await dependency(page, id, b.task_id, c.task_id, 'FS', 0);
    await dependency(page, id, c.task_id, d.task_id, 'SS', 0);
    await dependency(page, id, d.task_id, e.task_id, 'FF', 2);

    // 服务端只读派生回显.
    const plan = await planModel(page, id);
    const m = plan.dependency_type_mix;
    expect(m.available).toBe(true);
    expect(m['dependency-count']).toBe(4);
    expect(m['fs-count']).toBe(2);
    expect(m['parallel-count']).toBe(2); // SS + FF (+ SF)
    expect(m['parallel-pct']).toBe(50); // round(100 * 2/4)
    expect(m['lagged-count']).toBe(1); // 仅 FF lag 2
    expect(m['lagged-pct']).toBe(25); // round(100 * 1/4)
    expect(m['serialization-level']).toBe('parallel-heavy'); // 50 >= 50
    expect(m['by-type'].map(t => t.type)).toEqual(['FS', 'SS', 'FF', 'SF']);
    expect(m['by-type'].map(t => t.count)).toEqual([2, 1, 1, 0]);
    expect(m['by-type'].map(t => t.pct)).toEqual([50, 25, 25, 0]);
    // 口径自洽: parallel-pct = 四舍五入(100*parallel/total); by-type 计数之和 = 类型依赖总数.
    expect(m['parallel-pct']).toBe(Math.round(100 * m['parallel-count'] / m['dependency-count']));
    expect(m['by-type'].reduce((s, t) => s + t.count, 0)).toBe(m['dependency-count']);
    expect(m['fs-count'] + m['parallel-count']).toBe(m['dependency-count']);

    // 界面: 资源与日历页签"依赖类型结构"面板只读汇总真实可见.
    await open(page, id);
    const card = panel(page, '依赖类型结构');
    await expect(card.getByText('并行度 并行充分', { exact: true })).toBeVisible();
    await expect(card.getByText('并行依赖占比 50%', { exact: true })).toBeVisible();
    await expect(card.getByText('完成-开始(FS) 占比 50%', { exact: true })).toBeVisible();
    await expect(card.getByText('带缓冲间隔 1 条 · 25%', { exact: true })).toBeVisible();
    await expect(card.getByText('类型依赖 4 条', { exact: true })).toBeVisible();
    await expect(card.getByText('逐类明细:', { exact: true })).toBeVisible();
    await expect(card.getByText('完成-开始 2 (50%)', { exact: true })).toBeVisible();
    await expect(card.getByText('开始-开始 1 (25%)', { exact: true })).toBeVisible();
    await expect(card.getByText('完成-完成 1 (25%)', { exact: true })).toBeVisible();
    await expect(card.getByText('开始-完成 0 (0%)', { exact: true })).toBeVisible();
    await panelShot(page, '依赖类型结构', 'h04dt-1-panel.png');

    // 空态: 新项目无任何依赖 -> available=false -> 面板显"尚无依赖 (建立任务间 FS/SS/FF/SF 依赖后方可分析并行度)."
    const suffix2 = `${suffix}e`;
    const p2 = await newProject(page, suffix2, 'empty');
    const plan2 = await planModel(page, p2.id);
    expect(plan2.dependency_type_mix.available).toBe(false);
    expect(plan2.dependency_type_mix['dependency-count']).toBe(0);
    expect(plan2.dependency_type_mix['fs-count']).toBe(0);
    expect(plan2.dependency_type_mix['parallel-count']).toBe(0);
    expect(plan2.dependency_type_mix['parallel-pct']).toBe(0);
    expect(plan2.dependency_type_mix['lagged-count']).toBe(0);
    expect(plan2.dependency_type_mix['serialization-level']).toBeFalsy();
    expect(plan2.dependency_type_mix['by-type'].map(t => t.count)).toEqual([0, 0, 0, 0]);

    await open(page, p2.id);
    const emptyPanel = panel(page, '依赖类型结构');
    await expect(emptyPanel.getByText('尚无依赖 (建立任务间 FS/SS/FF/SF 依赖后方可分析并行度).', { exact: true })).toBeVisible();
    await panelShot(page, '依赖类型结构', 'h04dt-2-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
