const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H05 资源容量利用率只读汇总: 计划与执行 -> 资源与日历 页签"资源容量利用率"面板的只读概览.
// capacity/capacity-utilization 把每个被排入资源在其排入工作日上摊派的投入工时 (committed) 与其有效日容量 (capacities 覆盖优先, 否则 daily_capacity) 逐日累加 (capacity),
// 读取时派生 available/resource-count/total-committed-hours/total-capacity-hours/overall-pct/avg-pct/min-pct/max-pct/underused/near-saturated 与定性档位.
// 与"资源负荷检查"(谁超容量) / "任务投入覆盖度"(谁没排) / "关键路径投入缺口"(关键路径缺不缺人) / "资源投入均衡度"(忙闲均不均) 四项正交 —— 那四项看"够不够 / 缺不缺 / 均不均",
// 本项看"相对日历容量整体填了多少", 尤其暴露前四项都看不到的"排了人却没用满"的轻载资源.
// 档位: 无被排入资源->nil; 整体加权利用率<50->underused; <90->balanced; 否则->saturated.
// 免迁移/免新kind/免新命令/免新路由/免门控, 纯只读派生.
// 流程: 建项目 -> 任务A(3 工作日, 起 2026-09-21) -> 两设备资源同一任务:
//   重载机床 daily_capacity 8 + hours_per_day 8 -> committed 24 / capacity 24 = 100% -> near-saturated;
//   轻载设备 daily_capacity 8 + hours_per_day 2 -> committed 6  / capacity 24 = 25%  -> underused;
//   整体 30/48 = 63% -> balanced; GET /planning 的 capacity_utilization 回显一致.
//   空态: 新项目无任何分配 -> available=false -> "尚无资源工时分配 (创建资源、排程并分配工时后方可评估容量利用率)."
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h05cu');
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

async function newProject(page, suffix) {
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
  const adminId = options.currentUserId;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H05CU-${suffix}`, name: `资源容量利用率 / ${suffix}`,
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

async function resourceIdByName(page, id, name) {
  const m = await api(page, 'GET', base(id) + '/planning');
  return m.resources.find(r => r.name === name).resource_id;
}

test.describe('H05 资源容量利用率只读汇总浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('同任务排入重载+轻载两资源 -> 利用率档位/轻载与满负荷清单命中且服务端回显一致; 空态无分配', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const { id, adminId } = await newProject(page, suffix);

    // A 排定 3 个工作日 (09-21/22/23), 两设备资源同一任务.
    const a = await leafTask(page, id, 'A', '共用装配任务', adminId, 3);
    await mutate(page, id, '/resources', { name: `重载机床${suffix}`, resource_type: 'equipment', daily_capacity: 8 });
    await mutate(page, id, '/resources', { name: `轻载设备${suffix}`, resource_type: 'equipment', daily_capacity: 8 });
    const heavy = await resourceIdByName(page, id, `重载机床${suffix}`);
    const light = await resourceIdByName(page, id, `轻载设备${suffix}`);
    await mutate(page, id, '/allocations', { task_id: a.task_id, resource_id: heavy, hours_per_day: 8 });
    await mutate(page, id, '/allocations', { task_id: a.task_id, resource_id: light, hours_per_day: 2 });

    // 服务端只读派生回显.
    const plan = await api(page, 'GET', base(id) + '/planning');
    const r = plan.capacity_utilization;
    expect(r.available).toBe(true);
    expect(r['resource-count']).toBe(2);
    expect(r['overall-pct']).toBe(63);   // 30/48 -> balanced
    expect(r['avg-pct']).toBe(63);        // (100+25)/2 -> 63
    expect(r['min-pct']).toBe(25);
    expect(r['max-pct']).toBe(100);
    expect(r['utilization-level']).toBe('balanced');
    expect(r.underused).toHaveLength(1);
    expect(r.underused[0]['name']).toBe(`轻载设备${suffix}`);
    expect(r.underused[0]['utilization-pct']).toBe(25);
    expect(r.underused[0]['active-days']).toBe(3);
    expect(r['near-saturated']).toHaveLength(1);
    expect(r['near-saturated'][0]['name']).toBe(`重载机床${suffix}`);
    expect(r['near-saturated'][0]['utilization-pct']).toBe(100);

    // 界面: 资源与日历页签"资源容量利用率"面板只读汇总真实可见.
    await open(page, id);
    const card = panel(page, '资源容量利用率');
    await expect(card.getByText('容量利用 利用适中', { exact: true })).toBeVisible();
    await expect(card.getByText('整体利用率 63%', { exact: true })).toBeVisible();
    await expect(card.getByText('被排入资源 2 个', { exact: true })).toBeVisible();
    await expect(card.getByText('平均 63% · 最低 25% · 最高 100%', { exact: true })).toBeVisible();
    await expect(card.getByText('投入不足的轻载资源:', { exact: true })).toBeVisible();
    await expect(card.getByText(`轻载设备${suffix} 25%`, { exact: true })).toBeVisible();
    await expect(card.getByText('接近满负荷的资源:', { exact: true })).toBeVisible();
    await expect(card.getByText(`重载机床${suffix} 100%`, { exact: true })).toBeVisible();
    await expect(card.getByText(/投入 .* \/ 容量 .* 工时/)).toBeVisible();
    await panelShot(page, '资源容量利用率', 'h05cu-1-panel.png');

    // 空态: 新项目无任何分配 -> available=false -> 面板显空态文案.
    const p2 = await newProject(page, `${suffix}e`);
    const plan2 = await api(page, 'GET', base(p2.id) + '/planning');
    expect(plan2.capacity_utilization.available).toBe(false);
    expect(plan2.capacity_utilization['resource-count']).toBe(0);
    expect(plan2.capacity_utilization['utilization-level']).toBeFalsy();
    await open(page, p2.id);
    const emptyPanel = panel(page, '资源容量利用率');
    await expect(emptyPanel.getByText('尚无资源工时分配 (创建资源、排程并分配工时后方可评估容量利用率).', { exact: true })).toBeVisible();
    await panelShot(page, '资源容量利用率', 'h05cu-2-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
