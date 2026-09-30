const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C07 延伸: 会议纪要发布覆盖度只读派生洞察 (免迁移, 无新命令/新kind/新路由/不构成门控).
// 界面登记四条会议覆盖四种最新有效版本发布态: 草稿(recorded)/发布审批中(in_review)/已发布(approved)/已作废(discarded),
// "会议行动"页签新增只读"会议纪要发布覆盖度"面板: 会议总数/已发布(发布率, 分母排除已作废)/发布审批中/草稿/已作废;
// 独立审批人在第二真实上下文批准在办纪要后 -> 已发布率上升, 再受控作废草稿 -> 分母收缩发布率升到100%.
// 只读派生不改变任何会议发布状态, 面板仅聚合展示.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c07mrc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
// shared/panel 渲染 <section>, 按面板标题(heading)定位到具体面板, 避免跨面板文本串台.
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
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
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
  await page.waitForLoadState('networkidle');
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

// 覆盖度面板在抽屉自身滚动容器内靠下位置, window.scrollTo 无效 -> 先把面板滚动进视图再对面板元素截图, 确保翻转内容被真实捕获.
async function shotPanel(page, panelLoc, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await panelLoc.scrollIntoViewIfNeeded();
  await panelLoc.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 界面"登记项目会议": 标题/会议类型(常规会议)/会议日期/参会人/会议纪要, 登记后初始为草稿(recorded)态.
async function registerMeeting(page, { title, minutes }) {
  await drawer(page).getByRole('button', { name: '登记项目会议', exact: true }).click();
  const form = modal(page, '登记项目会议');
  await form.locator('#title').fill(title);
  await choose(page, form, 'meeting_type', '常规会议');
  await form.locator('#held_on').fill('2026-09-15');
  await choose(page, form, 'attendee_ids', 'admin');
  await form.locator('#minutes').fill(minutes);
  return save(page, '登记项目会议');
}

// admin 对指定纪要点击"提交发布", 在对话框选择独立审批人并保存 -> in_review.
async function submitRelease(page, title, reviewerName) {
  await row(page, title).getByRole('button', { name: '提交发布', exact: true }).click();
  const form = modal(page, '提交会议纪要发布审批');
  await choose(page, form, 'reviewer_id', reviewerName);
  await save(page, '提交会议纪要发布审批');
}

// 独立审批人在第二上下文批准发布 -> approved.
async function approveRelease(page, title, reason) {
  await row(page, title).getByRole('button', { name: '批准发布', exact: true }).click();
  const form = modal(page, '正式批准会议纪要');
  await form.locator('#reason').fill(reason);
  await save(page, '正式批准会议纪要');
}

// admin 对草稿纪要点击"作废", 填写原因 -> discarded.
async function discardMeeting(page, title, reason) {
  await row(page, title).getByRole('button', { name: '作废', exact: true }).click();
  const form = modal(page, '作废会议纪要');
  await form.locator('#reason').fill(reason);
  await save(page, '作废会议纪要');
}

async function coverageRow(page, id, title) {
  const gov = await api(page, 'GET', base(id) + '/governance');
  return gov.meetings.find(m => m.title === title);
}

test.describe('C07 延伸 会议纪要发布覆盖度只读洞察浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('发布覆盖度面板聚合四态/发布率, 独立审批批准与受控作废后发布率翻转且会议状态不漂移', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    // 合成只读且具备质量审批权限的独立审批人 (不改任何内置账号); admin 是登记/提交人须避开自审批门控.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    const suffix = serial();
    await api(page, 'POST', '/api/system/role', { role_name: `纪要发布覆盖审批${suffix}`, role_key: `mrc_ap_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `mrc_ap_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const approverName = `mrcs_${suffix}`;
    const approverPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '纪要发布覆盖独立审批人', password: approverPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C07 发布覆盖度 E2E 合成独立审批人' });
    const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CMRC-${suffix}`, name: `会议纪要发布覆盖度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });

    // 界面登记四条会议: 草稿(draft) 保持 recorded; 在办(pending) 提交发布 in_review; 已发布(released) 提交+批准 approved; 作废(voided) 直接 discard.
    const draft = `周例会纪要草稿-${suffix}`;
    const pending = `设计评审纪要在办-${suffix}`;
    const released = `需求基线纪要发布-${suffix}`;
    const voided = `重复登记纪要作废-${suffix}`;
    await open(page, id, '需求与治理', '会议行动');
    await registerMeeting(page, { title: draft, minutes: '周例会结论: 推进遗留行动项.' });
    await registerMeeting(page, { title: pending, minutes: '设计评审结论: 方案通过待归档.' });
    await registerMeeting(page, { title: released, minutes: '需求基线评审结论: 冻结范围.' });
    await registerMeeting(page, { title: voided, minutes: '误登记的重复会议内容.' });

    // 推进到四种发布态: released 提交+批准, pending 提交(在办), voided 作废, draft 留草稿.
    // 每次写命令前重新打开页签刷新项目版本(乐观锁), 避免陈旧的 project_version 触发 409.
    await open(page, id, '需求与治理', '会议行动');
    await submitRelease(page, released, approverName);
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const approver = await context.newPage();
    approver.on('pageerror', error => errors.push(error.message));
    await login(approver, approverName, approverPwd);
    await open(approver, id, '需求与治理', '会议行动');
    await approveRelease(approver, released, '需求基线纪要完整, 批准发布归档.');
    await open(page, id, '需求与治理', '会议行动');
    await submitRelease(page, pending, approverName);
    await open(page, id, '需求与治理', '会议行动');
    await discardMeeting(page, voided, '该会议为误登记的重复记录, 受控作废.');

    // 面板初态: 总数4, 已发布 33% (1/3, 分母排除已作废), 发布审批中1, 草稿1, 已作废1.
    await open(page, id, '需求与治理', '会议行动');
    const cov = panel(page, '会议纪要发布覆盖度');
    await expect(cov).toBeVisible();
    await expect(cov.getByText(/会议总数\s*4/)).toBeVisible();
    await expect(cov.getByText(/已发布\s*33%\s*\(1\/3\)/)).toBeVisible();
    await expect(cov.getByText(/发布审批中\s*1/)).toBeVisible();
    await expect(cov.getByText(/草稿\s*1/)).toBeVisible();
    await expect(cov.getByText(/已作废\s*1/)).toBeVisible();
    await shotPanel(page, cov, 'c07mrc-1-four-states.png');

    // 真实HTTP读模型回显 meeting_release_coverage 初始聚合.
    let ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.meeting_release_coverage.total, '分母=最新有效版本会议数').toBe(4);
    expect(ws.meeting_release_coverage.approved, '已发布数').toBe(1);
    expect(ws.meeting_release_coverage['in-review'], '审批中数').toBe(1);
    expect(ws.meeting_release_coverage.recorded, '草稿数').toBe(1);
    expect(ws.meeting_release_coverage.discarded, '已作废数').toBe(1);
    expect(ws.meeting_release_coverage['release-pct'], '发布率排除已作废分母').toBe(33);

    // 独立审批人在第二上下文批准在办纪要 -> 已发布升到 2/3 = 67%, 发布审批中消失.
    await open(approver, id, '需求与治理', '会议行动');
    await approveRelease(approver, pending, '设计评审纪要完整, 批准发布.');
    await context.close();

    await open(page, id, '需求与治理', '会议行动');
    await expect(cov.getByText(/已发布\s*67%\s*\(2\/3\)/)).toBeVisible();
    await expect(cov.getByText(/发布审批中\s*\d/)).toHaveCount(0);
    await expect(cov.getByText(/草稿\s*1/)).toBeVisible();
    await expect(cov.getByText(/已作废\s*1/)).toBeVisible();
    await shotPanel(page, cov, 'c07mrc-2-second-published.png');

    // 受控作废草稿 -> 分母收缩为2, 已发布率升到 2/2 = 100%, 草稿消失, 已作废计2.
    await open(page, id, '需求与治理', '会议行动');
    await discardMeeting(page, draft, '该周例会纪要不再需要发布, 受控作废.');
    await open(page, id, '需求与治理', '会议行动');
    await expect(cov.getByText(/已发布\s*100%\s*\(2\/2\)/)).toBeVisible();
    await expect(cov.getByText(/草稿\s*\d/)).toHaveCount(0);
    await expect(cov.getByText(/已作废\s*2/)).toBeVisible();
    await expect(cov.getByText(/会议总数\s*4/)).toBeVisible();
    await shotPanel(page, cov, 'c07mrc-3-full-release.png');

    // 真实HTTP读模型终态 + 只读派生不改变会议状态: 已发布仍 approved, 已作废仍 discarded.
    ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.meeting_release_coverage.total, '总数不变').toBe(4);
    expect(ws.meeting_release_coverage.approved, '已发布2').toBe(2);
    expect(ws.meeting_release_coverage.discarded, '已作废2').toBe(2);
    expect(ws.meeting_release_coverage.recorded, '草稿归零').toBe(0);
    expect(ws.meeting_release_coverage['in-review'], '审批中归零').toBe(0);
    expect(ws.meeting_release_coverage['release-pct'], '发布率100%').toBe(100);
    expect((await coverageRow(page, id, released)).status, '已发布会议仍 approved').toBe('approved');
    expect((await coverageRow(page, id, voided)).status, '作废会议仍 discarded').toBe('discarded');
    expect((await coverageRow(page, id, draft)).status, '只读派生不改草稿状态, 作废后为 discarded').toBe('discarded');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
