const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H05 资源超配项目级只读汇总: 计划与执行 -> 资源与日历 顶部"资源负荷检查"面板的只读汇总概览.
// capacity/overloads 逐日明细经只读聚合给出 超配资源数/人日超配/设备日超配/最大单日超出工时/峰值负荷日,
// 免迁移/免新kind/免新命令/免新路由, 不构成任何门控(汇总与明细均只读呈现, 不阻断任何写操作).
// 流程: 建项目->两条叶任务 A/B(2026-09-21 起 2 工作日)-> 人员资源(容量8)分配 A 6h + B 6h
//   -> 09-21/09-22 各 12h 超 4h (人日 2 行); 设备资源(容量8)分配 A 10h, 09-22 放宽到 12
//   -> 仅 09-21 超 2h (设备日 1 行). 期望 面板 超配资源 2 / 人日超配 2 / 设备日超配 1 / 最大单日超出 4 工时 / 峰值负荷日 2026-09-21;
//   GET /planning 服务端回显 overload_summary 与 overallocations 一致. 空态: 新项目设备 4h<8 -> "当前排程未发现资源超负荷."
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h05ro');
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

async function newProject(page, suffix, tag) {
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
  const adminId = options.currentUserId;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H05RO-${suffix}`, name: `资源超配汇总 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-21', end_date: '2026-12-31' });
  const id = project.project_id;
  await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E立项前置' });
  await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E计划前置' });
  return { id, adminId, deptId };
}

async function leafTask(page, id, code, name, owner) {
  await mutate(page, id, '/tasks', { wbs_code: code, name, duration_days: 2, start_date: '2026-09-21', owner_id: owner });
}

async function planModel(page, id) {
  return api(page, 'GET', base(id) + '/planning');
}

test.describe('H05 资源超配项目级只读汇总浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('人员与设备同时超配 -> 汇总概览面板可见且服务端回显一致; 空态无超负荷', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const { id, adminId } = await newProject(page, suffix, 'overload');

    // 两条叶任务 (09-21 起 2 工作日: 09-21, 09-22).
    await leafTask(page, id, 'A', '装配A', adminId);
    await leafTask(page, id, 'B', '调试B', adminId);
    const t = await planModel(page, id);
    const taskA = t.tasks.find(x => x.wbs_code === 'A').task_id;
    const taskB = t.tasks.find(x => x.wbs_code === 'B').task_id;

    // 人员资源(容量8): A 6h + B 6h -> 09-21/09-22 各 12h, 每天超 4h (人日 2 行).
    await mutate(page, id, '/resources', { name: `工程师${suffix}`, resource_type: 'person', user_id: adminId, daily_capacity: 8 });
    // 设备资源(容量8): A 10h; 09-22 放宽到 12 -> 仅 09-21 超 2h (设备日 1 行).
    await mutate(page, id, '/resources', { name: `机床${suffix}`, resource_type: 'equipment', daily_capacity: 8 });
    const m = await planModel(page, id);
    const personId = m.resources.find(r => r.name === `工程师${suffix}`).resource_id;
    const equipId = m.resources.find(r => r.name === `机床${suffix}`).resource_id;

    await mutate(page, id, '/allocations', { task_id: taskA, resource_id: personId, hours_per_day: 6 });
    await mutate(page, id, '/allocations', { task_id: taskB, resource_id: personId, hours_per_day: 6 });
    await mutate(page, id, '/allocations', { task_id: taskA, resource_id: equipId, hours_per_day: 10 });
    await mutate(page, id, `/resources/${equipId}/capacity`, { date: '2026-09-22', capacity_hours: 12 }, 200, 'PUT');

    // 服务端只读派生回显.
    const plan = await planModel(page, id);
    const s = plan.overload_summary;
    expect(s.available).toBe(true);
    expect(s['total-rows']).toBe(3);
    expect(s['distinct-resources']).toBe(2);
    expect(s['person-rows']).toBe(2);
    expect(s['equipment-rows']).toBe(1);
    expect(Number(s['worst-excess-hours'])).toBeCloseTo(4, 2);
    expect(s['peak-date']).toBe('2026-09-21');
    expect(plan.overallocations.length).toBe(3);

    // 界面: 资源与日历页签顶部"资源负荷检查"面板的只读汇总概览真实可见.
    await open(page, id);
    const ro = panel(page, '资源负荷检查');
    await expect(ro.getByText('超配资源 2', { exact: true })).toBeVisible();
    await expect(ro.getByText('人日超配 2', { exact: true })).toBeVisible();
    await expect(ro.getByText('设备日超配 1', { exact: true })).toBeVisible();
    await expect(ro.getByText(/最大单日超出 4(\.0+)? 工时/)).toBeVisible();
    await expect(ro.getByText('峰值负荷日 2026-09-21', { exact: true })).toBeVisible();
    // 逐日明细表仍在 (超出工时列), 汇总只增概览不改明细.
    await expect(ro.getByRole('columnheader', { name: '超出工时' })).toBeVisible();
    await panelShot(page, '资源负荷检查', 'h05ro-1-panel.png');

    // 空态: 新项目仅设备资源 4h<8 无超配 -> 面板显示"当前排程未发现资源超负荷."
    const suffix2 = `${suffix}e`;
    const p2 = await newProject(page, suffix2, 'empty');
    await leafTask(page, p2.id, 'A', '轻任务A', p2.adminId);
    const m2 = await planModel(page, p2.id);
    const taskA2 = m2.tasks.find(x => x.wbs_code === 'A').task_id;
    await mutate(page, p2.id, '/resources', { name: `空闲机床${suffix2}`, resource_type: 'equipment', daily_capacity: 8 });
    const mm2 = await planModel(page, p2.id);
    const equip2 = mm2.resources.find(r => r.name === `空闲机床${suffix2}`).resource_id;
    await mutate(page, p2.id, '/allocations', { task_id: taskA2, resource_id: equip2, hours_per_day: 4 });
    const plan2 = await planModel(page, p2.id);
    expect(plan2.overload_summary.available).toBe(false);
    expect(plan2.overload_summary['total-rows']).toBe(0);
    expect(plan2.overload_summary['peak-date']).toBeNull();

    await open(page, p2.id);
    const emptyPanel = panel(page, '资源负荷检查');
    await expect(emptyPanel.getByText('当前排程未发现资源超负荷.', { exact: true })).toBeVisible();
    await panelShot(page, '资源负荷检查', 'h05ro-2-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
