const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C07g 会议受控作废与恢复 浏览器端到端验收 (免迁移, 复用 H18 lifecycle 泛型化到 meeting kind):
//   界面可见事实:
//   (A) 登记态(草稿)会议行内"级联影响"打开只读预览显示"可安全作废"+"未发现引用"; 点"作废"填原因 -> 状态徽标翻红"已作废",
//       "作废"入口消失, "恢复"入口出现; 服务端 workflow_history 记 prior_status=recorded; 点"恢复"回到"草稿".
//   (B) 会议仍有派生行动时"级联影响"显示"不可作废"并列出"行动 <标题>"; 界面点"作废"保存被服务端 409 拒绝, 弹窗错误面板显示
//       "记录仍被其它对象引用", 记录仍"草稿"(预览与拒绝均只读/不改状态).
//   (C) 已提交发布(in_review)的会议不在可作废集合: "级联影响"显示"状态不可作废"+"不可作废", 且行内不出现"作废"按钮.
// 自作废 403 / 重复作废 409 / 审批中或已发布作废 409 / 已作废会议派生行动 409 / 沟通计划 last_meeting_id 引用 409 等门控由后端 SQLite 用例覆盖.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c07g');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const GB = (page, re) => page.getByRole('button', { name: re });

async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await GB(page, /登\s*录/).click();
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
  return (await api(page, 'GET', base(id))).version;
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
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function rowShot(page, text, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await row(page, text).scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function meetingRow(page, id, mid) {
  const gov = await api(page, 'GET', base(id) + '/governance');
  return gov.meetings.find(m => m.id === mid);
}

async function createProject(page, label) {
  const suffix = serial();
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
  const adminId = options.currentUserId;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C07G-${suffix}`, name: `会议受控作废验收-${label} / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
  return { id: project.project_id, suffix, adminId };
}

// 打开某行的级联影响预览弹窗并等待其加载.
async function openPreview(page, rowText) {
  await row(page, rowText).getByRole('button', { name: /^级\s*联\s*影\s*响$/ }).click();
  const dlg = page.getByRole('dialog').filter({ hasText: '级联影响预览' });
  await expect(dlg).toBeVisible();
  await expect(dlg.getByText(/当前状态:/)).toBeVisible();
  return dlg;
}

// 作废某行(填原因, 期望成功 200).
async function discardRow(page, rowText, reason) {
  await row(page, rowText).getByRole('button', { name: /^作\s*废$/ }).click();
  const form = modal(page, '作废会议纪要');
  await expect(form).toBeVisible();
  await form.locator('#reason').fill(reason);
  const response = page.waitForResponse(r => r.url().includes('/discard') && r.request().method() === 'POST');
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `作废: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
}

// 恢复某行(填原因, 期望成功 200).
async function restoreRow(page, rowText, reason) {
  await row(page, rowText).getByRole('button', { name: /^恢\s*复$/ }).click();
  const form = modal(page, '恢复会议纪要');
  await expect(form).toBeVisible();
  await form.locator('#reason').fill(reason);
  const response = page.waitForResponse(r => r.url().includes('/restore') && r.request().method() === 'POST');
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `恢复: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
}

// 合成含 pms:quality:approve 的最小角色 + 第二审批人用户, 返回 {name, pwd, id}.
async function makeApprover(page, suffix) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `纪要作废审批${suffix}`, role_key: `mdis_ap_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `mdis_ap_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
  const name = `mdis_${suffix}`;
  const pwd = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: name, nick_name: '纪要作废独立审批人', password: pwd,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C07g作废E2E合成审批人' });
  const id = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id;
  return { name, pwd, id };
}

