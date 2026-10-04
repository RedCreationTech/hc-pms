const { test, expect } = require('@playwright/test');
const path = require('node:path');

// B11/B08 交付物料审批闭环只读汇总 — 完整功能演示录像 (真实浏览器, 带旁白字幕叠加).
// 展示: 空态 -> 建备料申请并独立审批(approved/in_review/rejected/draft) -> 从已批准申请建BOM并冻结审批
//       (frozen/in_review/rejected/draft) -> "物料审批闭环"面板实时刷新: 闭环率/已批准落地/审批中/已驳回/草稿
//       与申请,清单两源分解. 只读派生不门控写操作, 真正门控仍由各写命令 status!/decision-actor!/execution! 强制.
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
  await page.waitForLoadState('load');
  await page.waitForTimeout(600);
}

async function open(page, id, section, subsection) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  if (section) await tab(page, section);
  if (subsection) await tab(page, subsection);
}

async function caption(page, text) {
  await page.evaluate(t => {
    let el = document.getElementById('b11-demo-caption');
    if (!el) {
      el = document.createElement('div');
      el.id = 'b11-demo-caption';
      el.style.cssText = 'position:fixed;left:50%;bottom:28px;transform:translateX(-50%);z-index:99999;'
        + 'max-width:82%;padding:14px 26px;border-radius:12px;background:rgba(17,24,39,0.92);color:#fff;'
        + 'font-size:20px;line-height:1.5;font-weight:600;box-shadow:0 8px 30px rgba(0,0,0,0.35);text-align:center;'
        + 'font-family:"PingFang SC","Microsoft YaHei",sans-serif;pointer-events:none;';
      document.body.appendChild(el);
    }
    el.textContent = t;
  }, text);
}

const pause = (page, ms = 700) => page.waitForTimeout(ms);
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '物料审批闭环', exact: true }) }).first();

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `物料审批闭环审核${suffix}`, role_key: `pms_b11d_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_b11d_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立物料审批人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `B11D-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const nodes = await api(page, 'GET', base(id) + '/nodes');
  const main = nodes.rows.find(n => n.node_type === 'main');
  const sub = await api(page, 'POST', base(id) + '/nodes', { parent_id: main.node_id, node_type: 'sub', node_code: `B11D-${suffix}-U1`, name: '主机单元' });
  const machine = await api(page, 'POST', base(id) + '/nodes', { parent_id: sub.node_id, node_type: 'machine', node_code: `B11D-${suffix}-M1`, name: '主机#1' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1366, height: 768 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, sub, machine, suffix };
}

async function setup(page, f) {
  const command = (suffix, data, actor = page) => mutate(actor, f.id, suffix, data).then(r => r.result);
  const task = await command('/tasks', { wbs_code: '1', name: '备料任务', owner_id: f.adminId, task_type: 'task', duration_days: 1, start_date: '2026-09-22', node_id: f.machine.node_id });
  const evidence = await command('/governance/documents', { code: 'B11D-EV', title: '物料审批证据', filename: 'b11d.txt', content: '合成备料/BOM独立审批记录.' });
  const requirement = await command('/governance/requirements', { code: 'B11D-URS', text: '关键长周期件须独立审批', category: '交付', priority: 'required', owner_id: f.adminId });
  return { ...f, task, evidence, requirement, command };
}

const refs = f => ({ task_id: f.task.task_id, requirement_ids: [f.requirement.id] });
const item = () => [{ code: 'M-1', name: '执行器', quantity: 2, unit: '个' }];

async function requestTo(f, code, outcome) {
  const req = await f.command('/delivery/material-requests', { ...refs(f), code, title: `申请${code}`, request_type: 'long_lead', owner_id: f.adminId, needed_on: '2026-10-01', items: item() });
  if (outcome === 'draft') return req;
  await f.command(`/delivery/material-requests/${req.id}/submit`, { evidence_ids: [f.evidence.id], reviewer_id: f.userId });
  if (!outcome) return req;
  await f.command(`/delivery/material-requests/${req.id}/decision`, { decision: outcome, reason: '独立核查' }, f.reviewer);
  return req;
}

