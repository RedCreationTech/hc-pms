const { test, expect } = require('@playwright/test');
const path = require('node:path');

// 到期倒计时只读派生列: 问题与行动台账按服务端派生的剩余天数渲染"到期倒计时"列, 逾期红色, 临期(1-3天)金色,
// 尚远蓝色; 会议行动"转为WBS任务"后从倒计时中剔除. 日期用相对服务器当天的偏移(±1 天漂移免疫: 取窗口内 n=2/30/-5),
// 断言按类别(正则匹配文本)与服务端回显(issue_due_in_days / issue_due_soon / issue_overdue)双重确认.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/due');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const dayOffset = n => { const d = new Date(); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };

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

async function mutateData(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version });
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

const sevLabel = { blocker: '阻断', major: '严重', minor: '一般' };

async function createIssue(page, id, { title, severity, due }) {
  await open(page, id, '需求与治理', '风险与问题');
  await drawer(page).getByRole('button', { name: '登记项目问题', exact: true }).click();
  const form = modal(page, '登记项目问题');
  await form.locator('#title').fill(title);
  await choose(page, form, 'severity', sevLabel[severity]);
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#due_date').fill(due);
  return save(page, '登记项目问题');
}

async function createProject(page, suffix, deptId, adminId) {
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `DUE-${suffix}`, name: `到期倒计时验收 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
  return project.project_id;
}

test.describe('问题与行动到期倒计时只读派生列浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('三档倒计时(临期/尚远/逾期)界面回显并经服务端二次确认; 行动转任务后倒计时消失', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const id = await createProject(page, suffix, deptId, adminId);

    const soonTitle = `标定整改问题-${suffix}`;
    const farTitle = `远期观察问题-${suffix}`;
    const overTitle = `未决处置问题-${suffix}`;
    await createIssue(page, id, { title: soonTitle, severity: 'major', due: dayOffset(2) });
    await createIssue(page, id, { title: farTitle, severity: 'major', due: dayOffset(30) });
    await createIssue(page, id, { title: overTitle, severity: 'major', due: dayOffset(-5) });

    // HTTP 登记会议 + 一条临期会议行动(避免多人下拉噪声; 仍走同一台账派生).
    const actionTitle = `来料整改行动-${suffix}`;
    const meeting = (await mutateData(page, id, '/governance/meetings',
      { title: `倒计时复盘会-${suffix}`, held_on: dayOffset(0), minutes: '行动到期跟踪', attendee_ids: [adminId] })).result;
    await mutateData(page, id, `/governance/meetings/${meeting.id}/actions`,
      { title: actionTitle, owner_id: adminId, due_date: dayOffset(2) });

    // 截图1: "风险与问题"页签三档倒计时徽标.
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, soonTitle).getByText(/临期/)).toBeVisible();
    await expect(row(page, farTitle).getByText(/^剩 \d+ 天$/)).toBeVisible();
    await expect(row(page, farTitle).getByText(/临期/)).toHaveCount(0);
    await expect(row(page, overTitle).getByText(/已逾期 \d+ 天/)).toBeVisible();
    await shot(page, 'due-1-issue-countdown.png');

    // 服务端回显二次确认(读取时派生, 不落库): 剩余天数落在窗口内且临期/逾期布尔一致.
    let gov = await api(page, 'GET', base(id) + '/governance');
    const issueBy = title => gov.issues.find(r => r.title === title);
    const soon = issueBy(soonTitle), far = issueBy(farTitle), over = issueBy(overTitle);
    expect(soon.issue_due_in_days).toBeGreaterThanOrEqual(1);
    expect(soon.issue_due_in_days).toBeLessThanOrEqual(3);
    expect(soon.issue_due_soon).toBe(true);
    expect(soon.issue_overdue).toBe(false);
    expect(far.issue_due_in_days).toBeGreaterThanOrEqual(28);
    expect(far.issue_due_soon).toBe(false);
    expect(far.issue_overdue).toBe(false);
    expect(over.issue_due_in_days).toBeLessThanOrEqual(-4);
    expect(over.issue_due_soon).toBe(false);
    expect(over.issue_overdue).toBe(true);

    // 截图2: "会议行动"页签同一临期倒计时.
    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, actionTitle).getByText(/临期/)).toBeVisible();
    await shot(page, 'due-2-action-countdown.png');

    // 界面把该行动"转为WBS任务" -> converted -> 倒计时列不再显示剩余/临期(显示短横).
    await row(page, actionTitle).getByRole('button', { name: '转为WBS任务', exact: true }).click();
    const convForm = modal(page, '会议行动转WBS任务');
    await convForm.locator('#start_date').fill(dayOffset(0));
    await save(page, '会议行动转WBS任务');

    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, actionTitle).getByText(/临期/)).toHaveCount(0);
    await expect(row(page, actionTitle).getByText(/已逾期 \d+ 天/)).toHaveCount(0);
    await shot(page, 'due-3-after-convert.png');

    // 服务端回显: 转真实任务后行动倒计时为 null, 不再临期/逾期.
    gov = await api(page, 'GET', base(id) + '/governance');
    const acted = gov.actions.find(r => r.title === actionTitle);
    expect(acted.status).toBe('converted');
    expect(acted.action_due_in_days).toBeNull();
    expect(acted.action_due_soon).toBe(false);
    expect(acted.action_overdue).toBe(false);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