test.describe('C07g 会议受控作废与恢复浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('登记态会议: 级联影响可安全作废 -> 作废红标 -> 恢复到草稿, 服务端审计回写', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const { id, suffix, adminId } = await createProject(page, '作废恢复');

    const meeting = (await api(page, 'POST', base(id) + '/governance/meetings', { title: `遗留清理会议-${suffix}`, held_on: '2026-09-12',
      minutes: '一次误登记的临时会议.', attendee_ids: [adminId], version: await version(page, id) })).result;
    const mid = meeting.id;

    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, meeting.title).getByText('草稿', { exact: true })).toBeVisible();

    // 级联影响预览: 无引用 -> 可安全作废.
    const dlg = await openPreview(page, meeting.title);
    await expect(dlg.getByText('可安全作废')).toBeVisible();
    await expect(dlg.getByText('未发现引用该记录的其它对象.')).toBeVisible();
    await shot(page, 'c07g-1-preview-safe.png');
    await dlg.getByRole('button', { name: /Close|close|×/ }).click();
    await expect(dlg).toBeHidden();

    // 界面作废 -> 已作废红标, 作废入口消失, 恢复入口出现.
    await discardRow(page, meeting.title, '误登记的临时会议, 先作废');
    await expect(row(page, meeting.title).getByText('已作废', { exact: true })).toBeVisible();
    await expect(row(page, meeting.title).getByRole('button', { name: /^作\s*废$/ })).toHaveCount(0);
    await expect(row(page, meeting.title).getByRole('button', { name: /^恢\s*复$/ })).toBeVisible();
    await rowShot(page, meeting.title, 'c07g-2-discarded.png');

    const discarded = await meetingRow(page, id, mid);
    expect(discarded.status).toBe('discarded');
    expect(discarded.discard_reason).toBe('误登记的临时会议, 先作废');
    const audit = (discarded.workflow_history || []).filter(h => h.action === 'discarded').pop();
    expect(audit.prior_status).toBe('recorded');

    // 界面恢复 -> 回到草稿, 记录仍带审计痕迹.
    await restoreRow(page, meeting.title, '误作废, 恢复继续跟踪');
    await expect(row(page, meeting.title).getByText('草稿', { exact: true })).toBeVisible();
    await rowShot(page, meeting.title, 'c07g-3-restored.png');
    const restored = await meetingRow(page, id, mid);
    expect(restored.status).toBe('recorded');
    expect(restored.restore_reason).toBe('误作废, 恢复继续跟踪');
    expect((restored.workflow_history || []).map(h => h.action)).toEqual(expect.arrayContaining(['discarded', 'restored']));

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });

  test('仍有派生行动的会议: 级联影响不可作废并列出行動, 界面作废被服务端409拒绝', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const { id, suffix, adminId } = await createProject(page, '行动引用');

    const meeting = (await api(page, 'POST', base(id) + '/governance/meetings', { title: `行动承载会议-${suffix}`, held_on: '2026-09-12',
      minutes: '会议决定形成行动.', attendee_ids: [adminId], version: await version(page, id) })).result;
    await api(page, 'POST', base(id) + `/governance/meetings/${meeting.id}/actions`, { title: `补齐接线图-${suffix}`, owner_id: adminId,
      due_date: '2026-10-01', version: await version(page, id) });

    await open(page, id, '需求与治理', '会议行动');
    const dlg = await openPreview(page, meeting.title);
    await expect(dlg.getByText('不可作废')).toBeVisible();
    await expect(dlg.getByText('可安全作废')).toHaveCount(0);
    await expect(dlg.getByText(/仍被以下\s*1\s*个对象引用/)).toBeVisible();
    await expect(dlg.getByText(`行动 补齐接线图-${suffix}`)).toBeVisible();
    await shot(page, 'c07g-4-preview-blocked.png');
    await dlg.getByRole('button', { name: /Close|close|×/ }).click();

    // 界面点作废保存 -> 409 错误面板显示"记录仍被其它对象引用", 弹窗不关闭, 记录仍草稿.
    await row(page, meeting.title).getByRole('button', { name: /^作\s*废$/ }).click();
    const form = modal(page, '作废会议纪要');
    await form.locator('#reason').fill('仍有未闭环行动');
    const response = page.waitForResponse(r => r.url().includes('/discard') && r.request().method() === 'POST');
    await form.getByRole('button', { name: /^保\s*存$/ }).click();
    const body = await (await response).json();
    expect(body.code).toBe(409);
    await expect(form.locator('[role="alert"]')).toContainText('记录仍被其它对象引用');
    await shot(page, 'c07g-5-discard-blocked-alert.png');
    await form.getByRole('button', { name: /Cancel|取\s*消|Close|close|×/ }).click();

    expect((await meetingRow(page, id, meeting.id)).status).toBe('recorded');
    await expect(row(page, meeting.title).getByText('草稿', { exact: true })).toBeVisible();

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });

  test('已提交发布的会议: 状态不可作废且行内不出现作废入口', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const { id, suffix, adminId } = await createProject(page, '审批中不可作废');
    const approver = await makeApprover(page, suffix);
    await api(page, 'POST', base(id) + '/members', { user_id: approver.id, role: 'viewer' });

    const meeting = (await api(page, 'POST', base(id) + '/governance/meetings', { title: `待发布纪要-${suffix}`, held_on: '2026-09-12',
      minutes: '正式结论, 等待发布审批.', attendee_ids: [adminId, approver.id], version: await version(page, id) })).result;
    // 提交发布 (in_review).
    await api(page, 'POST', base(id) + `/governance/meetings/${meeting.id}/submit`, { reviewer_id: approver.id, version: await version(page, id) });

    await open(page, id, '需求与治理', '会议行动');
    await expect(row(page, meeting.title).getByText('发布审批中', { exact: true })).toBeVisible();
    await expect(row(page, meeting.title).getByRole('button', { name: /^作\s*废$/ })).toHaveCount(0);

    const dlg = await openPreview(page, meeting.title);
    await expect(dlg.getByText('状态不可作废', { exact: true })).toBeVisible();
    await expect(dlg.getByText('不可作废', { exact: true })).toBeVisible();
    await shot(page, 'c07g-6-inreview-nodiscard.png');
    await dlg.getByRole('button', { name: /Close|close|×/ }).click();

    // 真实 HTTP 也拒绝作废审批中的会议 (409 状态门控).
    await api(page, 'POST', base(id) + `/governance/meetings/${meeting.id}/discard`, { reason: '审批中不可作废', version: await version(page, id) }, 409);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
