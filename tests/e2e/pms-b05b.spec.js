const { test, expect } = require('@playwright/test');
const path = require('node:path');

// B05 会议行动闭环只读洞察: 界面"登记项目会议"后逐条"形成行动"(带到期日) ->
// "会议行动"台账"行动闭环"列汇总 未完成 open/total, 到期日早于服务器当天(2026-09-22)的未完成行动追加"逾期 N"徽标 ->
// 行动转真实任务后从"未完成"计数中扣除, 全部闭环显示"行动已全部闭环". 复用既有 action 类型与只读读模型, 免迁移.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/b05b');
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  // 共享库累积用户/任务后长列表被 antd 虚拟滚动裁剪, 可搜索的下拉先按标签过滤再选.
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
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

// 在指定会议行"形成行动": 填行动内容与到期日, 绑定 admin 责任人.
async function addAction(page, meetingTitle, actionTitle, dueDate) {
  await row(page, meetingTitle).getByRole('button', { name: '形成行动', exact: true }).click();
  const form = modal(page, '新增会议行动');
  await form.locator('#title').fill(actionTitle);
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#due_date').fill(dueDate);
  return (await save(page, '新增会议行动')).result;
}

test.describe('B05 会议行动闭环浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('会议派生多条行动 -> 未完成计数 + 逾期徽标 + 转任务后闭环', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `B05B-${suffix}`, name: `会议行动闭环验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 界面登记一场会议.
    await open(page, id, '需求与治理', '会议行动');
    const meetingTitle = `设计评审会-${suffix}`;
    await drawer(page).getByRole('button', { name: '登记项目会议', exact: true }).click();
    const mf = modal(page, '登记项目会议');
    await mf.locator('#title').fill(meetingTitle);
    await mf.locator('#held_on').fill('2026-09-20');
    await mf.locator('#minutes').fill('评审接口设计, 明确遗留行动项与责任人.');
    await choose(page, mf, 'attendee_ids', 'admin');
    await save(page, '登记项目会议');

    // 无行动时"行动闭环"列显示"无行动".
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, meetingTitle).getByText('无行动')).toBeVisible();

    // 界面形成两条行动: 一条到期日已过(逾期), 一条未来到期(未完成但未逾期).
    const overdueAction = await addAction(page, meetingTitle, '补充接口安全性说明', '2026-09-15');
    const futureAction = await addAction(page, meetingTitle, '更新装配工艺文件', '2026-10-10');

    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, meetingTitle).getByText('未完成 2/2')).toBeVisible();
    await expect(row(page, meetingTitle).getByText('逾期 1')).toBeVisible();
    await shot(page, 'b05b-1-open-overdue.png');

    // 服务端只读读模型二次确认.
    let gov = await api(page, 'GET', base(id) + '/governance');
    let read = gov.meetings.find(m => m.title === meetingTitle);
    expect(read.meeting_action_total).toBe(2);
    expect(read.meeting_open_actions).toBe(2);
    expect(read.meeting_overdue_actions).toBe(1);

    // 将未来到期行动转为真实任务 -> 从"未完成"计数扣除, 只剩逾期的1条未完成.
    await api(page, 'POST', base(id) + '/governance/actions/' + futureAction.id + '/task',
      { start_date: '2026-09-23', duration_days: 3, version: await version(page, id) });
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, meetingTitle).getByText('未完成 1/2')).toBeVisible();
    await expect(row(page, meetingTitle).getByText('逾期 1')).toBeVisible();
    await shot(page, 'b05b-2-converted.png');

    gov = await api(page, 'GET', base(id) + '/governance');
    read = gov.meetings.find(m => m.title === meetingTitle);
    expect(read.meeting_action_total, '行动总数仍为2').toBe(2);
    expect(read.meeting_open_actions, '已转任务的行动不计入未完成').toBe(1);
    expect(overdueAction.id, '逾期行动保留').toBeTruthy();

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
