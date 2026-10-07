const { test, expect } = require('@playwright/test');
const path = require('node:path');

// B09 延伸: 关口签核流转待办只读派生洞察 (免迁移, 无新命令/新kind/新路由/不构成门控).
// 真实HTTP登记一份交付件文档 -> 建两个各含单个必需检查项 R 的关口模板 (closure 结项准出 / execution 执行准入) ->
//   从模板派生 5 个关口实例, 覆盖五个互斥流转桶:
//   gn 草稿未提交检查(结项准出) -> needs-checks;
//   gr 检查通过未提交(结项准出)   -> ready-to-submit (ready_to_sign 真);
//   ga 检查+提交停留评审(执行准入) -> awaiting-decision;
//   grw 检查+提交后被独立签核人驳回(执行准入) -> rework;
//   gsw 检查+提交后被独立签核人批准(执行准入) -> signed.
//   "Gate评审"页签只读"关口签核流转待办"面板按流转阶段唯一分桶: 关口总数 5 / 已签核 20% (1/5) /
//   待独立裁决 1 / 就绪待提交 1 / 待满足检查 1 / 需返工 1 / 可推进积压 3 (就绪+待裁决+返工) /
//   各阶段推进度 结项准出 已签 0/2 执行准入 已签 1/3 / 可立即提交评审点名 gr (检查 1/1);
//   真实HTTP GET /governance 回显 gate_velocity 各字段与面板一致; 只读派生不改变任何逐条关口状态.
//   新项目无关口 -> 面板空态"尚无关口实例"且 GET 回显 available=false/total=0/closure-pct=0.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/b09gv');
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

