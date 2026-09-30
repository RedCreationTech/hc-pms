const { test, expect } = require('@playwright/test');
const path = require('node:path');

// B09 延伸: 关口验收签核闭环汇总只读派生洞察 (免迁移, 无新命令/新kind/新路由/不构成门控).
// 真实HTTP建两份交付件文档 (一份仅登记 registered, 一份由独立签发人发布 approved) ->
//   建一个含单个必需检查项 R 的关口模板 -> 从该模板创建 4 个关口实例并推进到 4 态:
//   g1 草稿(未提交检查) / g2 待提交(检查通过绑未发布证据) / g3 签核评审中(提交, 绑未发布证据) / g4 签核评审中(提交, 绑已发布证据).
//   "Gate评审"页签只读"关口验收签核闭环汇总"面板按实例最新状态聚合: 关口总数 4 / 已签核 0% (批准 0 豁免 0) /
//   签核评审中 2 / 待提交 1 / 草稿 1 / 被必需检查阻断 1 / 证据待发布 2 (绑未发布证据的 g2,g3) / 证据已作废 0;
//   独立关口签核人在第二真实上下文把 g4 签核通过 (approved); 面板"已签核"升到 25% (1/4 · 批准 1), "签核评审中"降为 1,
//   "证据待发布"仍 2 (g2,g3 绑的 registered 文档仍未发布, g4 绑的是已发布文档不计) ->
//   真实HTTP GET /governance 回显 gate_closure 各字段与面板一致; 只读派生不改变任何逐条关口状态.
//   新项目无关口 -> 面板空态"尚无关口实例"且 GET 回显 available=false/total=0/closure-pct=0.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/b09gcs');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
// shared/panel 渲染 <section>, 按面板标题(heading)定位到具体面板, 避免与同页签其它 Gate 面板/台账文本串台.
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
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

