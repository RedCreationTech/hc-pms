const { test, expect } = require('@playwright/test');
const path = require('node:path');

// 责任人到期压力热点: 跨风险/问题/会议行动按责任人只读聚合其未闭环事项的到期压力并加权排名.
// 后端 collaboration.clj owner-due-pressure 纯函数在 GET /governance 时按服务器"今天"派生, 以 owner_id 分组,
//   压力指数 = 已逾期x4 + 临期(<=7天)x2 + 未来到期(<=30天)x1 (无到期日/更远期不计压力), 压力降序点名最热责任人,
//   无责任人事项单独计入 unassigned-total 不混入热点; 只读派生, 不改记录, 不构成门控, 免迁移/免新命令.
// 本用例在单浏览器上下文(admin)内造出两位责任人以演示排名与最热, 并演示闭环剔除:
//   责任人甲(admin): 风险-逾期(-5 => 4) + 风险-临期(+3 => 2) + 问题-未来(+15 => 1) => 压力 7, 未闭环 3.
//   责任人乙(合成 editor 成员): 风险-逾期(-20 => 4) + 会议行动-临期(+3 => 2) => 压力 6, 未闭环 2.
//   全局: available true, assigned-total 5, unassigned-total 0, owner-count 2, overdue 2, due-soon 2, upcoming 1, owners-with-overdue 2.
//   最热 = 甲(压力7), 排名 [甲7, 乙6]. 面板在"需求与治理 -> 会议行动"页签真实可见, 与服务端 owner_due_pressure 同源.
// 闭环剔除(单上下文): 把乙的"临期"会议行动"转为WBS任务" -> converted -> 乙未闭环 2->1, 临期 1->0, 压力 6->4 (甲不受影响仍7), 排名 [甲7, 乙4].
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/owner-pressure');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
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

async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function saveForm(page, title) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body.data;
}

