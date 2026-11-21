const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H05 任务技能匹配只读派生: 任务在 WBS 对话框可选声明"所需技能"(逗号分隔), 人力资源在资源对话框可选声明
// "已掌握技能". 计划与执行 -> WBS与排程 的"任务技能匹配"面板在读取时把每条任务所需技能与承担该任务的人力
// (person 资源, 设备资源不计) 已声明技能逐条比对, 给出已匹配/未匹配与逐任务缺失技能清单.
// 造 任务A 需 焊接,探伤(排入只懂焊接的人) / 任务B 需 装配(排入只懂装配的人) -> 期望 技能要求 2 / 已匹配 1 (50%) /
// 未匹配 1 且 A 缺"探伤". 面板界面可见, 并以 GET /planning 服务端回显二次确认; 补声明探伤技能后翻转为 100%.
// 最后技能不匹配不阻断计划提交冻结. 空态(无任务声明技能)单独截图.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h05sk');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
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
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data });
  const text = await response.text();
  let body;
  try { body = JSON.parse(text); } catch (e) { throw new Error(`${method} ${url} -> ${response.status()} ${text.slice(0, 200)}`); }
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

async function mutate(page, id, suffix, data = {}, expected = 200, verb = 'POST') {
  const project = await api(page, 'GET', base(id));
  return api(page, verb, base(id) + suffix, { ...data, version: project.version }, expected);
}