test.describe('B09 延伸 关口验收签核闭环汇总只读洞察浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('四态关口实例 -> 汇总面板聚合签核闭环, 独立签核人通过后已签核 0%->25% 且只读不漂移', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    // 合成只读且具备质量审批权限的独立签核人 (不改任何内置账号); admin 是登记/提交人须避开自审门控.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    const suffix = serial();
    await api(page, 'POST', '/api/system/role', { role_name: `关口签核审批${suffix}`, role_key: `gcs_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `gcs_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const approverName = `gcs_${suffix}`;
    const approverPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '关口闭环独立签核人', password: approverPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'B09 关口闭环汇总 E2E 合成独立签核人' });
    const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `GCS-${suffix}`, name: `关口验收签核闭环汇总验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });

    // 两份交付件: doc-reg 仅登记 (registered, 未发布), doc-rel 由独立签核人发布 (approved).
    const docReg = await command(page, id, '/documents', { code: `GREG-${suffix}`, title: `未发布交付件-${suffix}`, filename: 'reg.txt', content: '仅登记\n' });
    const docRel = await command(page, id, '/documents', { code: `GREL-${suffix}`, title: `已发布交付件-${suffix}`, filename: 'rel.txt', content: '已发布\n' });
    await command(page, id, `/documents/${docRel.id}/submit`, { reviewer_id: approverId });

    // 独立签核人在第二真实上下文发布 doc-rel, 使 g4 绑定的证据为"已发布", 与 g2/g3 的未发布证据形成对照.
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const approver = await context.newPage();
    approver.on('pageerror', error => errors.push(error.message));
    await login(approver, approverName, approverPwd);
    await command(approver, id, `/documents/${docRel.id}/decision`, { decision: 'approved', reason: '交付件齐备, 独立签发.' });

    // 建一个含单个必需检查项 R 的关口模板, 从它派生 4 个关口实例.
    const template = await command(page, id, '/gate-templates', { code: `GT-${suffix}`, title: '闭环验收关口', stage: 'execution', required: true,
      checks: [{ code: 'R', title: '必需检查', required: true }] });
    const mkGate = title => command(page, id, '/gates', { template_id: template.id, title, reviewer_id: approverId });
    const g1 = await mkGate('G1-草稿态');
    const g2 = await mkGate('G2-待提交态');
    const g3 = await mkGate('G3-评审中态');
    const g4 = await mkGate('G4-签核中态');
    const passCheck = (g, doc) => command(page, id, `/gates/${g.id}/checks`, { checks: [{ code: 'R', passed: true, evidence_ids: [doc.id] }] });

    // g1 保持草稿 (未提交检查); g2 检查通过绑未发布证据 -> ready; g3 检查+提交 -> in_review; g4 检查绑已发布证据+提交 -> in_review.
    await passCheck(g2, docReg);
    await passCheck(g3, docReg);
    await command(page, id, `/gates/${g3.id}/submit`, {});
    await passCheck(g4, docRel);
    await command(page, id, `/gates/${g4.id}/submit`, {});

    // 面板初态: 总数4, 已签核 0%, 签核评审中2, 待提交1, 草稿1, 被必需检查阻断1(g1), 证据待发布2(g2,g3).
    await open(page, id, '需求与治理', 'Gate评审');
    const sum = panel(page, '关口验收签核闭环汇总');
    await expect(sum).toBeVisible();
    await expect(sum.getByText(/关口总数\s*4/)).toBeVisible();
    await expect(sum.getByText(/已签核\s*0%/)).toBeVisible();
    await expect(sum.getByText(/签核评审中\s*2/)).toBeVisible();
    await expect(sum.getByText(/待提交\s*1/)).toBeVisible();
    await expect(sum.getByText(/草稿\s*1/)).toBeVisible();
    await expect(sum.getByText(/被必需检查阻断\s*1/)).toBeVisible();
    await expect(sum.getByText(/证据待发布\s*2/)).toBeVisible();
    await expect(sum.getByText(/已驳回\s*[1-9]/)).toHaveCount(0);
    await expect(sum.getByText(/证据已作废\s*[1-9]/)).toHaveCount(0);
    await shotCard(page, sum, 'b09gcs-1-initial.png');

    // 真实HTTP读模型回显 gate_closure 初始聚合.
    let ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.gate_closure.available, '有关口实例 -> available true').toBe(true);
    expect(ws.gate_closure.total, '分母=最新有效版本关口实例数').toBe(4);
    expect(ws.gate_closure.approved, '初始无已批准').toBe(0);
    expect(ws.gate_closure.waived, '无豁免').toBe(0);
    expect(ws.gate_closure['in-review'], '评审中 2 (g3,g4)').toBe(2);
    expect(ws.gate_closure.ready, '待提交 1 (g2)').toBe(1);
    expect(ws.gate_closure.draft, '草稿 1 (g1)').toBe(1);
    expect(ws.gate_closure.rejected, '已驳回 0').toBe(0);
    expect(ws.gate_closure.signed, '已签核 0').toBe(0);
    expect(ws.gate_closure['closure-pct'], '签核闭环率 0%').toBe(0);
    expect(ws.gate_closure.blocked, '被必需检查阻断 1 (g1)').toBe(1);
    expect(ws.gate_closure['evidence-pending'], '证据待发布 2 (g2,g3)').toBe(2);
    expect(ws.gate_closure['evidence-voided'], '证据已作废 0').toBe(0);

    // 独立签核人在第二真实上下文对 g4 作出签核通过 -> approved+1, in_review-1, 闭环率升到 25%.
    await command(approver, id, `/gates/${g4.id}/decision`, { decision: 'approved', reason: '证据已发布, 验收通过.' });

    // 回到登记人视角重载: 已签核 1/4=25%, 评审中降为 1, 其余不变.
    await open(page, id, '需求与治理', 'Gate评审');
    await expect(sum.getByText(/已签核\s*25%\s*\(1\/4/)).toBeVisible();
    await expect(sum.getByText(/签核评审中\s*1/)).toBeVisible();
    await expect(sum.getByText(/关口总数\s*4/)).toBeVisible();
    await expect(sum.getByText(/证据待发布\s*2/)).toBeVisible();
    await shotCard(page, sum, 'b09gcs-2-approved.png');

    // 真实HTTP读模型终态 + 只读派生不改变逐条关口状态: g4 已批准, g1 仍草稿, g2 仍 ready, g3 仍评审中.
    ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.gate_closure.approved, '批准 1').toBe(1);
    expect(ws.gate_closure.signed, '已签核 1').toBe(1);
    expect(ws.gate_closure['in-review'], '评审中降为 1').toBe(1);
    expect(ws.gate_closure['closure-pct'], '闭环率 25%').toBe(25);
    const s1 = ws.gates.find(g => g.id === g1.id);
    const s2 = ws.gates.find(g => g.id === g2.id);
    const s3 = ws.gates.find(g => g.id === g3.id);
    const s4 = ws.gates.find(g => g.id === g4.id);
    expect(s1.status, '只读派生不改草稿态').toBe('draft');
    expect(s2.status, '只读派生不改待提交态').toBe('ready');
    expect(s3.status, '只读派生不改评审中态').toBe('in_review');
    expect(s4.status, '签核后为 approved').toBe('approved');
    expect(s4.decided_by, '签核人为独立签核人').toBe(approverId);
    await context.close();

    // 空态: 新项目无关口 -> 面板"尚无关口实例"且 GET 回显 available=false/total=0/closure-pct=0.
    const empty = await api(page, 'POST', '/api/pms/projects', { project_no: `GCSE-${suffix}`, name: `关口闭环汇总空态验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const eid = empty.project_id;
    await open(page, eid, '需求与治理', 'Gate评审');
    const sumEmpty = panel(page, '关口验收签核闭环汇总');
    await expect(sumEmpty.getByText('尚无关口实例, 发起 Gate 检查并逐项签核后可在此查看验收闭环概览.', { exact: true })).toBeVisible();
    await expect(sumEmpty.getByText(/关口总数/)).toHaveCount(0);
    await shotCard(page, sumEmpty, 'b09gcs-3-empty.png');
    const wsE = await api(page, 'GET', base(eid) + '/governance');
    expect(wsE.gate_closure.available, '无关口 -> available false').toBe(false);
    expect(wsE.gate_closure.total, '空态总数 0').toBe(0);
    expect(wsE.gate_closure['closure-pct'], '空态闭环率 0').toBe(0);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
