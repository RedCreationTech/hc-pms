const { test, expect } = require('@playwright/test');
const path = require('node:path');

// B07 工勘闭环只读汇总 (只读派生): 工程交付 -> 工勘与现场 页签内"工勘闭环汇总"面板,
// 按工勘生命周期 (待提交/确认中/已确认/已驳回) 聚合项目全部工勘的确认进度/逾期与最近计划日期
// (免迁移/无门控/无新命令/无新路由). 数据来自 delivery workspace 顶层 :survey_closure;
// 逐条工勘的状态不因该汇总改变 (真正的独立确认门控仍由服务端 submit-survey!/decide-survey! 在写入时强制:
// 仅 draft 可提交, 仅 in_review 可决定, 审核人须为提交时指定的独立审核人).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/b07sc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const base = id => `/api/pms/projects/${id}`;
const localDate = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const daysAgo = n => { const d = new Date(); d.setDate(d.getDate() - n); return localDate(d); };
const daysAhead = n => daysAgo(-n);

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

async function mutate(page, id, suffix, data = {}, expected = 200) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version }, expected);
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
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 面板在抽屉滚动容器内, 通用 shot scrollTo(0,0) 可能截不到 -> 定位后单独截元素图.
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '工勘闭环汇总', exact: true }) }).first();

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '工勘闭环汇总', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `工勘闭环审核${suffix}`, role_key: `pms_b07_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_b07_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立工勘审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `B07-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, suffix };
}

const statusCount = (sc, key) => sc['by-status'].find(x => x.key === key).count;

