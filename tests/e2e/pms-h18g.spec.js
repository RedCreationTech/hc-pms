const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H18 延伸: Gate 验收快照只读标注其所引用证据文档"业务编码最新版本"是否已被受控作废(discarded) (免迁移, 无新命令/新kind/新路由/不门控).
// admin 建项目 + 合成独立审核人 -> 真实HTTP建立含 1 必需检查的模板与关口实例 -> 真实HTTP建证据文档 v1 并"填写检查"把该检查标记通过且绑定 v1 ->
//   重载 Gate 台账"检查就绪度"列显示绿色"检查 1/1""可签核"但无作废标注;
//   真实HTTP新增不可变修订 v2(同编号)后作废 v2 -> 因检查快照仍指向旧 v1(不受引用守卫拦截), 该编号最新版本现为 discarded ->
//   重载后"检查就绪度"列追加红色"证据已作废 1"标注, 而检查所指向的 v1 不漂移(evidence_ids 仍为 v1);
//   真实HTTP受控恢复 v2 -> 标注回落消失. 全程真实点击+真实HTTP+真实浏览器截图, 只读派生不改不可变版本.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h18g');
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

// 合成一名具备质量审批权限且加入项目的独立审核人 (关口审核人不得为登记人).
async function reviewerUser(page, suffix, deptId) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `关口审核${suffix}`, role_key: `gvrev_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `gvrev_${suffix}`).role_id;
  const name = `gv_rev_${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: name, nick_name: '关口审核人', password: `E2e!${suffix}`,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'Gate 证据作废 E2E 合成审核人' });
  return (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id;
}

test.describe('H18 Gate 证据作废只读标注浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('证据编码最新版本被作废 -> Gate"检查就绪度"列追加"证据已作废", 恢复后消失', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H18G-${suffix}`, name: `Gate证据作废标注验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    const reviewerId = await reviewerUser(page, suffix, deptId);
    await api(page, 'POST', base(id) + '/members', { user_id: reviewerId, role: 'editor' });

    // 1) 建立含 1 必需检查的模板并发起关口实例.
    const gateTitle = `关口证据作废评审-${suffix}`;
    const template = await command(page, id, '/gate-templates', {
      code: `GV-${suffix}`, title: `证据作废模板-${suffix}`, stage: 'execution', required: true,
      checks: [{ code: 'E-1', title: '验收测试记录已归档', required: true }] });
    const gate = await command(page, id, '/gates', { template_id: template.id, title: gateTitle, reviewer_id: reviewerId });

    // 2) 真实HTTP: 建证据文档 v1 并把检查 E-1 标记通过且绑定 v1.
    const codeD = `GDOC-${suffix}`;
    const docV1 = await command(page, id, '/documents', { code: codeD, title: `验收测试记录-${suffix}`, filename: '验收.txt', content: '已执行验收测试\n' });
    await command(page, id, `/gates/${gate.id}/checks`, { checks: [{ code: 'E-1', passed: true, waived: false, evidence_ids: [docV1.id] }] });

    // 3) 重载: 编码最新版本未作废 -> "检查就绪度"显示检查 1/1 与可签核, 无作废标注.
    await open(page, id, '需求与治理', 'Gate评审');
    await expect(gateCard(page).getByRole('columnheader', { name: '检查就绪度', exact: true })).toBeVisible();
    await expect(row(page, gateTitle).getByText('检查 1/1')).toBeVisible();
    await expect(row(page, gateTitle).getByText('可签核')).toBeVisible();
    await expect(row(page, gateTitle).getByText('证据已作废 1', { exact: true })).toHaveCount(0);
    await shotCard(page, gateCard(page), 'h18g-1-clean.png');

    // 4) 真实HTTP回显: 未作废 -> gate_evidence_voided false / gate_voided_checks 0.
    const gov1 = await api(page, 'GET', base(id) + '/governance');
    const g1 = gov1.gates.find(g => g.id === gate.id);
    expect(g1.gate_evidence_voided, '未作废时不标注').toBe(false);
    expect(g1.gate_voided_checks, '未作废时计数 0').toBe(0);

    // 5) 真实HTTP: 新增不可变修订 v2(同编号) 后作废 v2. 检查指向旧 v1, 不构成对 v2 的引用 -> 守卫放行.
    const docV2 = await command(page, id, `/documents/${docV1.id}/revisions`, { code: codeD, title: `验收测试记录-${suffix}`, filename: '验收v2.txt', content: '第二版正文\n' });
    expect(docV2.revision, '修订递增').toBe(2);
    await command(page, id, `/documents/${docV2.id}/discard`, { reason: '上传错误版本作废' });

    // 6) 重载: 该编号最新版本被作废 -> "检查就绪度"列追加红色"证据已作废 1".
    await open(page, id, '需求与治理', 'Gate评审');
    await expect(row(page, gateTitle).getByText('证据已作废 1', { exact: true })).toBeVisible();
    await expect(row(page, gateTitle).getByText('可签核')).toBeVisible();
    await shotCard(page, gateCard(page), 'h18g-2-voided.png');

    // 7) 真实HTTP回显: 检查快照仍指向 v1(evidence_ids 不漂移, v1 状态 registered) 但 gate_evidence_voided 翻 true.
    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const g2 = gov2.gates.find(g => g.id === gate.id);
    expect(g2.gate_evidence_voided, '编码最新版本作废 -> Gate 标注 true').toBe(true);
    expect(g2.gate_voided_checks, '作废命中 1 个检查').toBe(1);
    expect(g2.checks[0].evidence_ids, '检查快照所指向的 v1 id 不漂移').toEqual([docV1.id]);

    // 8) 真实HTTP: 受控恢复 v2 -> 最新版本回到非作废 -> 标注回落消失.
    await command(page, id, `/documents/${docV2.id}/restore`, { reason: '误作废恢复' });
    await open(page, id, '需求与治理', 'Gate评审');
    await expect(row(page, gateTitle).getByText('证据已作废 1', { exact: true })).toHaveCount(0);
    await shotCard(page, gateCard(page), 'h18g-3-restored.png');

    const gov3 = await api(page, 'GET', base(id) + '/governance');
    const g3 = gov3.gates.find(g => g.id === gate.id);
    expect(g3.gate_evidence_voided, '恢复后标注回落 false').toBe(false);
    expect(g3.gate_voided_checks, '恢复后计数 0').toBe(0);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
