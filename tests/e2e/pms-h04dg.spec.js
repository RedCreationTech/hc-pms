const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H04 任务分解粒度只读汇总: 计划与执行 -> 资源与日历 页签"任务分解粒度"面板的只读概览.
// 与"关键路径敏感度"/"排程紧凑度"/"计划网络连通性"/"依赖类型结构"正交互补 —— 那四项都默认任务已拆到合适粒度,
// 再看浮动分布/整体松弛/依赖拓扑/编排结构; 本项反过来核验 WBS 分解粒度本身, 只看非汇总叶任务的 duration_days.
// duration-granularity: leaf-count=非汇总叶任务数, sum-days=工期之和, avg-days=一位小数均值, median-days=中位数
// (偶数取中间两项均值), coarse-threshold-days=过粗阈值常量(10), coarse-count=工期>=阈值叶任务数,
// dominant-pct=round(100*max-duration/sum-days) 最长单任务占比; 档位: 无叶->nil; dominant-pct>=40->hard-to-track
// (一个任务吞掉大半工期); 存在过粗任务->coarse; 否则->fine.
// 免迁移/免新kind/免新命令/免新路由/免门控, 纯只读派生.
// 流程: 建项目 -> 三条叶任务 A(12) B(2) C(2) 同日开工 (无需依赖, 粒度只看工期分布)
//   -> leaf-count3, sum16, max12, min2, avg=round(100*16/3)/10=5.3, median=2(奇数中位), dominant=round(100*12/16)=75
//   -> 75>=40 -> hard-to-track; coarse-count=1(仅 A>=10); GET /planning 的 duration_granularity 回显一致.
//   空态: 新项目无任何任务 -> available=false -> "尚未建任务 (创建任务并设定工期后方可评估分解粒度)."
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h04dg');
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
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H04DG-${suffix}`, name: `任务分解粒度 / ${suffix}`,
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

async function planModel(page, id) {
  return api(page, 'GET', base(id) + '/planning');
}

test.describe('H04 任务分解粒度只读汇总浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('巨任务 A(12)+两短任务 B(2)C(2) -> 分解粒度档位/最长占比/过粗数/中位·平均/清单命中且服务端回显一致; 空态无任何任务', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const { id, adminId } = await newProject(page, suffix, 'dg');

    // 三条非汇总叶任务: 一个 12 工作日巨任务吞掉大半工期, 两个 2 工作日短任务 (无需依赖, 粒度只看工期分布).
    await leafTask(page, id, 'A', '吞链巨任务', adminId, 12);
    await leafTask(page, id, 'B', '短任务乙', adminId, 2);
    await leafTask(page, id, 'C', '短任务丙', adminId, 2);

    // 服务端只读派生回显.
    const plan = await planModel(page, id);
    const g = plan.duration_granularity;
    expect(g.available).toBe(true);
    expect(g['leaf-count']).toBe(3);
    expect(g['sum-days']).toBe(16); // 12 + 2 + 2
    expect(g['max-duration']).toBe(12);
    expect(g['min-duration']).toBe(2);
    expect(g['avg-days']).toBeCloseTo(5.3, 1); // round(100 * 16/3)/10
    expect(g['median-days']).toBe(2); // 升序 [2,2,12] 奇数中位
    expect(g['coarse-threshold-days']).toBe(10);
    expect(g['coarse-count']).toBe(1); // 仅 A(12) 达到 10
    expect(g['dominant-pct']).toBe(75); // round(100 * 12/16)
    expect(g['granularity-level']).toBe('hard-to-track'); // 75 >= 40
    expect(g['coarse-tasks'].map(t => t.wbs_code)).toEqual(['A']);
    expect(g['coarse-tasks'][0]['duration_days']).toBe(12);
    // 口径自洽: dominant-pct = 四舍五入(100*max/sum); sum-days = 叶任务工期之和.
    expect(g['dominant-pct']).toBe(Math.round(100 * g['max-duration'] / g['sum-days']));

    // 界面: 资源与日历页签"任务分解粒度"面板只读汇总真实可见.
    await open(page, id);
    const card = panel(page, '任务分解粒度');
    await expect(card.getByText('分解粒度 单任务吞掉大半工期', { exact: true })).toBeVisible();
    await expect(card.getByText('最长单任务占比 75%', { exact: true })).toBeVisible();
    await expect(card.getByText('过粗任务 1 个', { exact: true })).toBeVisible();
    await expect(card.getByText('中位工期 2 · 平均 5.3 天', { exact: true })).toBeVisible();
    await expect(card.getByText('叶任务 3 个 · 合计 16 天', { exact: true })).toBeVisible();
    await expect(card.getByText('最长 12 / 最短 2 天', { exact: true })).toBeVisible();
    await expect(card.getByText('难以逐日跟踪的过粗任务 (按工期降序):', { exact: true })).toBeVisible();
    await expect(card.getByText('A 吞链巨任务 · 12 天', { exact: true })).toBeVisible();
    await panelShot(page, '任务分解粒度', 'h04dg-1-panel.png');

    // 空态: 新项目无任何任务 -> available=false -> 面板显"尚未建任务 (创建任务并设定工期后方可评估分解粒度)."
    const suffix2 = `${suffix}e`;
    const p2 = await newProject(page, suffix2, 'empty');
    const plan2 = await planModel(page, p2.id);
    expect(plan2.duration_granularity.available).toBe(false);
    expect(plan2.duration_granularity['leaf-count']).toBe(0);
    expect(plan2.duration_granularity['sum-days']).toBe(0);
    expect(plan2.duration_granularity['coarse-count']).toBe(0);
    expect(plan2.duration_granularity['dominant-pct']).toBe(0);
    expect(plan2.duration_granularity['granularity-level']).toBeFalsy();
    expect(plan2.duration_granularity['coarse-tasks']).toEqual([]);

    await open(page, p2.id);
    const emptyPanel = panel(page, '任务分解粒度');
    await expect(emptyPanel.getByText('尚未建任务 (创建任务并设定工期后方可评估分解粒度).', { exact: true })).toBeVisible();
    await panelShot(page, '任务分解粒度', 'h04dg-2-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
