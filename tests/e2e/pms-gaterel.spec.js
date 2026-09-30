const { test, expect } = require('@playwright/test');
const path = require('node:path');

// Gate 证据待发布只读派生 (免迁移, 无新命令/新kind/新路由/不门控): 关口检查快照标记通过的证据文档所绑业务编码版本是否已正式发布(approved).
// admin 建项目 + 合成独立审核人 -> 真实HTTP建立含 1 必需检查的模板与关口实例 -> 真实HTTP建证据文档 v1(registered) 并"填写检查"把该检查标记通过且绑定 v1 ->
//   重载 Gate 台账"检查就绪度"列显示绿色"检查 1/1""可签核"且追加金色"证据待发布 1"(证据尚未正式发布), 与红色"证据已作废"正交;
//   admin 提交文档发布(指定独立审核人), 审核人以自己真实登录上下文亲自批准发布 -> v1 变 approved;
//   重载后金色"证据待发布 1"消失(证据已发布), HTTP 回显 gate_evidence_unreleased false / gate_evidence_pending 0;
//   再新增不可变修订 v2(同编号)后作废 v2 -> 快照仍指向已发布的 v1(unreleased 恒 false 不漂移)而"证据已作废 1"翻红, 证明两口径正交. 全程真实点击+真实HTTP+真实浏览器截图.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/gaterel');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const gateCard = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: 'Gate检查与评审', exact: true }) });
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

async function shotCard(page, card, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 合成一名具备质量审批权限且加入项目的独立审核人 (关口审核人/发布审核人均不得为登记人).
async function approverUser(page, suffix, deptId) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `关口发布审核${suffix}`, role_key: `grrev_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `grrev_${suffix}`).role_id;
  const name = `gr_rev_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: name, nick_name: '关口发布审核人', password,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'Gate 证据待发布 E2E 合成审核人' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id;
  return { name, password, userId };
}

test.describe('Gate 证据待发布只读派生浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('通过证据未发布 -> 金色"证据待发布"; 独立批准后消失; 与"证据已作废"正交', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `GREL-${suffix}`, name: `Gate证据待发布验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    const approver = await approverUser(page, suffix, deptId);
    await api(page, 'POST', base(id) + '/members', { user_id: approver.userId, role: 'editor' });

    // 1) 建立含 1 必需检查的模板并发起关口实例.
    const gateTitle = `关口证据发布评审-${suffix}`;
    const template = await command(page, id, '/gate-templates', {
      code: `GR-${suffix}`, title: `证据发布模板-${suffix}`, stage: 'execution', required: true,
      checks: [{ code: 'E-1', title: '验收测试记录已归档', required: true }] });
    const gate = await command(page, id, '/gates', { template_id: template.id, title: gateTitle, reviewer_id: approver.userId });

    // 2) 真实HTTP: 建证据文档 v1(registered) 并把检查 E-1 标记通过且绑定 v1.
    const codeD = `GRELDOC-${suffix}`;
    const docV1 = await command(page, id, '/documents', { code: codeD, title: `验收测试记录-${suffix}`, filename: '验收.txt', content: '已执行验收测试\n' });
    await command(page, id, `/gates/${gate.id}/checks`, { checks: [{ code: 'E-1', passed: true, waived: false, evidence_ids: [docV1.id] }] });

    // 3) 重载: 检查通过 -> 绿色"检查 1/1""可签核", 但证据未发布 -> 追加金色"证据待发布 1", 无"证据已作废".
    await open(page, id, '需求与治理', 'Gate评审');
    await expect(gateCard(page).getByRole('columnheader', { name: '检查就绪度', exact: true })).toBeVisible();
    await expect(row(page, gateTitle).getByText('检查 1/1')).toBeVisible();
    await expect(row(page, gateTitle).getByText('可签核')).toBeVisible();
    await expect(row(page, gateTitle).getByText('证据待发布 1', { exact: true })).toBeVisible();
    await expect(row(page, gateTitle).getByText('证据已作废 1', { exact: true })).toHaveCount(0);
    await shotCard(page, gateCard(page), 'gaterel-1-pending.png');

    // 4) 真实HTTP回显: 证据未发布 -> gate_evidence_checks 1 / gate_evidence_pending 1 / gate_evidence_unreleased true, 且未作废.
    const gov1 = await api(page, 'GET', base(id) + '/governance');
    const g1 = gov1.gates.find(g => g.id === gate.id);
    expect(g1.gate_evidence_checks, '通过且绑证据的检查计 1').toBe(1);
    expect(g1.gate_evidence_pending, '证据未发布计 1').toBe(1);
    expect(g1.gate_evidence_unreleased, '存在待发布证据').toBe(true);
    expect(g1.gate_evidence_voided, '尚未作废').toBe(false);

    // 5) admin 提交文档发布(指定独立审核人), 审核人以自己真实上下文亲自批准发布 (双上下文).
    await command(page, id, `/documents/${docV1.id}/submit`, { reviewer_id: approver.userId });
    const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
    const approverPage = await ctx.newPage();
    await login(approverPage, approver.name, approver.password);
    await command(approverPage, id, `/documents/${docV1.id}/decision`, { decision: 'approved', reason: '独立签发' });
    await ctx.close();

    // 6) 重载: v1 已发布 -> 金色"证据待发布 1"消失, "检查 1/1""可签核"仍在.
    await open(page, id, '需求与治理', 'Gate评审');
    await expect(row(page, gateTitle).getByText('证据待发布 1', { exact: true })).toHaveCount(0);
    await expect(row(page, gateTitle).getByText('检查 1/1')).toBeVisible();
    await expect(row(page, gateTitle).getByText('可签核')).toBeVisible();
    await shotCard(page, gateCard(page), 'gaterel-2-released.png');

    // 7) 真实HTTP回显: 证据已发布 -> gate_evidence_unreleased false / gate_evidence_pending 0 / checks 仍 1.
    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const g2 = gov2.gates.find(g => g.id === gate.id);
    expect(g2.gate_evidence_checks, '检查计不变').toBe(1);
    expect(g2.gate_evidence_pending, '已发布无待发布').toBe(0);
    expect(g2.gate_evidence_unreleased, '证据已发布').toBe(false);

    // 8) 正交性: 新增不可变修订 v2(同编号)后作废 v2 -> 快照仍指向已发布的 v1(unreleased 恒 false), 而"证据已作废 1"翻红.
    const docV2 = await command(page, id, `/documents/${docV1.id}/revisions`, { code: codeD, title: `验收测试记录-${suffix}`, filename: '验收v2.txt', content: '第二版正文\n' });
    expect(docV2.revision, '修订递增').toBe(2);
    await command(page, id, `/documents/${docV2.id}/discard`, { reason: '上传错误版本作废' });

    await open(page, id, '需求与治理', 'Gate评审');
    await expect(row(page, gateTitle).getByText('证据已作废 1', { exact: true })).toBeVisible();
    await expect(row(page, gateTitle).getByText('证据待发布 1', { exact: true })).toHaveCount(0);
    await shotCard(page, gateCard(page), 'gaterel-3-orthogonal.png');

    const gov3 = await api(page, 'GET', base(id) + '/governance');
    const g3 = gov3.gates.find(g => g.id === gate.id);
    expect(g3.gate_evidence_voided, '编码最新版本作废 -> 作废标注 true').toBe(true);
    expect(g3.gate_evidence_unreleased, '快照指向已发布 v1 -> 待发布仍 false (正交不漂移)').toBe(false);
    expect(g3.checks[0].evidence_ids, '检查快照所指向的 v1 id 不漂移').toEqual([docV1.id]);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