test.describe('B09 延伸 关口签核流转待办只读洞察浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('五态关口实例 -> 流转待办面板唯一分桶五桶+阶段推进+点名可提交, 只读不漂移', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    // 合成只读且具备质量审批权限的独立签核人 (不改任何内置账号); admin 是登记/提交人须避开自审门控.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    const suffix = serial();
    await api(page, 'POST', '/api/system/role', { role_name: `关口流转审批${suffix}`, role_key: `gv_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `gv_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const approverName = `gv_${suffix}`;
    const approverPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '关口流转独立签核人', password: approverPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'B09 关口签核流转待办 E2E 合成独立签核人' });
    const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `GV-${suffix}`, name: `关口签核流转待办验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });

    // 一份已登记交付件 (registered, 未发布), 作为已通过检查项绑定的证据 (检查项未声明 require_released, 提交/批准均可通过).
    const doc = await command(page, id, '/documents', { code: `GVDOC-${suffix}`, title: `关口证据交付件-${suffix}`, filename: 'gv.txt', content: '证据\n' });

    // 两个关口模板, 各含单个必需检查项 R, 分别落在结项准出(closure)与执行准入(execution)两个阶段.
    const tClosure = await command(page, id, '/gate-templates', { code: `GVC-${suffix}`, title: '结项准出关口', stage: 'closure', required: true,
      checks: [{ code: 'R', title: '必需检查', required: true }] });
    const tExec = await command(page, id, '/gate-templates', { code: `GVE-${suffix}`, title: '执行准入关口', stage: 'execution', required: true,
      checks: [{ code: 'R', title: '必需检查', required: true }] });
    const mkGate = (tpl, title) => command(page, id, '/gates', { template_id: tpl.id, title, reviewer_id: approverId });
    const passCheck = g => command(page, id, `/gates/${g.id}/checks`, { checks: [{ code: 'R', passed: true, evidence_ids: [doc.id] }] });

    const gn = await mkGate(tClosure, 'GV-需满足检查');                                   // 草稿: 不提交检查 -> needs-checks
    const gr = await mkGate(tClosure, 'GV-就绪待提交'); await passCheck(gr);              // 检查通过未提交 -> ready-to-submit
    const ga = await mkGate(tExec, 'GV-待独立裁决'); await passCheck(ga); await command(page, id, `/gates/${ga.id}/submit`, {}); // -> awaiting-decision
    const grw = await mkGate(tExec, 'GV-需返工'); await passCheck(grw); await command(page, id, `/gates/${grw.id}/submit`, {});   // 提交后驳回 -> rework
    const gsw = await mkGate(tExec, 'GV-已签核'); await passCheck(gsw); await command(page, id, `/gates/${gsw.id}/submit`, {});   // 提交后批准 -> signed

    // 独立签核人在第二真实上下文: 驳回 grw (返工), 批准 gsw (签核). admin 是提交人不能自审.
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const approver = await context.newPage();
    approver.on('pageerror', error => errors.push(error.message));
    await login(approver, approverName, approverPwd);
    await command(approver, id, `/gates/${grw.id}/decision`, { decision: 'rejected', reason: '条件不足, 返工补充.' });
    await command(approver, id, `/gates/${gsw.id}/decision`, { decision: 'approved', reason: '证据齐备, 验收签核通过.' });

    // 面板: 总数5 / 已签核 20% (1/5) / 待裁决1 / 就绪待提交1 / 待满足检查1 / 需返工1 / 可推进积压3.
    await open(page, id, '需求与治理', 'Gate评审');
    const sum = panel(page, '关口签核流转待办');
    await expect(sum).toBeVisible();
    await expect(sum.getByText(/关口总数\s*5/)).toBeVisible();
    await expect(sum.getByText(/已签核\s*20%\s*\(1\/5\)/)).toBeVisible();
    await expect(sum.getByText(/待独立裁决\s*1/)).toBeVisible();
    await expect(sum.getByText(/就绪待提交\s*1/)).toBeVisible();
    await expect(sum.getByText(/待满足检查\s*1/)).toBeVisible();
    await expect(sum.getByText(/需返工\s*1/)).toBeVisible();
    await expect(sum.getByText(/可推进积压\s*3/)).toBeVisible();
    // 各阶段推进度: 结项准出 已签 0/2, 执行准入 已签 1/3.
    await expect(sum.getByText(/结项准出\s*已签\s*0\/2/)).toBeVisible();
    await expect(sum.getByText(/执行准入\s*已签\s*1\/3/)).toBeVisible();
    // 点名可立即提交评审: 仅 gr (检查 1/1).
    await expect(sum.getByText(/GV-就绪待提交/)).toBeVisible();
    await expect(sum.getByText(/检查\s*1\/1/)).toBeVisible();
    await shotCard(page, sum, 'b09gv-1-velocity.png');

    // 真实HTTP读模型回显 gate_velocity 聚合与面板一致.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const v = ws.gate_velocity;
    expect(v.available, '有关口实例 -> available true').toBe(true);
    expect(v.total, '分母=最新有效版本关口实例数').toBe(5);
    expect(v.signed, '已签核 1 (gsw)').toBe(1);
    expect(v['awaiting-decision'], '待裁决 1 (ga)').toBe(1);
    expect(v['ready-to-submit'], '就绪待提交 1 (gr)').toBe(1);
    expect(v['needs-checks'], '待满足检查 1 (gn)').toBe(1);
    expect(v.rework, '需返工 1 (grw)').toBe(1);
    expect(v['actionable-backlog'], '可推进积压=就绪+待裁决+返工=3').toBe(3);
    expect(v['closure-pct'], '签核占比 20%').toBe(20);
    // 五桶互斥且并集恰为全部实例.
    expect(v.signed + v['awaiting-decision'] + v['ready-to-submit'] + v['needs-checks'] + v.rework, '五桶并集=总数').toBe(5);
    // 阶段分布.
    expect(v['by-stage'].closure.signed, '结项准出已签 0').toBe(0);
    expect(v['by-stage'].closure.total, '结项准出总数 2').toBe(2);
    expect(v['by-stage'].closure.pending, '结项准出待推进 2').toBe(2);
    expect(v['by-stage'].execution.signed, '执行准入已签 1').toBe(1);
    expect(v['by-stage'].execution.total, '执行准入总数 3').toBe(3);
    expect(v['by-stage'].execution.pending, '执行准入待推进 2').toBe(2);
    // 点名列表: 仅 gr.
    expect(v['ready-to-submit-list'].length, '可立即提交评审点名 1 条').toBe(1);
    const entry = v['ready-to-submit-list'][0];
    expect(entry['gate-id'], '点名指向 gr').toBe(gr.id);
    expect(entry.title).toBe('GV-就绪待提交');
    expect(entry.stage).toBe('closure');
    expect(entry['checks-passed'], '必需检查已通过 1').toBe(1);
    expect(entry['checks-total'], '必需检查总数 1').toBe(1);

    // 只读派生不改变任何逐条关口状态.
    const st = gid => ws.gates.find(g => g.id === gid).status;
    expect(st(gn.id), 'gn 仍草稿').toBe('draft');
    expect(st(gr.id), 'gr 仍待提交').toBe('ready');
    expect(st(ga.id), 'ga 仍评审中').toBe('in_review');
    expect(st(grw.id), 'grw 已驳回').toBe('rejected');
    expect(st(gsw.id), 'gsw 已批准').toBe('approved');
    expect(ws.gates.find(g => g.id === gsw.id).decided_by, '签核人为独立签核人').toBe(approverId);
    await context.close();

    // 空态: 新项目无关口 -> 面板"尚无关口实例"且 GET 回显 available=false/total=0/closure-pct=0.
    const empty = await api(page, 'POST', '/api/pms/projects', { project_no: `GVE-${suffix}`, name: `关口流转待办空态验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const eid = empty.project_id;
    await open(page, eid, '需求与治理', 'Gate评审');
    const sumEmpty = panel(page, '关口签核流转待办');
    await expect(sumEmpty.getByText('尚无关口实例, 发起 Gate 检查并绑定独立评审人后可在此查看签核流转待办概览.', { exact: true })).toBeVisible();
    await expect(sumEmpty.getByText(/关口总数/)).toHaveCount(0);
    await shotCard(page, sumEmpty, 'b09gv-2-empty.png');
    const wsE = await api(page, 'GET', base(eid) + '/governance');
    expect(wsE.gate_velocity.available, '无关口 -> available false').toBe(false);
    expect(wsE.gate_velocity.total, '空态总数 0').toBe(0);
    expect(wsE.gate_velocity['closure-pct'], '空态占比 0').toBe(0);
    expect(wsE.gate_velocity['ready-to-submit-list'], '空态点名空数组').toEqual([]);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
