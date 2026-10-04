const { test, expect } = require('@playwright/test');
const path = require('node:path');

// 项目级跨对象"未闭环整改总览"只读派生 (project_remediation_overview, 免迁移/无新 kind/无新命令/不构成门控):
//   把五类整改来源 (试验/关口/质量/风险/绩效) 的未闭环情况汇总到一处, 读模型按来源给出 total/open/closed/overdue/closure-pct,
//   另给全局合计与含整改来源数/仍有未闭环来源数; 试验来源问题堆叠修订先按 code 取最新版, 行动为单条活记录直接计.
// 浏览器路径: 真实HTTP建项目 -> 空态面板显占位提示 ->
//   真实HTTP建含 1 必需检查的关口模板 + 关口实例(草稿) + 含 1 必需项的 DQ + 一条 open 风险 ->
//   真实HTTP各落实一条整改 (关口整改到期日过去->逾期, DQ整改未来日, 风险预防行动未来日) ->
//   进入 需求与治理 / 会议行动 -> "未闭环整改总览"面板回显"整改来源 3 类"/"整改总数 3"/"已闭环 0% (0/3)"/"未完成 3"/"逾期未闭环 1",
//   并按来源标签列出 关口检查整改 / 质量检查整改 / 风险预防整改; 与真实HTTP读模型一致;
//   真实HTTP把 DQ 整改行动转为WBS任务(converted=完成) -> 重载面板"已闭环 33% (1/3)"/"未完成 2"/"仍有未闭环来源 2", 且 DQ 来源标签显"闭环 100%";
//   只读派生不回写来源对象: 关口仍 draft, DQ 仍 draft, 风险仍 open. 全程真实点击 + 真实HTTP + 真实浏览器截图 + 演示录像.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/pro');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
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

