const { test, expect } = require('@playwright/test');
const path = require('node:path');

const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../..');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
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
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

async function version(page, id) {
  const project = await api(page, 'GET', base(id));
  return project.version;
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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('H02 干系人, RACI与沟通计划浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(120000);

  test('治理工作台干系人识别, RACI冲突提示与沟通计划生成会议闭环', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const project = await api(page, 'POST', '/api/pms/projects', {
      project_no: `H02-${suffix}`, name: `干系人沟通验收 / ${suffix}`, project_type: 'line',
      manager_id: options.currentUserId, dept_id: deptId,
      start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // API 基准: 绑定项目成员为责任人的干系人, 只指派R的活动(造成缺A冲突), 以及一个沟通计划
    const sh = await api(page, 'POST', base(id) + '/governance/stakeholders', {
      code: 'SH-A', name: '设备工程师' + suffix, role: '设备', category: 'internal',
      interest: 'high', influence: 'medium', owner_id: options.currentUserId, version: await version(page, id) });
    expect(sh.result.status).toBe('active');

    await api(page, 'POST', base(id) + '/governance/raci', {
      activity: '出厂验收', stakeholder_id: sh.result.id, responsibility: 'R', version: await version(page, id) });

    const plan = await api(page, 'POST', base(id) + '/governance/comm-plans', {
      code: 'CP-A', objective: '每周设备进度沟通', channel: 'meeting', frequency: 'weekly',
      audience: [sh.result.id], next_date: '2026-09-26', version: await version(page, id) });
    expect(plan.result.status).toBe('active');
    expect(plan.result.last_meeting_id).toBeFalsy();

    // 打开治理工作台 -> 干系人与沟通页签, 校验读模型渲染
    await open(page, id, '需求与治理', '干系人与沟通');
    await expect(row(page, 'SH-A')).toBeVisible();
    await expect(row(page, 'CP-A')).toBeVisible();
    // 出厂验收只有R没有A, 应出现RACI完整性缺口提示
    await expect(drawer(page).getByText('RACI完整性缺口')).toBeVisible();
    await expect(drawer(page).getByText(/缺少负责\(A\)/)).toBeVisible();

    // 界面再登记一个干系人(分类等下拉由初始值预填), 保存后表格出现新行
    await drawer(page).getByRole('button', { name: '登记干系人', exact: true }).click();
    const shForm = modal(page, '登记干系人');
    await shForm.locator('#code').fill('SH-B');
    await shForm.locator('#name').fill('质量经理' + suffix);
    await shForm.locator('#role').fill('质量');
    await shForm.getByRole('button', { name: /保\s*存/ }).click();
    await expect(shForm).toBeHidden();
    await expect(row(page, 'SH-B')).toBeVisible();

    // 界面由沟通计划生成一次受控会议, 保存后计划回写最近会议ID, 形成沟通计划到会议闭环
    await row(page, 'CP-A').getByRole('button', { name: '生成会议', exact: true }).click();
    const mtForm = modal(page, '生成沟通计划会议');
    await mtForm.getByRole('button', { name: /保\s*存/ }).click();
    await expect(mtForm).toBeHidden();

    const gov = await api(page, 'GET', base(id) + '/governance');
    expect(gov.stakeholders.filter(s => s.project_id === id).map(s => s.code).sort()).toEqual(['SH-A', 'SH-B']);
    const planRow = gov.comm_plans.find(c => c.code === 'CP-A');
    expect(planRow.last_meeting_id, '沟通计划应回写生成的会议ID').toBeTruthy();
    const meeting = gov.meetings.find(m => m.id === planRow.last_meeting_id);
    expect(meeting, '会议读模型可查').toBeTruthy();
    expect(meeting.status).toBe('recorded');
    expect(meeting.attendee_ids).toContain(options.currentUserId);
    // 只指派R时该活动缺失A, 冲突读模型应包含它
    expect(gov.raci_conflicts.some(c => c.activity === '出厂验收' && c['missing-accountable?'])).toBeTruthy();

    await shot(page, 'pms-h02-stakeholders.png');
    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
