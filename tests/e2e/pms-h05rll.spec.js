const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H05 资源投入均衡度只读汇总: 计划与执行 -> 资源与日历 页签"资源投入均衡度"面板的只读概览.
// capacity/resource-load-leveling 把每条 allocation 的 hours_per_day 摊到其任务的每个 working_date, 得到逐工作日总负荷曲线,
// 读取时派生 scheduled-days/active-days/idle-days/total-hours/peak-hours/peak-date/avg-hours/peak-to-avg/cv-pct 与定性档位.
// 与"资源负荷检查"(谁超容量) / "任务投入覆盖度"(谁没排) / "关键路径投入缺口"(关键路径缺不缺人) 三项正交 —— 那三项看"够不够 / 缺不缺",
// 本项看"忙闲均不均": 即便没有任何一天超容量, 若有的工作日堆满、有的排定工作日却空转, 仍需资源平滑 (resource leveling).
// 档位: 无排定工作日->nil; 总负荷0->unassigned; peak-to-avg<1.3->level; <1.6->moderate; 否则->spiky.
// 免迁移/免新kind/免新命令/免新路由/免门控, 纯只读派生.
// 流程: 建项目 -> 任务A(1 工作日, 投入 8h) + 任务B(3 工作日, 无任何投入, 与A同起 2026-09-21)
//   -> 逐日负荷 [8,0,0] -> scheduled3 active1 idle2 peak8 peak-date 09-21 avg2.7 peak-to-avg3.0 cv141 -> spiky;
//   GET /planning 的 resource_load_leveling 回显一致.
//   空态: 新项目无任何任务 -> available=false -> "尚无排程任务 (创建任务、排程并分配工时后方可评估投入均衡度)."
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h05rll');
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
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H05RLL-${suffix}`, name: `资源投入均衡度 / ${suffix}`,
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

test.describe('H05 资源投入均衡度只读汇总浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('一工作日堆满+连续排定工作日空转 -> 均衡度档位/峰值-均值/空转日命中且服务端回显一致; 空态无任务', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const { id, adminId } = await newProject(page, suffix, 'rll');

    // A 1 工作日只投入一天(8h), B 3 工作日全程排定却无任何投入 -> 逐日负荷 [8,0,0]: 堆满+空转并存.
    const a = await leafTask(page, id, 'A', '集中装配', adminId, 1);
    await leafTask(page, id, 'B', '长周期调试', adminId, 3);
    await mutate(page, id, '/resources', { name: `机床${suffix}`, resource_type: 'equipment', daily_capacity: 8 });
    const m = await planModel(page, id);
    const equipId = m.resources.find(r => r.name === `机床${suffix}`).resource_id;
    await mutate(page, id, '/allocations', { task_id: a.task_id, resource_id: equipId, hours_per_day: 8 });

    // 服务端只读派生回显.
    const plan = await planModel(page, id);
    const r = plan.resource_load_leveling;
    expect(r.available).toBe(true);
    expect(r['scheduled-days']).toBe(3);   // 09-21 / 09-22 / 09-23
    expect(r['active-days']).toBe(1);      // 只有 09-21 有投入
    expect(r['idle-days']).toBe(2);        // 09-22 / 09-23 排定却空转
    expect(r['idle-days'] + r['active-days']).toBe(r['scheduled-days']);
    expect(r['peak-date']).toBe('2026-09-21');
    expect(r['avg-hours']).toBeCloseTo(2.7, 1);
    expect(r['peak-to-avg']).toBeCloseTo(3.0, 1);
    expect(r['cv-pct']).toBeCloseTo(141, 0);
    expect(r['leveling-level']).toBe('spiky');

    // 界面: 资源与日历页签"资源投入均衡度"面板只读汇总真实可见.
    await open(page, id);
    const card = panel(page, '资源投入均衡度');
    await expect(card.getByText('投入均衡 负荷尖峰 (忙闲不均)', { exact: true })).toBeVisible();
    await expect(card.getByText('空转工作日 2 天', { exact: true })).toBeVisible();
    await expect(card.getByText('排定 3 天 · 有负荷 1 天', { exact: true })).toBeVisible();
    await expect(card.getByText(/峰值\/均值/)).toBeVisible();
    await expect(card.getByText(/波动系数 CV/)).toBeVisible();
    await expect(card.getByText(/峰值负荷 .* 工时 @ 2026-09-21/)).toBeVisible();
    await panelShot(page, '资源投入均衡度', 'h05rll-1-panel.png');

    // 空态: 新项目无任何任务 -> available=false -> 面板显空态文案.
    const suffix2 = `${suffix}e`;
    const p2 = await newProject(page, suffix2, 'empty');
    const plan2 = await planModel(page, p2.id);
    expect(plan2.resource_load_leveling.available).toBe(false);
    expect(plan2.resource_load_leveling['scheduled-days']).toBe(0);
    expect(plan2.resource_load_leveling['leveling-level']).toBeFalsy();

    await open(page, p2.id);
    const emptyPanel = panel(page, '资源投入均衡度');
    await expect(emptyPanel.getByText('尚无排程任务 (创建任务、排程并分配工时后方可评估投入均衡度).', { exact: true })).toBeVisible();
    await panelShot(page, '资源投入均衡度', 'h05rll-2-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
