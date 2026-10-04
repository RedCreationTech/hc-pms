const { test, expect } = require('@playwright/test');
const path = require('node:path');

// B11/B08 交付物料审批闭环只读汇总 (只读派生): 工程交付 -> 备料与BOM 页签内"物料审批闭环"面板,
// 汇总项目全部备料申请与BOM清单的独立审批链分布 (批准落地/审批中/已驳回/草稿 + 闭环率) 及申请/清单两源分解
// (免迁移/无门控/无新命令/无新路由). 数据来自 delivery workspace 顶层 :material_approval_summary;
// 逐条申请与清单的 lifecycle 状态与审批门控 (status!/decision-actor!/execution!) 均不因该汇总改变.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/b11mac');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
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

// 面板在抽屉滚动容器内偏下, 通用 shot scrollTo(0,0) 可能截不到 -> 定位后单独截元素图.
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '物料审批闭环', exact: true }) }).first();

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '物料审批闭环', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `物料审批闭环审核${suffix}`, role_key: `pms_b11_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_b11_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立物料审批人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `B11-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `B11-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `B11-${suffix}-M1`, name: '主机#1' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, sub, machine, suffix };
}

// 轻量前置: 建实际任务/证据/URS 需求供 references! 使用, 项目停留在 draft 即可 (物料写命令不要求 execution).
async function setup(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '备料任务', owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22', node_id: f.machine.node_id });
  const evidence = await command('/governance/documents', { code: 'B11-EV', title: '物料审批证据', filename: 'b11.txt', content: '合成备料/BOM独立审批记录.' });
  const requirement = await command('/governance/requirements', { code: 'B11-URS', text: '关键长周期件须独立审批', category: '交付', priority: 'required', owner_id: f.adminId });
  return { ...f, task, evidence, requirement, command };
}

const refs = f => ({ task_id: f.task.task_id, requirement_ids: [f.requirement.id] });
const item = () => [{ code: 'M-1', name: '执行器', quantity: 2, unit: '个' }];

// 提交并 (可选) 由独立审批人裁决一条申请; 不裁决则停留在 in_review.
async function requestTo(f, code, outcome) {
  const req = await f.command('/delivery/material-requests', { ...refs(f), code, title: `申请${code}`, request_type: 'long_lead', owner_id: f.adminId, needed_on: '2026-10-01', items: item() });
  if (outcome === 'draft') return req;
  await f.command(`/delivery/material-requests/${req.id}/submit`, { evidence_ids: [f.evidence.id], reviewer_id: f.userId });
  if (!outcome) return req;
  await f.command(`/delivery/material-requests/${req.id}/decision`, { decision: outcome, reason: '独立核查' }, f.reviewer);
  return req;
}

// 从已批准申请建BOM; 提交冻结并 (可选) 裁决; 不裁决则停留在 in_review.
async function bomTo(f, sourceId, code, outcome) {
  const bom = await f.command('/delivery/boms', { code, title: `清单${code}`, material_request_id: sourceId });
  if (outcome === 'draft') return bom;
  await f.command(`/delivery/boms/${bom.id}/freeze`, { evidence_ids: [f.evidence.id], reviewer_id: f.userId });
  if (!outcome) return bom;
  await f.command(`/delivery/boms/${bom.id}/decision`, { decision: outcome, reason: '独立冻结确认' }, f.reviewer);
  return bom;
}

const summary = async page => (await api(page, 'GET', base(page.__b11id) + '/delivery')).material_approval_summary;
const statusOf = (rows, code) => (rows.find(r => r.code === code) || {}).status;

test.describe('B11/B08 交付物料审批闭环只读汇总', () => {
  test.setTimeout(300000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('空态 -> 逐条独立审批流转 -> 面板与顶层 :material_approval_summary 一致, 记录状态无漂移', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const f0 = await fixture(page, browser, '物料审批闭环汇总');
    const f = await setup(page, f0);
    page.__b11id = f.id;
    const id = f.id;

    // 1. 尚无申请与清单 -> 面板空态; :material_approval_summary.available=false.
    await open(page, id, '工程交付', '备料与BOM');
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('暂无物料申请与BOM记录')).toBeVisible();
    await shot(page, 'b11mac-1-empty.png');
    await shotPanel(page, 'b11mac-1-empty-panel.png');
    let sm = await summary(page);
    expect(sm.available).toBe(false);
    expect(sm.total).toBe(0);
    expect(sm['closure-pct']).toBe(0);

    // 2. 建 4 申请: MR-A approved, MR-R in_review, MR-X rejected, MR-D draft.
    const mrA = await requestTo(f, 'MR-A', 'approved');
    await requestTo(f, 'MR-R', undefined);
    await requestTo(f, 'MR-X', 'rejected');
    await requestTo(f, 'MR-D', 'draft');
    // 建 4 BOM (均源自已批准 MR-A): BOM-F frozen, BOM-R in_review, BOM-X rejected, BOM-D draft.
    await bomTo(f, mrA.id, 'BOM-F', 'approved');
    await bomTo(f, mrA.id, 'BOM-R', undefined);
    await bomTo(f, mrA.id, 'BOM-X', 'rejected');
    await bomTo(f, mrA.id, 'BOM-D', 'draft');

    // 3. 面板真实呈现: 申请 total4 approved1 in-review1 rejected1 draft1 (25%), BOM 同分布 (25%), 合计 total8 landed2 (25%).
    await open(page, id, '工程交付', '备料与BOM');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('闭环率 25%', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已批准落地 2/8', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('审批中 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已驳回 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('草稿 2', { exact: true })).toBeVisible();
    // 两源分解表: 物料申请 / 物料清单 各 总数4 / 已批准落地1 / 审批中1 / 已驳回1 / 草稿1.
    await expect(panel(page).getByRole('cell', { name: '物料申请', exact: true })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '物料清单', exact: true })).toBeVisible();
    await expect(panel(page).getByRole('row', { name: /物料申请/ })).toContainText('25');
    await shot(page, 'b11mac-2-populated.png');
    await shotPanel(page, 'b11mac-2-populated-panel.png');

    sm = await summary(page);
    expect(sm).toMatchObject({ available: true, total: 8, approved: 2, 'in-review': 2, rejected: 2, draft: 2, 'closure-pct': 25, 'request-total': 4, 'bom-total': 4 });
    expect(sm['by-source'].map(x => x.key)).toEqual(['request', 'bom']);
    expect(sm['by-source'].map(x => x.approved)).toEqual([1, 1]);
    expect(sm['by-source'].map(x => x['closure-pct'])).toEqual([25, 25]);

    // 4. 只读汇总不改变逐条记录状态: 申请与清单各自 lifecycle 与流转目标一致.
    const dl = await api(page, 'GET', base(id) + '/delivery');
    expect(statusOf(dl.material_requests, 'MR-A')).toBe('approved');
    expect(statusOf(dl.material_requests, 'MR-R')).toBe('in_review');
    expect(statusOf(dl.material_requests, 'MR-X')).toBe('rejected');
    expect(statusOf(dl.material_requests, 'MR-D')).toBe('draft');
    expect(statusOf(dl.boms, 'BOM-F')).toBe('frozen');
    expect(statusOf(dl.boms, 'BOM-R')).toBe('in_review');
    expect(statusOf(dl.boms, 'BOM-X')).toBe('rejected');
    expect(statusOf(dl.boms, 'BOM-D')).toBe('draft');

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