async function bomTo(f, sourceId, code, outcome) {
  const bom = await f.command('/delivery/boms', { code, title: `清单${code}`, material_request_id: sourceId });
  if (outcome === 'draft') return bom;
  await f.command(`/delivery/boms/${bom.id}/freeze`, { evidence_ids: [f.evidence.id], reviewer_id: f.userId });
  if (!outcome) return bom;
  await f.command(`/delivery/boms/${bom.id}/decision`, { decision: outcome, reason: '独立冻结确认' }, f.reviewer);
  return bom;
}

test.use({
  viewport: { width: 1366, height: 768 },
  video: { mode: 'on', size: { width: 1280, height: 720 } },
});

test.describe('B11/B08 交付物料审批闭环只读汇总 — 完整功能演示', () => {
  test.setTimeout(900000);

  test('免门控只读汇总 · 逐条独立审批 -> 物料审批闭环面板 (单次加载)', async ({ page, browser }) => {
    await login(page);
    const f0 = await fixture(page, browser, '物料审批闭环演示');
    const f = await setup(page, f0);
    const id = f.id;

    await caption(page, 'B11/B08 交付物料审批闭环只读汇总 · 真实浏览器功能演示');
    await caption(page, '步骤 1/4 · 经真实 HTTP 建 4 条备料申请并逐条独立审批: MR-A 批准 / MR-R 审批中 / MR-X 驳回 / MR-D 草稿');
    const mrA = await requestTo(f, 'MR-A', 'approved');
    await requestTo(f, 'MR-R', undefined);
    await requestTo(f, 'MR-X', 'rejected');
    await requestTo(f, 'MR-D', 'draft');

    await caption(page, '步骤 2/4 · 从已批准申请建 4 条 BOM 并独立冻结审批: BOM-F 冻结 / BOM-R 审批中 / BOM-X 驳回 / BOM-D 草稿');
    await bomTo(f, mrA.id, 'BOM-F', 'approved');
    await bomTo(f, mrA.id, 'BOM-R', undefined);
    await bomTo(f, mrA.id, 'BOM-X', 'rejected');
    await bomTo(f, mrA.id, 'BOM-D', 'draft');

    await caption(page, '步骤 3/4 · 读模型核验 (真实 HTTP GET /delivery): 总数8 / 已批准落地2 / 审批中2 / 已驳回2 / 草稿2 / 闭环率25%, 逐条 lifecycle 不漂移');
    const dl = await api(page, 'GET', base(id) + '/delivery');
    const sm = dl.material_approval_summary;
    expect(sm).toMatchObject({ available: true, total: 8, approved: 2, 'in-review': 2, rejected: 2, draft: 2, 'closure-pct': 25, 'request-total': 4, 'bom-total': 4 });
    expect(dl.material_requests.find(r => r.code === 'MR-A').status).toBe('approved');
    expect(dl.boms.find(r => r.code === 'BOM-F').status).toBe('frozen');
    expect(dl.boms.find(r => r.code === 'BOM-R').status).toBe('in_review');

    await caption(page, '步骤 4/4 · 打开工程交付 › 备料与BOM 一次 -> 物料审批闭环面板呈现闭环率与两源分解');
    await open(page, id, '工程交付', '备料与BOM');
    await panel(page).scrollIntoViewIfNeeded();
    await pause(page, 1200);
    await expect(panel(page).getByText('闭环率 25%', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已批准落地 2/8', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('审批中 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已驳回 2', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('草稿 2', { exact: true })).toBeVisible();
    await pause(page);
    await expect(panel(page).getByRole('cell', { name: '物料申请', exact: true })).toBeVisible();
    await expect(panel(page).getByRole('cell', { name: '物料清单', exact: true })).toBeVisible();
    await pause(page, 1200);

    await caption(page, '演示结束 · B11/B08 交付物料审批闭环只读汇总 (免迁移 / 无门控 / 无新命令 / 无新路由)');
    await pause(page, 1000);

    const video = page.video();
    if (video) {
      const target = path.join(output, 'b11mac-demo.webm');
      await video.saveAs(target);
      console.log('VIDEO_SAVED=' + target);
    }
    await f.context.close();
  });
});
