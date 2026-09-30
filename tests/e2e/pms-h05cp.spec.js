const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H05 关键路径投入缺口只读汇总: 计划与执行 -> 资源与日历 页签"关键路径投入缺口"面板的只读概览.
// capacity/critical-path-staffing 对关键路径上的可分配叶任务(task_type=task, 排除汇总与里程碑)按工时分配明细
// 读取时聚合, 给出 关键路径任务数/已投入/缺口/投入率与缺口任务清单; 与"任务投入覆盖度"(全部任务)互补,
// 优先暴露"关键路径上还没排人"的最高进度风险. 免迁移/免新kind/免新命令/免新路由, 不构成任何门控.
// 流程: 建项目->长任务 LONG(4 工作日, 关键路径)+短任务 SHORT(1 工作日, 有浮动非关键) -> 设备资源只给 SHORT 分配 4h
//   -> 面板 关键路径任务 1 / 已投入 0 / 投入缺口 1 / 关键路径投入率 0% 且缺口清单含 LONG; GET /planning 回显一致;
//   同时 allocation_coverage(全部任务)=可分配2/已有投入1/覆盖率50%, 证明两面板口径不同(覆盖度含非关键 SHORT, 关键路径只看 LONG).
//   空态: 新项目只有里程碑 -> available=false -> "关键路径上暂无可分配任务 (里程碑与汇总不计入)."
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h05cp');
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
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H05CP-${suffix}`, name: `关键路径投入缺口 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-21', end_date: '2026-12-31' });
  const id = project.project_id;
  await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E立项前置' });
  await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E计划前置' });
  return { id, adminId, deptId };
}

async function leafTask(page, id, code, name, owner, days) {
  await mutate(page, id, '/tasks', { wbs_code: code, name, duration_days: days, start_date: '2026-09-21', owner_id: owner });
}

async function milestone(page, id, code, name, owner) {
  await mutate(page, id, '/tasks', { wbs_code: code, name, task_type: 'milestone', duration_days: 0, start_date: '2026-09-21', owner_id: owner });
}

async function planModel(page, id) {
  return api(page, 'GET', base(id) + '/planning');
}

test.describe('H05 关键路径投入缺口只读汇总浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('关键路径长任务未投入而短任务已投入 -> 缺口面板命中关键任务且服务端回显一致; 空态仅里程碑', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const { id, adminId } = await newProject(page, suffix, 'gap');

    // LONG 4 工作日 -> 关键路径(浮动0); SHORT 1 工作日 -> 有浮动非关键.
    await leafTask(page, id, 'LONG', '关键装配', adminId, 4);
    await leafTask(page, id, 'SHORT', '非关键辅助', adminId, 1);
    const t = await planModel(page, id);
    const taskShort = t.tasks.find(x => x.wbs_code === 'SHORT').task_id;

    // 设备资源(无 user_id, 不参与跨项目同人归集): 只给非关键 SHORT 分配 4h, 关键 LONG 不分配.
    await mutate(page, id, '/resources', { name: `机床${suffix}`, resource_type: 'equipment', daily_capacity: 8 });
    const m = await planModel(page, id);
    const equipId = m.resources.find(r => r.name === `机床${suffix}`).resource_id;
    await mutate(page, id, '/allocations', { task_id: taskShort, resource_id: equipId, hours_per_day: 4 });

    // 服务端只读派生回显.
    const plan = await planModel(page, id);
    const cp = plan.critical_path_staffing;
    expect(cp.available).toBe(true);
    expect(cp['critical-tasks']).toBe(1);
    expect(cp['staffed']).toBe(0);
    expect(cp['unstaffed']).toBe(1);
    expect(cp['staffing-pct']).toBe(0);
    expect(cp['unstaffed-tasks'].map(x => x.wbs_code)).toEqual(['LONG']);
    // 与"任务投入覆盖度"口径不同: 覆盖度含非关键 SHORT -> 可分配2/已投入1/50%.
    const cov = plan.allocation_coverage;
    expect(cov['total-tasks']).toBe(2);
    expect(cov['with-allocations']).toBe(1);
    expect(cov['coverage-pct']).toBe(50);

    // 界面: 资源与日历页签"关键路径投入缺口"面板只读汇总真实可见.
    await open(page, id);
    const gap = panel(page, '关键路径投入缺口');
    await expect(gap.getByText('关键路径任务 1', { exact: true })).toBeVisible();
    await expect(gap.getByText('已投入 0', { exact: true })).toBeVisible();
    await expect(gap.getByText('投入缺口 1', { exact: true })).toBeVisible();
    await expect(gap.getByText('关键路径投入率 0%', { exact: true })).toBeVisible();
    await expect(gap.getByText('关键路径上未排工时的任务:', { exact: true })).toBeVisible();
    await expect(gap.getByText(/LONG\s*关键装配/)).toBeVisible();
    await panelShot(page, '关键路径投入缺口', 'h05cp-1-panel.png');

    // 空态: 新项目只有里程碑 -> available=false -> 面板显"关键路径上暂无可分配任务 (里程碑与汇总不计入)."
    const suffix2 = `${suffix}e`;
    const p2 = await newProject(page, suffix2, 'empty');
    await milestone(page, p2.id, 'M', '唯一里程碑', p2.adminId);
    const plan2 = await planModel(page, p2.id);
    expect(plan2.critical_path_staffing.available).toBe(false);
    expect(plan2.critical_path_staffing['critical-tasks']).toBe(0);
    expect(plan2.critical_path_staffing['staffing-pct']).toBe(0);

    await open(page, p2.id);
    const emptyPanel = panel(page, '关键路径投入缺口');
    await expect(emptyPanel.getByText('关键路径上暂无可分配任务 (里程碑与汇总不计入).', { exact: true })).toBeVisible();
    await panelShot(page, '关键路径投入缺口', 'h05cp-2-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
