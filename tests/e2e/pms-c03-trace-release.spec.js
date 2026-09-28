const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C03 需求追踪"证据发布"只读派生: admin 建项目 -> 真实HTTP建立需求 + 已登记证据文档 + verifies 追踪 ->
// 打开"URS与追踪"页签, "需求追踪矩阵"表新增"证据发布"列对文档目标显示金色"待发布"(registered/in_review 均未按发布计).
// admin 提交文档发布(独立审核人), 该审核人以自己的真实登录上下文作独立批准决定 -> approved 后重载,
// 同列翻绿"已发布". 全程真实点击 + 真实HTTP(双上下文) + 真实浏览器截图, 免迁移读模型仅界面可见, 不改状态机/不门控.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c03-trace-release');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const traceCard = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '需求追踪矩阵', exact: true }) });
const traceRow = (page, text) => traceCard(page).locator('tbody tr:visible').filter({ hasText: text }).first();
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

// 命令写操作: 拉取当前项目版本, 带版本发命令, 返回命令结果记录 (data.result).
async function command(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  const result = await api(page, 'POST', base(id) + '/governance' + suffix, { ...data, version: project.version });
  return result.result;
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

// 追踪矩阵面板位于抽屉滚动区下方, 用元素截图确保"证据发布"列进入画面.
async function shotTrace(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = traceCard(page);
  await card.scrollIntoViewIfNeeded();
  await expect(card.getByRole('columnheader', { name: '证据发布', exact: true })).toBeVisible();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 合成一名具备质量审批权限且加入项目的独立审核人 (发布审核人不得为提交人).
async function approverUser(page, suffix, deptId) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `发布审核${suffix}`, role_key: `relrev_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `relrev_${suffix}`).role_id;
  const name = `rel_rev_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: name, nick_name: '发布审核人', password,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C03 追踪证据发布 E2E 合成审核人' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id;
  return { name, password, userId };
}

test.describe('C03 需求追踪证据发布只读派生浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('追踪文档目标显示待发布 -> 独立批准发布后翻已发布', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `TRRL-${suffix}`, name: `追踪证据发布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    const approver = await approverUser(page, suffix, deptId);
    await api(page, 'POST', base(id) + '/members', { user_id: approver.userId, role: 'editor' });

    // 建立需求 + 已登记证据文档 + verifies 追踪 (需求 <- 文档).
    const reqCode = `URS-${suffix}`;
    const docTitle = `现场验证记录-${suffix}`;
    const req = await command(page, id, '/requirements', { code: reqCode, text: '必须可现场验证', category: '功能',
      priority: 'required', owner_id: adminId });
    const doc = await command(page, id, '/documents', { code: `DOC-${suffix}`, title: docTitle, filename: '验证.txt', content: ' 真实证据\n' });
    await command(page, id, '/traces', { requirement_id: req.id, target_kind: 'document', target_id: doc.id, relation: 'verifies' });

    // 打开URS与追踪页签: 追踪矩阵"证据发布"列对文档目标显示金色"待发布"(已登记未发布).
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(traceRow(page, reqCode).getByText('待发布')).toBeVisible();
    await expect(traceRow(page, reqCode).getByText('已发布')).toHaveCount(0);
    await shotTrace(page, 'c03-trace-release-1-pending.png');

    // 后端读模型回显 (真实HTTP): 提交前 evidence_release_state=pending, evidence_released=false.
    const before = await api(page, 'GET', base(id) + '/governance');
    const beforeTrace = before.traces.find(t => t.target_id === doc.id);
    expect(beforeTrace.evidence_release_state).toBe('pending');
    expect(beforeTrace.evidence_released).toBe(false);

    // admin 提交文档发布审核 (审核人为独立发布审核人); 提交后仍按未发布口径显示待发布.
    await command(page, id, `/documents/${doc.id}/submit`, { reviewer_id: approver.userId });
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(traceRow(page, reqCode).getByText('待发布')).toBeVisible();

    // 独立审核人用自己的真实登录上下文亲自批准发布 (双上下文, 满足发布审核人不得为提交人).
    const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
    const approverPage = await ctx.newPage();
    await login(approverPage, approver.name, approver.password);
    await command(approverPage, id, `/documents/${doc.id}/decision`, { decision: 'approved', reason: '独立签发' });
    await ctx.close();

    // 重载追踪矩阵: 文档版本已发布 -> "证据发布"列翻绿"已发布".
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(traceRow(page, reqCode).getByText('已发布')).toBeVisible();
    await expect(traceRow(page, reqCode).getByText('待发布')).toHaveCount(0);
    await shotTrace(page, 'c03-trace-release-2-released.png');

    // 后端读模型回显 (真实HTTP): approved 版本 evidence_release_state=released, evidence_released=true.
    const after = await api(page, 'GET', base(id) + '/governance');
    const afterTrace = after.traces.find(t => t.target_id === doc.id);
    expect(afterTrace.evidence_release_state).toBe('released');
    expect(afterTrace.evidence_released).toBe(true);
    expect(afterTrace.evidence_status).toBe('approved');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
