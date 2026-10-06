const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H04 关键路径敏感度只读汇总: 计划与执行 -> 资源与日历 页签"关键路径敏感度"面板的只读概览.
// schedule/float-sensitivity 把 CPM 排程的每任务总时差只读派生为三档 —— 关键(总时差=0)/近关键(0<总时差<=band)/宽松(>band),
// band 随项目工作日跨度自适应(至少 2 个工作日), 并给出按时差升序的近关键清单(携带 WBS 编号)与最小近关键时差;
// 优先暴露"关键链之外, 轻微滑移即会上关键链"的进度风险. 免迁移/免新kind/免新命令/免新路由, 不构成任何门控.
// 流程: 建项目 -> 三条独立任务 L(5 工作日, 关键) N(3, 浮动2=近关键) C(1, 浮动4=宽松) 同日开工
//   -> span 5, band=max(2,round(0.5))=2 -> 面板 关键任务1/近关键任务1/宽松任务1/近关键带宽2天/最小近关键时差2天, 近关键清单含 N;
//   GET /planning 的 schedule_sensitivity 回显一致 (分区穷尽: leaf-count 3 = 1+1+1).
//   空态: 新项目无任何任务 -> available=false -> "尚未排程 (需任务设置工期与依赖后计算总时差)."
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h04cps');
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
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H04CPS-${suffix}`, name: `关键路径敏感度 / ${suffix}`,
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

test.describe('H04 关键路径敏感度只读汇总浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('关键/近关键/宽松三档共存 -> 敏感度面板命中各档且服务端回显一致; 空态无任何任务', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const { id, adminId } = await newProject(page, suffix, 'sens');

    // 三条独立任务, 同日开工: L 5 工作日 -> 最长即关键(浮动0); N 3 -> 浮动2(=band 近关键); C 1 -> 浮动4(>band 宽松).
    await leafTask(page, id, 'L', '关键装配甲', adminId, 5);
    await leafTask(page, id, 'N', '近关键装配乙', adminId, 3);
    await leafTask(page, id, 'C', '宽松辅助丙', adminId, 1);

    // 服务端只读派生回显.
    const plan = await planModel(page, id);
    const s = plan.schedule_sensitivity;
    expect(s.available).toBe(true);
    expect(s.span).toBe(5);
    expect(s.band).toBe(2);
    expect(s['leaf-count']).toBe(3);
    expect(s['critical-count']).toBe(1);
    expect(s['near-critical-count']).toBe(1);
    expect(s['comfortable-count']).toBe(1);
    // 分区穷尽: leaf-count = critical + near + comfortable.
    expect(s['leaf-count']).toBe(s['critical-count'] + s['near-critical-count'] + s['comfortable-count']);
    expect(s['min-near-float']).toBe(2);
    expect(s['near-critical-tasks'].map(x => x.wbs_code)).toEqual(['N']);
    // 近关键清单每项 0 < 总时差 <= band.
    for (const t of s['near-critical-tasks']) {
      expect(t.total_float).toBeGreaterThan(0);
      expect(t.total_float).toBeLessThanOrEqual(s.band);
    }

    // 界面: 资源与日历页签"关键路径敏感度"面板只读汇总真实可见.
    await open(page, id);
    const card = panel(page, '关键路径敏感度');
    await expect(card.getByText('关键任务 1', { exact: true })).toBeVisible();
    await expect(card.getByText('近关键任务 1', { exact: true })).toBeVisible();
    await expect(card.getByText('宽松任务 1', { exact: true })).toBeVisible();
    await expect(card.getByText('近关键带宽 2 天', { exact: true })).toBeVisible();
    await expect(card.getByText('最小近关键时差 2 天', { exact: true })).toBeVisible();
    await expect(card.getByText('滑移即上关键链的近关键任务 (按时差升序):', { exact: true })).toBeVisible();
    await expect(card.getByText(/N\s*近关键装配乙\s*·\s*时差 2 天/)).toBeVisible();
    await panelShot(page, '关键路径敏感度', 'h04cps-1-panel.png');

    // 空态: 新项目无任何任务 -> available=false -> 面板显"尚未排程 (需任务设置工期与依赖后计算总时差)."
    const suffix2 = `${suffix}e`;
    const p2 = await newProject(page, suffix2, 'empty');
    const plan2 = await planModel(page, p2.id);
    expect(plan2.schedule_sensitivity.available).toBe(false);
    expect(plan2.schedule_sensitivity['leaf-count']).toBe(0);

    await open(page, p2.id);
    const emptyPanel = panel(page, '关键路径敏感度');
    await expect(emptyPanel.getByText('尚未排程 (需任务设置工期与依赖后计算总时差).', { exact: true })).toBeVisible();
    await panelShot(page, '关键路径敏感度', 'h04cps-2-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
