const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H05 任务投入覆盖度只读汇总: 计划与执行 -> 资源与日历 页签"任务投入覆盖度"面板的只读概览.
// capacity/allocation-coverage 对可分配叶任务(task_type=task, 排除汇总与里程碑)按工时分配明细读取时聚合
// 给出 可分配总数/已有投入/未投入/投入覆盖率与未投入任务清单; 免迁移/免新kind/免新命令/免新路由,
// 不构成任何门控(仅只读呈现, 不阻断任何写操作).
// 流程: 建项目->三条叶任务 A/B/C(09-21 起 2 工作日)+ 一条里程碑 M -> 设备资源分配 A 4h + B 4h, C 不分配
//   -> 面板 可分配任务 3 / 已有投入 2 / 未投入 1 / 投入覆盖率 67% 且未投入清单含 C; GET /planning 回显 allocation_coverage 一致.
//   空态: 新项目只有里程碑 -> available=false -> "尚无普通任务 (汇总与里程碑不计入投入覆盖)."
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h05ac');
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
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H05AC-${suffix}`, name: `投入覆盖度 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-21', end_date: '2026-12-31' });
  const id = project.project_id;
  await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E立项前置' });
  await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E计划前置' });
  return { id, adminId, deptId };
}

async function leafTask(page, id, code, name, owner) {
  await mutate(page, id, '/tasks', { wbs_code: code, name, duration_days: 2, start_date: '2026-09-21', owner_id: owner });
}

async function milestone(page, id, code, name, owner) {
  await mutate(page, id, '/tasks', { wbs_code: code, name, task_type: 'milestone', duration_days: 0, start_date: '2026-09-21', owner_id: owner });
}

async function planModel(page, id) {
  return api(page, 'GET', base(id) + '/planning');
}

test.describe('H05 任务投入覆盖度只读汇总浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('三叶任务两叶已投入 -> 覆盖度面板可见且服务端回显一致; 空态无普通任务', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const { id, adminId } = await newProject(page, suffix, 'coverage');

    // 三条可分配叶任务 + 一条里程碑(不计入分母).
    await leafTask(page, id, 'A', '装配A', adminId);
    await leafTask(page, id, 'B', '调试B', adminId);
    await leafTask(page, id, 'C', '未分配C', adminId);
    await milestone(page, id, 'M', '交付里程碑', adminId);
    const t = await planModel(page, id);
    const taskA = t.tasks.find(x => x.wbs_code === 'A').task_id;
    const taskB = t.tasks.find(x => x.wbs_code === 'B').task_id;

    // 设备资源(无 user_id, 不参与跨项目同人归集): A 4h + B 4h; C 不分配.
    await mutate(page, id, '/resources', { name: `机床${suffix}`, resource_type: 'equipment', daily_capacity: 8 });
    const m = await planModel(page, id);
    const equipId = m.resources.find(r => r.name === `机床${suffix}`).resource_id;
    await mutate(page, id, '/allocations', { task_id: taskA, resource_id: equipId, hours_per_day: 4 });
    await mutate(page, id, '/allocations', { task_id: taskB, resource_id: equipId, hours_per_day: 4 });

    // 服务端只读派生回显.
    const plan = await planModel(page, id);
    const cov = plan.allocation_coverage;
    expect(cov.available).toBe(true);
    expect(cov['total-tasks']).toBe(3);
    expect(cov['with-allocations']).toBe(2);
    expect(cov['without-allocations']).toBe(1);
    expect(cov['coverage-pct']).toBe(67);
    expect(cov['unallocated-tasks'].map(x => x.wbs_code)).toEqual(['C']);

    // 界面: 资源与日历页签"任务投入覆盖度"面板只读汇总真实可见.
    await open(page, id);
    const ac = panel(page, '任务投入覆盖度');
    await expect(ac.getByText('可分配任务 3', { exact: true })).toBeVisible();
    await expect(ac.getByText('已有投入 2', { exact: true })).toBeVisible();
    await expect(ac.getByText('未投入 1', { exact: true })).toBeVisible();
    await expect(ac.getByText('投入覆盖率 67%', { exact: true })).toBeVisible();
    await expect(ac.getByText('未投入工时的任务:', { exact: true })).toBeVisible();
    await expect(ac.getByText(/C\s*未分配C/)).toBeVisible();
    await panelShot(page, '任务投入覆盖度', 'h05ac-1-panel.png');

    // 空态: 新项目只有里程碑 -> available=false -> 面板显"尚无普通任务 (汇总与里程碑不计入投入覆盖)."
    const suffix2 = `${suffix}e`;
    const p2 = await newProject(page, suffix2, 'empty');
    await milestone(page, p2.id, 'M', '唯一里程碑', p2.adminId);
    const plan2 = await planModel(page, p2.id);
    expect(plan2.allocation_coverage.available).toBe(false);
    expect(plan2.allocation_coverage['total-tasks']).toBe(0);
    expect(plan2.allocation_coverage['coverage-pct']).toBe(0);

    await open(page, p2.id);
    const emptyPanel = panel(page, '任务投入覆盖度');
    await expect(emptyPanel.getByText('尚无普通任务 (汇总与里程碑不计入投入覆盖).', { exact: true })).toBeVisible();
    await panelShot(page, '任务投入覆盖度', 'h05ac-2-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