async function tab(page, name) {
  await drawer(page).getByRole('tab', { name, exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function open(page, id, section, subsection) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  if (section) await tab(page, section);
  if (subsection) await tab(page, subsection);
}

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  const options = dropdown.locator('.ant-select-item-option');
  await options.filter({ hasText: text }).first().click({ timeout: 8000 });
  await form.locator(`#${key}`).press('Escape');
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
}

async function save(page, title) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body.data;
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

async function wbsTask(page, code, name, days, skills) {
  await drawer(page).getByRole('button', { name: '新建WBS任务', exact: true }).click();
  const form = modal(page, '新建WBS任务');
  await fill(form, { wbs_code: code, name, duration_days: days, start_date: '2026-09-22' });
  if (skills) await form.locator('#required_skills').fill(skills);
  await choose(page, form, 'owner_id', /\/ admin$/);
  return (await save(page, '新建WBS任务')).result;
}

async function resource(page, name, skills, capacity = 8) {
  await drawer(page).getByRole('button', { name: '新增项目资源', exact: true }).click();
  const form = modal(page, '新增项目资源');
  await fill(form, { name, daily_capacity: capacity });
  await choose(page, form, 'user_id', /\/ admin$/);
  if (skills) await form.locator('#skills').fill(skills);
  return (await save(page, '新增项目资源')).result;
}

test.describe('H05 任务技能匹配只读派生浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('任务所需技能与人力已掌握技能逐条比对 -> 面板可见, 服务端回显一致, 补技能后翻转, 不阻断冻结', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H05SK-${suffix}`, name: `技能匹配验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E立项前置' });
    await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E计划前置' });

    // 界面建两条任务并声明所需技能 (均派给 admin).
    await open(page, id, '计划与执行', 'WBS与排程');
    const taskA = await wbsTask(page, '1.1', '焊接作业', 2, '焊接,探伤');
    const taskB = await wbsTask(page, '1.2', '装配作业', 2, '装配');
    expect(taskA.required_skills).toBe('焊接,探伤');
    expect(taskB.required_skills).toBe('装配');

    // 资源与分配均在"资源与日历"页签; 界面建焊工资源并声明已掌握技能.
    await open(page, id, '计划与执行', '资源与日历');
    const welder = await resource(page, `焊工${suffix}`, '焊接', 24);
    expect(welder.skills).toBe('焊接');
    await drawer(page).getByRole('button', { name: '分配任务资源', exact: true }).click();
    const allocForm = modal(page, '分配任务资源');
    await choose(page, allocForm, 'task_id', '焊接作业');
    await choose(page, allocForm, 'resource_id', `焊工${suffix}`);
    await fill(allocForm, { hours_per_day: 6 });
    await save(page, '分配任务资源');

    // 同一成员只允许一个资源档案: 通过真实用户接口各建一位成员承载装配工/焊接工资源.
    const newUser = async (prefix, nick) => {
      const user = `${prefix}${suffix}`;
      await api(page, 'POST', '/api/system/user',
        { user_name: user, nick_name: `${nick} ${suffix}`, password: 'asm123456', status: '0', dept_id: deptId, posts: [], roles: [2] });
      const uid = (await api(page, 'GET', `/api/system/user?page=1&size=20&user_name=${user}`)).rows[0].user_id;
      await api(page, 'POST', base(id) + '/members', { user_id: uid, role: 'viewer' });
      return uid;
    };
    const asmId = await newUser('h05sa', '装配工');
    await api(page, 'POST', base(id) + '/members', { user_id: asmId, role: 'viewer' });
    const { result: assembler } = await mutate(page, id, '/resources', { name: `装配工${suffix}`, resource_type: 'person',
      user_id: asmId, daily_capacity: 8, skills: ['装配'] });
    expect(assembler.skills).toBe('装配');
    await mutate(page, id, '/allocations', { task_id: taskB.task_id, resource_id: assembler.resource_id, hours_per_day: 6 });

    // 面板: 技能要求 2 / 已匹配 1 (50%) / 未匹配 1 且 A 缺探伤.
    await open(page, id, '计划与执行', 'WBS与排程');
    const smPanel = panel(page, '任务技能匹配');
    await expect(smPanel.getByText('技能要求 2', { exact: true })).toBeVisible();
    await expect(smPanel.getByText('已匹配 1 (50%)', { exact: true })).toBeVisible();
    await expect(smPanel.getByText('未匹配 1', { exact: true })).toBeVisible();
    await expect(smPanel.getByText(/1\.1 焊接作业 缺: 探伤/)).toBeVisible();
    await panelShot(page, '任务技能匹配', 'h05sk-1-panel.png');
    await shot(page, 'h05sk-2-page.png');

    // 服务端读取时派生回显二次确认.
    const plan = await api(page, 'GET', base(id) + '/planning');
    const sm = plan.skill_match;
    expect(sm.available).toBe(true);
    expect(sm['total-requirements']).toBe(2);
    expect(sm.matched).toBe(1);
    expect(sm.unmatched).toBe(1);
    expect(sm['coverage-pct']).toBe(50);
    const rowA = sm.tasks.find(t => t.wbs_code === '1.1');
    expect(rowA.required).toEqual(['焊接', '探伤']);
    expect(rowA.covered).toEqual(['焊接']);
    expect(rowA.missing).toEqual(['探伤']);
    expect(rowA.matched).toBe(false);
    const rowB = sm.tasks.find(t => t.wbs_code === '1.2');
    expect(rowB.matched).toBe(true);

    // 补声明探伤技能后翻转为 100%, 证明是读取时派生而非落库快照.
    await mutate(page, id, `/resources/${welder.resource_id}`, { skills: ['焊接', '探伤'] }, 200, 'PUT');
    await open(page, id, '计划与执行', 'WBS与排程');
    const smPanel2 = panel(page, '任务技能匹配');
    await expect(smPanel2.getByText('已匹配 2 (100%)', { exact: true })).toBeVisible();
    await expect(smPanel2.getByText('未匹配 0', { exact: true })).toBeVisible();
    const plan2 = await api(page, 'GET', base(id) + '/planning');
    expect(plan2.skill_match.matched).toBe(2);
    expect(plan2.skill_match['coverage-pct']).toBe(100);

    // 冻结前置: admin 种子账号在多个历史项目里均有人员资源, 共享负荷会让提交被判超配.
    // 这里把任务与资源都改挂一位专属新成员(只懂焊接+探伤), 使提交只受常规门控约束; 技能匹配本身从不门控.
    const weldId = await newUser('h05sw', '焊接工');
    const { result: welder2 } = await mutate(page, id, '/resources', { name: `焊接工${suffix}`, resource_type: 'person',
      user_id: weldId, daily_capacity: 8, skills: ['焊接', '探伤'] });
    const planNow = await api(page, 'GET', base(id) + '/planning');
    const allocA = planNow.allocations.find(a => a.resource_id === welder.resource_id);
    await mutate(page, id, `/allocations/${allocA.allocation_id}`, {}, 200, 'DELETE');
    await mutate(page, id, `/resources/${welder.resource_id}`, {}, 200, 'DELETE');
    for (const t of planNow.tasks) await mutate(page, id, `/tasks/${t.task_id}`, { owner_id: weldId }, 200, 'PUT');
    await mutate(page, id, '/allocations', { task_id: taskA.task_id, resource_id: welder2.resource_id, hours_per_day: 1 });

    // 只读不门控: 技能不匹配不阻断计划提交冻结 (此处已匹配, 冻结路径与 H03 一致).
    await open(page, id, '计划与执行', '审批与基线');
    await drawer(page).getByRole('button', { name: '提交计划审批', exact: true }).click();
    await fill(modal(page, '提交计划审批'), { comment: '技能匹配只读派生, 不阻断冻结' });
    await save(page, '提交计划审批');
    await expect(drawer(page).getByText('计划已冻结,等待独立审批.')).toBeVisible();

    // 空态: 新项目任务不声明技能, 面板提示尚无技能要求.
    const empty = await api(page, 'POST', '/api/pms/projects', { project_no: `H05SK0-${suffix}`, name: `技能匹配空态 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    await mutate(page, empty.project_id, '/transition', { status: 'initiated', reason: 'E2E空态前置' });
    await mutate(page, empty.project_id, '/transition', { status: 'planning', reason: 'E2E空态前置' });
    await open(page, empty.project_id, '计划与执行', 'WBS与排程');
    const emptyPanel = panel(page, '任务技能匹配');
    await expect(emptyPanel.getByText('尚无任务声明所需技能', { exact: true })).toBeVisible();
    await panelShot(page, '任务技能匹配', 'h05sk-3-empty.png');
    const emptyPlan = await api(page, 'GET', base(empty.project_id) + '/planning');
    expect(emptyPlan.skill_match.available).toBe(false);
    expect(emptyPlan.skill_match['coverage-pct']).toBe(0);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});