test.describe('责任人到期压力热点只读面板浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('双责任人压力排名/最热点名界面真实可见, 与服务端 owner_due_pressure 同源; 行动转任务后该责任人压力回落', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    // 合成第二责任人(乙)作为项目成员, 仅作为 owner_id 出现于压力聚合; 无需其登录, 属单上下文可见事实.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `压力热点乙角${suffix}`, role_key: `opb_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `opb_${suffix}`).role_id;
    const secondName = `opb_${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: secondName, nick_name: `责任人乙-${suffix}`, password: `E2e!${suffix}`,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'owner-due-pressure E2E 合成第二责任人' });
    const secondId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === secondName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `OP-${suffix}`, name: `责任人到期压力热点验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: secondId, role: 'editor' });

    const rA = `甲逾期风险-${suffix}`;   // admin, 逾期 -5 => 4
    const rB = `甲临期风险-${suffix}`;   // admin, 临期 +3 => 2
    const iC = `甲未来问题-${suffix}`;   // admin, 未来 +15 => 1
    const rD = `乙逾期风险-${suffix}`;   // second, 逾期 -20 => 4
    const aE = `乙临期行动-${suffix}`;   // second, 临期 +3 => 2 (经会议行动写入)

    // 全部低分(2x3=6 < 阈值16)不触发 H08 自动升级, 保持 open 计入未闭环.
    await mutateData(page, id, '/governance/risks', { title: rA, probability: 2, impact: 3, owner_id: adminId, mitigation: '追加缓冲并每周跟踪关键路径', due_date: dayOffset(-5) });
    await mutateData(page, id, '/governance/risks', { title: rB, probability: 2, impact: 3, owner_id: adminId, mitigation: '锁定备选供应商并加严来料检验', due_date: dayOffset(3) });
    await mutateData(page, id, '/governance/issues', { title: iC, severity: 'major', owner_id: adminId, due_date: dayOffset(15) });
    await mutateData(page, id, '/governance/risks', { title: rD, probability: 2, impact: 3, owner_id: secondId, mitigation: '提前介入现场准备与备件', due_date: dayOffset(-20) });

    const meeting = (await mutateData(page, id, '/governance/meetings',
      { title: `压力复盘会-${suffix}`, held_on: dayOffset(0), minutes: '责任人到期压力跟踪', attendee_ids: [adminId, secondId] })).result;
    await mutateData(page, id, `/governance/meetings/${meeting.id}/actions`, { title: aE, owner_id: secondId, due_date: dayOffset(3) });

    // 服务端只读派生回显 (GET /governance 的 data.owner_due_pressure).
    const gov = await api(page, 'GET', base(id) + '/governance');
    const op = gov.owner_due_pressure;
    expect(op.available).toBe(true);
    expect(op['assigned-total']).toBe(5);
    expect(op['unassigned-total']).toBe(0);
    expect(op['owner-count']).toBe(2);
    expect(op.overdue).toBe(2);
    expect(op['due-soon']).toBe(2);
    expect(op.upcoming).toBe(1);
    expect(op.further).toBe(0);
    expect(op.undated).toBe(0);
    expect(op['owners-with-overdue']).toBe(2);

    // 甲(admin) 压力 7 = 4+2+1; 乙(second) 压力 6 = 4+2; 压力降序 => 甲最热, 排名 [7, 6].
    expect(op.hottest['owner-id']).toBe(adminId);
    expect(op.hottest.pressure).toBe(7);
    expect(op.hottest.open).toBe(3);
    expect(typeof op.hottest.name).toBe('string');
    expect(op.hottest.name.length).toBeGreaterThan(0);
    expect(op['by-owner'].map(o => o.pressure)).toEqual([7, 6]);
    expect(op['by-owner'].map(o => o['owner-id'])).toEqual([adminId, secondId]);
    const adminRow = op['by-owner'].find(o => o['owner-id'] === adminId);
    expect(adminRow).toEqual({ 'owner-id': adminId, name: expect.any(String), open: 3, overdue: 1, 'due-soon': 1, upcoming: 1, further: 0, undated: 0, pressure: 7 });
    const secondRow = op['by-owner'].find(o => o['owner-id'] === secondId);
    expect(secondRow).toEqual({ 'owner-id': secondId, name: expect.any(String), open: 2, overdue: 1, 'due-soon': 1, upcoming: 0, further: 0, undated: 0, pressure: 6 });

    // 界面: "需求与治理 -> 会议行动" 页签内面板真实渲染, 全局汇总/最热责任人/压力排名徽标可见.
    await open(page, id, '需求与治理', '会议行动');
    const card = panel(page, '责任人到期压力热点');
    await expect(card.getByText('有责任人未闭环 5', { exact: true })).toBeVisible();
    await expect(card.getByText('涉及责任人 2', { exact: true })).toBeVisible();
    await expect(card.getByText('有逾期责任人 2', { exact: true })).toBeVisible();
    await expect(card.getByText('逾期事项 2', { exact: true })).toBeVisible();
    await expect(card.getByText('临期事项 2', { exact: true })).toBeVisible();
    await expect(card.getByText('未来事项 1', { exact: true })).toBeVisible();
    // "无责任人" 也出现在面板说明文案中, 故这里只断言"无责任人 <数字>"这条徽标未渲染(unassigned-total=0).
    await expect(card.getByText(/无责任人\s+\d+/)).toHaveCount(0);
    await expect(card.getByText('最热责任人:', { exact: true })).toBeVisible();
    await expect(card.getByText('压力排名:', { exact: true })).toBeVisible();
    // 甲(admin)为最热责任人, 同一条"压力 7 ..."文案在"最热责任人"与"压力排名"两处各出现一次 => 用 .first() 规避 strict mode.
    await expect(card.getByText(new RegExp(`压力 7 · 未闭环 3 · 逾期 1 · 临期 1 · 未来 1`)).first()).toBeVisible();
    await expect(card.getByText(new RegExp(`压力 6 · 未闭环 2 · 逾期 1 · 临期 1`)).first()).toBeVisible();
    await expect(card.getByText(new RegExp(`压力 7 · 未闭环 3 · 逾期 1 · 临期 1 · 未来 1`))).toHaveCount(2);
    await panelShot(page, '责任人到期压力热点', 'op-1-hotspots.png');

    // 闭环剔除(单上下文): 把乙的"临期"会议行动转为WBS任务 -> converted -> 乙压力 6->4, 未闭环 2->1; 甲不受影响.
    await row(page, aE).getByRole('button', { name: '转为WBS任务', exact: true }).click();
    const convForm = modal(page, '会议行动转WBS任务');
    await convForm.locator('#start_date').fill(dayOffset(0));
    await saveForm(page, '会议行动转WBS任务');

    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const op2 = gov2.owner_due_pressure;
    expect(op2['assigned-total']).toBe(4);
    expect(op2['due-soon']).toBe(1);
    const secondAfter = op2['by-owner'].find(o => o['owner-id'] === secondId);
    expect(secondAfter.open).toBe(1);
    expect(secondAfter['due-soon']).toBe(0);
    expect(secondAfter.pressure).toBe(4);
    expect(op2.hottest['owner-id']).toBe(adminId);
    expect(op2.hottest.pressure).toBe(7);
    expect(op2['by-owner'].map(o => o.pressure)).toEqual([7, 4]);

    await open(page, id, '需求与治理', '会议行动');
    const card2 = panel(page, '责任人到期压力热点');
    await expect(card2.getByText('有责任人未闭环 4', { exact: true })).toBeVisible();
    await expect(card2.getByText(new RegExp(`压力 4 · 未闭环 1 · 逾期 1`))).toBeVisible();
    await expect(card2.getByText(new RegExp(`压力 6 · 未闭环 2 · 逾期 1 · 临期 1`))).toHaveCount(0);
    await panelShot(page, '责任人到期压力热点', 'op-2-after-convert.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });

  test('空项目面板渲染空态提示 (无分配责任人的未闭环事项)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `OP-${suffix}e`, name: `责任人压力空态验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const gov = await api(page, 'GET', base(id) + '/governance');
    expect(gov.owner_due_pressure.available).toBe(false);
    expect(gov.owner_due_pressure['assigned-total']).toBe(0);
    expect(gov.owner_due_pressure['by-owner']).toEqual([]);
    expect(gov.owner_due_pressure.hottest).toBeNull();

    await open(page, id, '需求与治理', '会议行动');
    const card = panel(page, '责任人到期压力热点');
    await expect(card.getByText('暂无分配了责任人的未闭环到期事项', { exact: false })).toBeVisible();
    await panelShot(page, '责任人到期压力热点', 'op-3-empty.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