// 命令写操作: 拉取当前项目版本, 带版本发命令. 返回命令结果记录 (data.result).
async function command(page, id, suffix, data = {}, expected = 200) {
  const project = await api(page, 'GET', base(id));
  const result = await api(page, 'POST', base(id) + '/governance' + suffix, { ...data, version: project.version }, expected);
  return result && result.result;
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

async function shotCard(page, card, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 合成一名具备质量审批权限且加入项目的独立关口审核人 (关口审核人不得为登记人 admin).
async function reviewerUser(page, suffix, deptId) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `总览审核${suffix}`, role_key: `prorev_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `prorev_${suffix}`).role_id;
  const name = `pro_rev_${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: name, nick_name: '整改总览审核人', password: `E2e!${suffix}`,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: '整改总览 E2E 合成审核人' });
  return (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id;
}

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('项目级跨对象未闭环整改总览浏览器验收', () => {
  test.setTimeout(180000);

  test('五源整改汇总 -> 会议行动页签面板回显 -> 转任务闭环一源 -> 不回写来源对象', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `PRO-${suffix}`, name: `整改总览验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    const reviewerId = await reviewerUser(page, suffix, deptId);
    await api(page, 'POST', base(id) + '/members', { user_id: reviewerId, role: 'editor' });

    // 空态: 尚未落实任何整改, 面板显占位提示.
    await open(page, id, '需求与治理', '会议行动');
    const emptyPanel = panel(page, '未闭环整改总览');
    await expect(emptyPanel.getByText(/暂无整改项/)).toBeVisible();
    await shotCard(page, emptyPanel, 'pro-1-empty.png');

    // 真实HTTP建含 1 必需检查的关口模板 + 关口实例(草稿) + 含 1 必需项的 DQ + 一条 open 风险.
    const template = await command(page, id, '/gate-templates', {
      code: `PRO-${suffix}`, title: `总览模板-${suffix}`, stage: 'execution', required: true,
      checks: [{ code: 'R-1', title: '必需检查', required: true }] });
    const gate = await command(page, id, '/gates', { template_id: template.id, title: `总览关口-${suffix}`, reviewer_id: reviewerId });
    const dq = await command(page, id, '/dqs', {
      code: `PRO-DQ-${suffix}`, title: `总览DQ-${suffix}`, owner_id: adminId,
      checklist: [{ code: 'D-1', title: '必需项', required: true }], deliverable_ids: [] });
    const risk = await command(page, id, '/risks', {
      title: `总览风险-${suffix}`, probability: 2, impact: 2, owner_id: adminId, mitigation: '提前排期', due_date: '2026-12-01' });

    // 各来源落实一条整改: 关口到期日在过去(->逾期), DQ 与风险未来日.
    const ga = await command(page, id, `/gates/${gate.id}/remediation-action`, { due_date: '2020-01-01' });
    const da = await command(page, id, `/dqs/${dq.id}/remediation-action`, { check_code: 'D-1', due_date: '2099-01-01' });
    const ra = await command(page, id, `/risks/${risk.id}/mitigation-action`, { title: `落实预防措施-${suffix}`, due_date: '2099-01-01' });
    expect(ga.source_gate_id, '整改行动来源关口').toBe(gate.id);
    expect(da.source_dq_id, '整改行动来源DQ').toBe(dq.id);
    expect(ra.source_risk_id, '整改行动来源风险').toBe(risk.id);

    // 真实HTTP读模型: 三来源各 1 条, 全局 total 3 / open 3 / closed 0 / overdue>=1, by-source 三项.
    let ws = await api(page, 'GET', base(id) + '/governance');
    let ov = ws.project_remediation_overview;
    expect(ov.available, '有整改项即 available').toBe(true);
    expect(ov.total, '全局整改总数 3').toBe(3);
    expect(ov.open, '未完成 3').toBe(3);
    expect(ov.closed, '已闭环 0').toBe(0);
    expect(ov['sources-with-remediation'], '含整改来源 3 类').toBe(3);
    expect(ov['sources-with-open'], '仍有未闭环来源 3').toBe(3);
    expect(ov.overdue, '逾期未闭环 >= 1 (关口到期日在过去)').toBeGreaterThanOrEqual(1);
    expect(ov['closure-pct'], '闭环率 0').toBe(0);
    expect(ov['by-source'].map(s => s.key).sort(), '三来源键').toEqual(['dq', 'gate', 'risk']);

    // 界面: 会议行动页签 -> "未闭环整改总览"面板回显与读模型一致.
    await open(page, id, '需求与治理', '会议行动');
    const sum = panel(page, '未闭环整改总览');
    await expect(sum.getByText(/整改来源\s*3\s*类/)).toBeVisible();
    await expect(sum.getByText(/整改总数\s*3/)).toBeVisible();
    await expect(sum.getByText(/已闭环\s*0%\s*\(0\/3\)/)).toBeVisible();
    await expect(sum.getByText(/未完成\s*3/)).toBeVisible();
    await expect(sum.getByText(/逾期未闭环\s*1/)).toBeVisible();
    await expect(sum.getByText(/关口检查整改/)).toBeVisible();
    await expect(sum.getByText(/质量检查整改/)).toBeVisible();
    await expect(sum.getByText(/风险预防整改/)).toBeVisible();
    await shotCard(page, sum, 'pro-2-overview-3sources.png');

    // 真实HTTP把 DQ 整改行动转为WBS任务(converted=完成) -> 该来源闭环, 全局闭环率升到 33%.
    await command(page, id, `/actions/${da.id}/task`, { start_date: '2026-09-23', duration_days: 2 });
    ws = await api(page, 'GET', base(id) + '/governance');
    ov = ws.project_remediation_overview;
    expect(ov.closed, '闭环 1').toBe(1);
    expect(ov.open, '未完成 2').toBe(2);
    expect(ov['sources-with-open'], '仍有未闭环来源 2').toBe(2);
    expect(ov['closure-pct'], '闭环率 33').toBe(33);
    const dqGroup = ov['by-source'].find(s => s.key === 'dq');
    expect(dqGroup['closure-pct'], 'DQ 来源闭环率 100').toBe(100);
    expect(dqGroup.open, 'DQ 来源未完成 0').toBe(0);

    await open(page, id, '需求与治理', '会议行动');
    const sum2 = panel(page, '未闭环整改总览');
    await expect(sum2.getByText(/已闭环\s*33%\s*\(1\/3\)/)).toBeVisible();
    await expect(sum2.getByText(/未完成\s*2/)).toBeVisible();
    await expect(sum2.getByText(/仍有未闭环来源\s*2/)).toBeVisible();
    await expect(sum2.getByText(/质量检查整改 · 闭环 100%/)).toBeVisible();
    await shotCard(page, sum2, 'pro-3-after-close-one.png');

    // 只读派生不回写来源对象状态: 关口仍 draft, DQ 仍 draft, 风险仍 open; 三条整改行动仍在行动台账.
    const g1 = ws.gates.find(x => x.id === gate.id);
    const q1 = ws.dqs.find(x => x.id === dq.id);
    const r1 = ws.risks.find(x => x.id === risk.id);
    expect(g1.status, '关口状态未被总览改动').toBe('draft');
    expect(q1.status, 'DQ 状态未被总览改动').toBe('draft');
    expect(r1.status, '风险状态未被总览改动').toBe('open');
    expect([ga.id, da.id, ra.id].every(aid => ws.actions.some(x => x.id === aid)), '三条整改行动均在台账').toBe(true);

    await shot(page, 'pro-4-final-page.png');
    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
