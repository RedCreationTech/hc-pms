const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H04 WBS 层级结构只读汇总: 计划与执行 -> 资源与日历 页签"WBS 层级结构"面板的只读概览.
// 与"关键路径敏感度"/"排程紧凑度"/"计划网络连通性"/"依赖类型结构"/"任务分解粒度"正交互补 —— 前四项看浮动分布/整体松弛/
// 依赖拓扑/编排结构 (都默认任务已拆好), 第五项反过来核验叶任务工期是否够细; 本项看分解树的"形状"本身 —— 按 parent_id
// 的父子挂接有几层、有没有汇总上卷、叶任务是否被均衡地挂在相近深度、有无父级缺失的孤儿.
// wbs-hierarchy: task-count 任务总数, root-count 无父顶层任务数, summary-count 汇总数, leaf-count 非汇总数 (与其余分母同口径),
// max-depth 最深层级(根记1), leaf-min/max-depth 与 depth-spread 叶任务最深减最浅, level-counts 逐层任务数, orphan-count 父级缺失数.
// 档位: 无任务->nil; max-depth<=1->flat (全部平铺无汇总); max-depth>=4->deep (层级过深); depth-spread>=2->unbalanced (深度不均); 否则 balanced.
// 免迁移/免新kind/免新命令/免新路由/免门控, 纯只读派生.
// 流程: 建项目 -> 汇总"1"(根) -> 其下汇总"1.1" -> "1.1"下叶"1.1.1"(深3) + "1"下叶"1.2"(深2) + 顶层独立叶"2"(深1)
//   -> summary-count2, leaf-count3, root-count2, max-depth3, leaf-min1, leaf-max3, spread2 -> unbalanced;
//   GET /planning 的 wbs_hierarchy 回显一致.
//   空态: 新项目无任何任务 -> available=false -> "尚未建任务 (创建 WBS 任务后方可分析层级结构)."
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h04wh');
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
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H04WH-${suffix}`, name: `WBS层级结构 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-21', end_date: '2026-12-31' });
  const id = project.project_id;
  await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E立项前置' });
  await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E计划前置' });
  return { id, adminId, deptId };
}

async function summaryTask(page, id, code, name, parentId) {
  const body = { wbs_code: code, name, task_type: 'summary', duration_days: 0, start_date: '2026-09-21' };
  if (parentId) body.parent_id = parentId;
  const data = await mutate(page, id, '/tasks', body);
  return data.result;
}

async function leafTask(page, id, code, name, owner, parentId) {
  const body = { wbs_code: code, name, duration_days: 1, start_date: '2026-09-21', owner_id: owner };
  if (parentId) body.parent_id = parentId;
  const data = await mutate(page, id, '/tasks', body);
  return data.result;
}

async function planModel(page, id) {
  return api(page, 'GET', base(id) + '/planning');
}

test.describe('H04 WBS 层级结构只读汇总浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('多层树 (汇总>子汇总>深叶 + 顶层独立叶) -> 层级结构档位/最深·跨度·逐层分布命中且服务端回显一致; 空态无任何任务', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const { id, adminId } = await newProject(page, suffix, 'wh');

    // 一棵三层 WBS: 根汇总"1" -> 子汇总"1.1" -> 深叶"1.1.1"(深3); 根下另一叶"1.2"(深2); 再加一个顶层独立叶"2"(深1).
    const s = await summaryTask(page, id, '1', '需求分析包');
    const m = await summaryTask(page, id, '1.1', '详细设计', s.task_id);
    await leafTask(page, id, '1.1.1', '模块A详设', adminId, m.task_id); // 深度 3
    await leafTask(page, id, '1.2', '需求评审', adminId, s.task_id);      // 深度 2
    await leafTask(page, id, '2', '独立预研', adminId);                   // 深度 1 (顶层叶)

    // 服务端只读派生回显.
    const plan = await planModel(page, id);
    const h = plan.wbs_hierarchy;
    expect(h.available).toBe(true);
    expect(h['task-count']).toBe(5);
    expect(h['summary-count']).toBe(2);
    expect(h['leaf-count']).toBe(3);
    expect(h['root-count']).toBe(2); // 根汇总"1" + 顶层独立叶"2"
    expect(h['max-depth']).toBe(3);
    expect(h['leaf-min-depth']).toBe(1); // 顶层独立叶"2"
    expect(h['leaf-max-depth']).toBe(3); // "1.1.1" 挂 "1.1" 挂 "1"
    expect(h['depth-spread']).toBe(2);   // 3 - 1
    expect(h['orphan-count']).toBe(0);
    expect(h['level-counts']).toEqual([
      { level: 1, count: 2 }, // "1" + "2"
      { level: 2, count: 2 }, // "1.1" + "1.2"
      { level: 3, count: 1 }, // "1.1.1"
    ]);
    expect(h['structure-level']).toBe('unbalanced'); // max-depth 3<4 且 spread 2>=2
    // 口径自洽: 顶层 + 各层任务数之和 = 任务总数; 汇总 + 叶 = 任务总数.
    expect(h['summary-count'] + h['leaf-count']).toBe(h['task-count']);
    expect(h['level-counts'].reduce((a, x) => a + x.count, 0)).toBe(h['task-count']);

    // 界面: 资源与日历页签"WBS 层级结构"面板只读汇总真实可见.
    await open(page, id);
    const card = panel(page, 'WBS 层级结构');
    await expect(card.getByText('层级结构 叶任务深度不均', { exact: true })).toBeVisible();
    await expect(card.getByText('最深层级 3 层', { exact: true })).toBeVisible();
    await expect(card.getByText('汇总 2 · 叶 3 · 顶层 2 个', { exact: true })).toBeVisible();
    await expect(card.getByText('叶任务深度跨度 2 层', { exact: true })).toBeVisible();
    await expect(card.getByText('父级缺失 0 个', { exact: true })).toBeVisible();
    await expect(card.getByText('逐层任务数:', { exact: true })).toBeVisible();
    await expect(card.getByText('第 1 层 2 个', { exact: true })).toBeVisible();
    await expect(card.getByText('第 2 层 2 个', { exact: true })).toBeVisible();
    await expect(card.getByText('第 3 层 1 个', { exact: true })).toBeVisible();
    await panelShot(page, 'WBS 层级结构', 'h04wh-1-panel.png');

    // 空态: 新项目无任何任务 -> available=false -> 面板显"尚未建任务 (创建 WBS 任务后方可分析层级结构)."
    const suffix2 = `${suffix}e`;
    const p2 = await newProject(page, suffix2, 'empty');
    const plan2 = await planModel(page, p2.id);
    expect(plan2.wbs_hierarchy.available).toBe(false);
    expect(plan2.wbs_hierarchy['task-count']).toBe(0);
    expect(plan2.wbs_hierarchy['max-depth']).toBe(0);
    expect(plan2.wbs_hierarchy['level-counts']).toEqual([]);
    expect(plan2.wbs_hierarchy['structure-level']).toBeFalsy();
    await open(page, p2.id);
    const emptyCard = panel(page, 'WBS 层级结构');
    await expect(emptyCard.getByText('尚未建任务 (创建 WBS 任务后方可分析层级结构).', { exact: true })).toBeVisible();
    await panelShot(page, 'WBS 层级结构', 'h04wh-2-empty.png');

    expect(errors).toEqual([]);
  });
});
