const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H05 资源投入结构 (资源类别构成) 只读汇总: 计划与执行 -> 资源与日历 页签"资源投入结构"面板的只读概览.
// capacity/resource-type-mix 把每个资源按其 resource_type (person 人力 / equipment 设备) 分组, 逐资源把工时摊到其任务工作日后按类别汇总,
// 读取时派生 available/type-count/total-committed-hours/types(每项含 resource_type/resource-total/engaged/idle/committed-hours/share-pct)/dominant-type/dominant-share-pct/structure-level.
// 与"资源负荷检查"(谁超容量) / "任务投入覆盖度"(谁没排) / "关键路径投入缺口"(关键路径缺不缺人) / "资源投入均衡度"(忙闲均不均) / "资源容量利用率"(填了多少) 五项正交 ——
// 那五项逐日逐任务逐被排入资源, 本项把视角拉到类别层面: 工时在人力/设备之间怎么分布, 是否"严重偏科"(几乎只靠一类) 或"整类闲置"(某类建了却一条工时都没排).
// 档位: 无任何资源->nil; 排了资源却零投入->unassigned; 主导类别占比>=80->concentrated; >=60->skewed; 否则->balanced.
// 免迁移/免新kind/免新命令/免新路由/免门控, 纯只读派生.
// 流程: 建项目 -> 任务A(2 工作日, 起 2026-09-21) -> admin 入成员 -> 人力资源 (daily_capacity 8, hours_per_day 6) + 设备资源 (建了不排):
//   committed 仅人力 12 工时 -> 人力 share 100% concentrated, 设备 engaged 0/idle 1/share 0;
//   dominant-type=person / dominant-share-pct=100 / structure-level=concentrated / type-count=2; GET /planning 的 resource_type_mix 回显一致.
//   空态: 新项目无任何资源 -> available=false -> "尚无资源 (创建人力或设备资源后方可评估投入结构)."
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h05rtm');
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
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H05RTM-${suffix}`, name: `资源投入结构 / ${suffix}`,
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

test.describe('H05 资源投入结构只读汇总浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('人力重载 + 设备整类闲置 -> 主导类别/档位/类别清单命中且服务端回显一致; 空态无资源', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const { id, adminId } = await newProject(page, suffix);

    // A 排定 2 个工作日 (09-21/22); admin 即项目经理, kernel/user! 允许管理者本人作资源归属, 无需再入成员.
    const a = await leafTask(page, id, 'A', '共用装配任务', adminId, 2);
    await mutate(page, id, '/resources', { name: `装配工程师${suffix}`, resource_type: 'person', user_id: adminId, daily_capacity: 8 });
    await mutate(page, id, '/resources', { name: `闲置机床${suffix}`, resource_type: 'equipment', daily_capacity: 8 });
    const person = await resourceIdByName(page, id, `装配工程师${suffix}`);
    await mutate(page, id, '/allocations', { task_id: a.task_id, resource_id: person, hours_per_day: 6 });
    // 设备资源建了却不排任何工时 -> 整类闲置.

    // 服务端只读派生回显.
    const plan = await api(page, 'GET', base(id) + '/planning');
    const r = plan.resource_type_mix;
    expect(r.available).toBe(true);
    expect(r['type-count']).toBe(2);
    expect(r['dominant-type']).toBe('person');
    expect(r['dominant-share-pct']).toBe(100);
    expect(r['structure-level']).toBe('concentrated');
    const personRow = r.types.find(t => t.resource_type === 'person');
    const equipRow = r.types.find(t => t.resource_type === 'equipment');
    expect(personRow['share-pct']).toBe(100);
    expect(personRow.engaged).toBe(1);
    expect(personRow.idle).toBe(0);
    expect(personRow['resource-total']).toBe(1);
    expect(personRow['committed-hours']).toBeGreaterThan(0);
    expect(equipRow['share-pct']).toBe(0);
    expect(equipRow.engaged).toBe(0);
    expect(equipRow.idle).toBe(1);
    expect(equipRow['resource-total']).toBe(1);
    expect(equipRow['committed-hours']).toBe(0);

    // 界面: 资源与日历页签"资源投入结构"面板只读汇总真实可见.
    await open(page, id);
    const card = panel(page, '资源投入结构');
    await expect(card.getByText('投入结构 严重偏科 (几乎只靠一类)', { exact: true })).toBeVisible();
    await expect(card.getByText('主导类别 人力 100%', { exact: true })).toBeVisible();
    await expect(card.getByText('资源类别 2 类', { exact: true })).toBeVisible();
    await expect(card.getByText(/投入合计 .* 工时/)).toBeVisible();
    await expect(card.getByText(/人力 100% · 投入 .* 工时 · 已排入 1\/1/)).toBeVisible();
    await expect(card.getByText(/设备 0% · 投入 .* 工时 · 已排入 0\/1 · 闲置 1/)).toBeVisible();
    await panelShot(page, '资源投入结构', 'h05rtm-1-panel.png');

    // 空态: 新项目无任何资源 -> available=false -> 面板显空态文案.
    const p2 = await newProject(page, `${suffix}e`);
    const plan2 = await api(page, 'GET', base(p2.id) + '/planning');
    expect(plan2.resource_type_mix.available).toBe(false);
    expect(plan2.resource_type_mix['type-count']).toBe(0);
    expect(plan2.resource_type_mix['structure-level']).toBeFalsy();
    await open(page, p2.id);
    const emptyPanel = panel(page, '资源投入结构');
    await expect(emptyPanel.getByText('尚无资源 (创建人力或设备资源后方可评估投入结构).', { exact: true })).toBeVisible();
    await panelShot(page, '资源投入结构', 'h05rtm-2-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
