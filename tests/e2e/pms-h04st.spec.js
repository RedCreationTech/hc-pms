const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H04 排程紧凑度只读汇总: 计划与执行 -> 资源与日历 页签"排程紧凑度"面板的只读概览.
// 与"关键路径敏感度"正交互补 —— sensitivity 看关键链之外谁快变关键(逐任务分档), 本项看整个计划有多紧:
// float-tightness 把 CPM 排程的每叶任务总时差汇总为 关键任务占比(100*关键/叶, 四舍五入), 平均/最小/最大总时差,
// 以及按关键任务占比给的定性紧凑度档位 (very-tight>=50% / tight>=25% / moderate>=10% / 否则 loose); 只统计非汇总叶子任务.
// 免迁移/免新kind/免新命令/免新路由, 只读派生, 不构成任何门控.
// 流程: 建项目 -> 三条独立任务 L(5 工作日, 关键浮动0) N(3, 浮动2) C(1, 浮动4) 同日开工
//   -> span 5, 叶任务3, 关键1, 关键任务占比 round(100*1/3)=33 -> tight(>=25), 平均总时差 (0+2+4)/3=2.0, 最小0 最大4;
//   GET /planning 的 schedule_tightness 回显一致.
//   空态: 新项目无任何任务 -> available=false -> "尚未排程 (需任务设置工期与依赖后计算总时差)."
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h04st');
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
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H04ST-${suffix}`, name: `排程紧凑度 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-21', end_date: '2026-12-31' });
  const id = project.project_id;
  await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E立项前置' });
  await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E计划前置' });
  return { id, adminId, deptId };
}

async function leafTask(page, id, code, name, owner, days) {
  await mutate(page, id, '/tasks', { wbs_code: code, name, duration_days: days, start_date: '2026-09-21', owner_id: owner });
}

async function planModel(page, id) {
  return api(page, 'GET', base(id) + '/planning');
}

test.describe('H04 排程紧凑度只读汇总浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('关键/近关键/宽松共存 -> 整体紧凑度档位与总时差分布命中且服务端回显一致; 空态无任何任务', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const { id, adminId } = await newProject(page, suffix, 'tight');

    // 三条独立任务, 同日开工: L 5 -> 关键(浮动0); N 3 -> 浮动2; C 1 -> 浮动4. 叶任务3, 关键1.
    await leafTask(page, id, 'L', '关键装配甲', adminId, 5);
    await leafTask(page, id, 'N', '近关键装配乙', adminId, 3);
    await leafTask(page, id, 'C', '宽松辅助丙', adminId, 1);

    // 服务端只读派生回显.
    const plan = await planModel(page, id);
    const t = plan.schedule_tightness;
    expect(t.available).toBe(true);
    expect(t.span).toBe(5);
    expect(t['leaf-count']).toBe(3);
    expect(t['critical-count']).toBe(1);
    expect(t['critical-pct']).toBe(33); // round(100 * 1/3) = 33
    expect(t['avg-float']).toBeCloseTo(2, 1); // (0+2+4)/3 = 2.0 (一位小数指标)
    expect(t['min-float']).toBe(0);
    expect(t['max-float']).toBe(4);
    expect(t['tightness-level']).toBe('tight'); // >=25 且 <50
    // 口径自洽: 关键占比 = 四舍五入(100*关键/叶).
    expect(t['critical-pct']).toBe(Math.round(100 * t['critical-count'] / t['leaf-count']));

    // 界面: 资源与日历页签"排程紧凑度"面板只读汇总真实可见.
    await open(page, id);
    const card = panel(page, '排程紧凑度');
    await expect(card.getByText('紧凑度 偏紧', { exact: true })).toBeVisible();
    await expect(card.getByText('关键任务占比 33%', { exact: true })).toBeVisible();
    await expect(card.getByText('平均总时差 2 天', { exact: true })).toBeVisible();
    await expect(card.getByText('最小总时差 0 天', { exact: true })).toBeVisible();
    await expect(card.getByText('最大总时差 4 天', { exact: true })).toBeVisible();
    await expect(card.getByText('关键任务 1 / 叶任务 3', { exact: true })).toBeVisible();
    await panelShot(page, '排程紧凑度', 'h04st-1-panel.png');

    // 空态: 新项目无任何任务 -> available=false -> 面板显"尚未排程 (需任务设置工期与依赖后计算总时差)."
    const suffix2 = `${suffix}e`;
    const p2 = await newProject(page, suffix2, 'empty');
    const plan2 = await planModel(page, p2.id);
    expect(plan2.schedule_tightness.available).toBe(false);
    expect(plan2.schedule_tightness['leaf-count']).toBe(0);
    expect(plan2.schedule_tightness['critical-pct']).toBe(0);
    expect(plan2.schedule_tightness['tightness-level']).toBeFalsy();

    await open(page, p2.id);
    const emptyPanel = panel(page, '排程紧凑度');
    await expect(emptyPanel.getByText('尚未排程 (需任务设置工期与依赖后计算总时差).', { exact: true })).toBeVisible();
    await panelShot(page, '排程紧凑度', 'h04st-2-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