test.describe('B07 工勘闭环只读汇总', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 草稿逾期 -> 已确认/确认中逾期/待提交未来/已驳回 四态, 面板与 :survey_closure 一致且只读不门控', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f = await fixture(page, browser, '工勘闭环汇总');
    const id = f.id;
    const command = (suffix, data, actor = page) => mutate(actor, id, suffix, data).then(r => r.result);
    const ev = (await command('/governance/documents', { code: 'B07-EV', title: '工勘证据', filename: 'b07.txt', content: '合成工勘交付物记录.' })).id;

    // 1. 尚无工勘 -> 面板空态; :survey_closure.available=false, total/各态计数 0, closure-pct=0, nearest-planned=null.
    await open(page, id, '工程交付', '工勘与现场');
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无工勘任务, 按项目适用性登记各次工勘')).toBeVisible();
    await shot(page, 'b07sc-1-empty.png');
    await shotPanel(page, 'b07sc-1-empty-panel.png');
    let sc = (await api(page, 'GET', base(id) + '/delivery')).survey_closure;
    expect(sc.available).toBe(false);
    expect(sc.total).toBe(0);
    expect(sc.draft).toBe(0);
    expect(sc['in-review']).toBe(0);
    expect(sc.approved).toBe(0);
    expect(sc.rejected).toBe(0);
    expect(sc.open).toBe(0);
    expect(sc['overdue-open']).toBe(0);
    expect(sc['closure-pct']).toBe(0);
    expect(sc['nearest-planned']).toBeNull();
    expect(sc['by-status'].map(x => x.key)).toEqual(['draft', 'in_review', 'approved', 'rejected']);
    expect(sc['by-status'].every(x => x.count === 0)).toBe(true);

    // 2. 登记四次工勘, 全为 draft: SV-1 计划已过(20天前), SV-2 计划已过(5天前), SV-3 计划未来(10天后), SV-4 计划已过(8天前).
    const sv1 = await command('/delivery/surveys', { code: 'SV-1', title: '首次工勘', visit_no: 1, owner_id: f.adminId, planned_date: daysAgo(20), deliverable: '地基勘察' });
    const sv2 = await command('/delivery/surveys', { code: 'SV-2', title: '复勘', visit_no: 2, owner_id: f.adminId, planned_date: daysAgo(5), deliverable: '二次勘察' });
    const sv3 = await command('/delivery/surveys', { code: 'SV-3', title: '终勘', visit_no: 3, owner_id: f.adminId, planned_date: daysAhead(10), deliverable: '终勘' });
    const sv4 = await command('/delivery/surveys', { code: 'SV-4', title: '专项勘', visit_no: 4, owner_id: f.adminId, planned_date: daysAgo(8), deliverable: '专项勘察' });
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('工勘 4', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('待提交 4', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('逾期未确认 3', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已确认 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('确认闭环率 0%', { exact: true })).toBeVisible();
    await shot(page, 'b07sc-2-all-draft.png');
    await shotPanel(page, 'b07sc-2-all-draft-panel.png');
    sc = (await api(page, 'GET', base(id) + '/delivery')).survey_closure;
    expect(sc).toMatchObject({ available: true, total: 4, draft: 4, 'in-review': 0, approved: 0, rejected: 0, open: 4, 'overdue-open': 3, 'closure-pct': 0 });
    expect(sc['nearest-planned']).toBe(daysAgo(20));
    expect(statusCount(sc, 'draft')).toBe(4);
    // 逐条工勘状态不因汇总改变 (仍 draft, SV-1 计划已过 -> survey_overdue 只读标记).
    const rows = (await api(page, 'GET', base(id) + '/delivery')).surveys;
    expect(rows.find(r => r.id === sv1.id).status).toBe('draft');
    expect(rows.find(r => r.id === sv1.id).survey_overdue).toBe(true);
    expect(rows.find(r => r.id === sv3.id).survey_overdue).toBe(false);

    // 3. SV-1 独立确认通过 -> approved; SV-2 提交进入 in_review 且计划已过 -> 逾期未确认; SV-4 提交后驳回 -> rejected; SV-3 留 draft 计划未来.
    await command(`/delivery/surveys/${sv1.id}/submit`, { reviewer_id: f.userId, evidence_ids: [ev], actual_date: daysAgo(1) });
    await command(`/delivery/surveys/${sv1.id}/decision`, { decision: 'approved', reason: '独立确认交付物' }, f.reviewer);
    await command(`/delivery/surveys/${sv2.id}/submit`, { reviewer_id: f.userId, evidence_ids: [ev], actual_date: daysAgo(1) });
    await command(`/delivery/surveys/${sv4.id}/submit`, { reviewer_id: f.userId, evidence_ids: [ev], actual_date: daysAgo(1) });
    await command(`/delivery/surveys/${sv4.id}/decision`, { decision: 'rejected', reason: '交付物不完整' }, f.reviewer);
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('工勘 4', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('待提交 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('确认中 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已确认 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已驳回 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('逾期未确认 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('确认闭环率 25%', { exact: true })).toBeVisible();
    await shot(page, 'b07sc-3-mixed.png');
    await shotPanel(page, 'b07sc-3-mixed-panel.png');
    sc = (await api(page, 'GET', base(id) + '/delivery')).survey_closure;
    expect(sc).toMatchObject({ total: 4, draft: 1, 'in-review': 1, approved: 1, rejected: 1, open: 2, 'overdue-open': 1, 'closure-pct': 25 });
    expect(sc['nearest-planned']).toBe(daysAgo(5)); // 未决工勘(SV-2 已过, SV-3 未来)中最早计划 = SV-2
    expect(statusCount(sc, 'draft')).toBe(1);
    expect(statusCount(sc, 'in_review')).toBe(1);
    expect(statusCount(sc, 'approved')).toBe(1);
    expect(statusCount(sc, 'rejected')).toBe(1);

    // 只读汇总不改变逐条工勘状态 (SV-1 approved, SV-2 in_review+逾期, SV-4 rejected).
    const final = (await api(page, 'GET', base(id) + '/delivery')).surveys;
    expect(final.find(r => r.id === sv1.id).status).toBe('approved');
    expect(final.find(r => r.id === sv2.id).status).toBe('in_review');
    expect(final.find(r => r.id === sv2.id).survey_overdue).toBe(true);
    expect(final.find(r => r.id === sv4.id).status).toBe('rejected');

    // 门控仍在: 已 approved 的 SV-1 再次提交 -> 409 (状态守卫, 仅 draft 可提交), 只读汇总不放宽写入约束.
    await mutate(page, id, `/delivery/surveys/${sv1.id}/submit`, { reviewer_id: f.userId, evidence_ids: [ev], actual_date: daysAgo(1) }, 409);

    // 全部确认后闭环率可达 100%: 批准 SV-2, 确认并批准 SV-3 -> 无未决工勘, nearest-planned 归 null.
    await command(`/delivery/surveys/${sv2.id}/decision`, { decision: 'approved', reason: '补交后确认' }, f.reviewer);
    await command(`/delivery/surveys/${sv3.id}/submit`, { reviewer_id: f.userId, evidence_ids: [ev], actual_date: daysAgo(0) });
    await command(`/delivery/surveys/${sv3.id}/decision`, { decision: 'approved', reason: '独立确认终勘' }, f.reviewer);
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('已确认 3', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('待提交 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('逾期未确认 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('确认闭环率 75%', { exact: true })).toBeVisible();
    await shot(page, 'b07sc-4-mostly-approved.png');
    await shotPanel(page, 'b07sc-4-mostly-approved-panel.png');
    sc = (await api(page, 'GET', base(id) + '/delivery')).survey_closure;
    expect(sc).toMatchObject({ total: 4, draft: 0, 'in-review': 0, approved: 3, rejected: 1, open: 0, 'overdue-open': 0, 'closure-pct': 75 });
    expect(sc['nearest-planned']).toBeNull();

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
