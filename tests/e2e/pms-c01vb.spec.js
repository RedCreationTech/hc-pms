const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C01 延伸(再): 区分验证(verifies)关联所指向的证据文档是否已发布(approved)的只读派生 (免迁移, 无新命令/新kind/新状态).
// 界面登记一条声明验证方式(测试)的需求 -> 真实HTTP建证据文档并挂一条 verifies 追踪 ->
//   "URS与追踪"页签新增"验证证据"列: 关联指向尚未发布文档 -> 金色"证据待发布";
//   只读"验证方式与验证关联对齐"面板新增"已发布证据 0% / 证据已发布 0 / 证据待发布 1"标签.
// admin 提交文档发布(指定独立审核人), 该审核人以自己的真实登录上下文亲自批准发布 -> approved 后重载,
//   同一列翻绿"证据已发布", 面板升到"已发布证据 100% / 证据已发布 1"且"证据待发布"消失.
// 对齐口径(验证对齐列/已配验证关联)只反映有无 verifies 关联, 不因发布与否改变; 全程真实点击+真实HTTP(双上下文)+真实浏览器截图, 只读不门控不改状态机.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c01vb');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const reqCard = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: 'URS 需求版本', exact: true }) });
const reqRow = (page, code) => reqCard(page).locator('tbody tr:visible').filter({ hasText: code }).first();
const alignCard = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '验证方式与验证关联对齐', exact: true }) });
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

async function fillRequirement(page, { code, text, methodLabel }) {
  await drawer(page).getByRole('button', { name: '新增URS需求', exact: true }).click();
  const form = modal(page, '新增URS需求');
  await form.locator('#code').fill(code);
  await form.locator('#text').fill(text);
  await form.locator('#category').fill('功能');
  await choose(page, form, 'owner_id', 'admin');
  if (methodLabel) await choose(page, form, 'verification_method', methodLabel);
}

async function shotCard(page, card, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();
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
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C01 验证证据发布 E2E 合成审核人' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id;
  return { name, password, userId };
}

test.describe('C01 延伸 验证关联所指向证据是否已发布只读洞察浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('验证证据列与面板: 关联指向未发布证据 -> 独立批准发布后翻为证据已发布', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C1VB-${suffix}`, name: `验证证据发布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    const approver = await approverUser(page, suffix, deptId);
    await api(page, 'POST', base(id) + '/members', { user_id: approver.userId, role: 'editor' });

    await open(page, id, '需求与治理', 'URS与追踪');

    // 1) 界面登记一条声明验证方式"测试"的需求.
    const codeR = `URS-R-${suffix}`;
    await fillRequirement(page, { code: codeR, text: '控制器固件须支持远程升级并通过回滚校验.', methodLabel: '测试' });
    await save(page, '新增URS需求');

    // 2) 真实HTTP: 建证据文档并挂一条 verifies 追踪到该需求 (文档已登记未发布).
    const gov1 = await api(page, 'GET', base(id) + '/governance');
    const reqR = gov1.requirements.find(r => r.code === codeR);
    const doc = await command(page, id, '/documents', { code: `DOC-${suffix}`, title: `远程升级验证记录-${suffix}`, filename: '验证.txt', content: ' 已执行远程升级回滚测试\n' });
    await command(page, id, '/traces', { requirement_id: reqR.id, target_kind: 'document', target_id: doc.id, relation: 'verifies' });

    // 3) 重载: "验证证据"列对指向未发布证据的关联显"证据待发布"; 面板新增已发布证据 0% / 证据待发布 1.
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(reqCard(page).getByRole('columnheader', { name: '验证证据', exact: true })).toBeVisible();
    await expect(reqRow(page, codeR).getByText('证据待发布', { exact: true })).toBeVisible();
    // 对齐口径不受影响: 有 verifies 关联 -> 验证对齐列仍"已配验证关联".
    await expect(reqRow(page, codeR).getByText('已配验证关联', { exact: true })).toBeVisible();
    await shotCard(page, reqCard(page), 'c01vb-2-pending-column.png');
    await expect(alignCard(page).getByText(/已声明验证方式\s*1/)).toBeVisible();
    await expect(alignCard(page).getByText(/已配验证关联\s*100%/)).toBeVisible();
    await expect(alignCard(page).getByText(/已发布证据\s*0%/)).toBeVisible();
    await expect(alignCard(page).getByText(/证据待发布\s*1/)).toBeVisible();
    await expect(alignCard(page).getByText(/证据已发布\s*0/)).toBeVisible();
    await shotCard(page, alignCard(page), 'c01vb-1-pending-panel.png');

    // 4) 真实HTTP回显: 关联指向未发布文档 -> pending; 聚合 evidence-released 0 / pending 1 / pct 0.
    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const pending = gov2.requirements.find(r => r.code === codeR);
    expect(pending.verification_alignment, '对齐口径只看有无关联').toBe('aligned');
    expect(pending.verification_evidence_state, '证据未发布').toBe('pending');
    expect(gov2.verification_evidence_alignment['evidence-released'], '已发布数').toBe(0);
    expect(gov2.verification_evidence_alignment['evidence-pending'], '待发布数').toBe(1);
    expect(gov2.verification_evidence_alignment['evidence-released-pct'], '发布率').toBe(0);

    // 5) admin 提交文档发布(独立审核人), 审核人以自己真实上下文亲自批准发布 (双上下文).
    await command(page, id, `/documents/${doc.id}/submit`, { reviewer_id: approver.userId });
    const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
    const approverPage = await ctx.newPage();
    await login(approverPage, approver.name, approver.password);
    await command(approverPage, id, `/documents/${doc.id}/decision`, { decision: 'approved', reason: '独立签发' });
    await ctx.close();

    // 6) 重载: 证据文档已发布 -> "验证证据"列翻绿"证据已发布"; 面板升到已发布证据 100% / 证据已发布 1 且"证据待发布"消失.
    await open(page, id, '需求与治理', 'URS与追踪');
    await expect(reqRow(page, codeR).getByText('证据已发布', { exact: true })).toBeVisible();
    await expect(reqRow(page, codeR).getByText('证据待发布', { exact: true })).toHaveCount(0);
    await shotCard(page, reqCard(page), 'c01vb-3-released-column.png');
    await expect(alignCard(page).getByText(/已发布证据\s*100%/)).toBeVisible();
    await expect(alignCard(page).getByText(/证据已发布\s*1/)).toBeVisible();
    await expect(alignCard(page).getByText(/证据待发布\s*\d+/)).toHaveCount(0);
    await shotCard(page, alignCard(page), 'c01vb-4-released-panel.png');

    // 7) 真实HTTP回显: approved 版本 -> released; 聚合翻 1 / 0 / 100; 只读不改不可变字段.
    const gov3 = await api(page, 'GET', base(id) + '/governance');
    const released = gov3.requirements.find(r => r.code === codeR);
    expect(released.verification_evidence_state, '证据已发布').toBe('released');
    expect(released.verification_alignment, '对齐口径不漂移').toBe('aligned');
    expect(released.code, '编号不漂移').toBe(codeR);
    expect(released.verification_method, '验证方式仍为 test').toBe('test');
    expect(gov3.verification_evidence_alignment['evidence-released'], '已发布数1').toBe(1);
    expect(gov3.verification_evidence_alignment['evidence-pending'], '待发布清零').toBe(0);
    expect(gov3.verification_evidence_alignment['evidence-released-pct'], '发布率100').toBe(100);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
