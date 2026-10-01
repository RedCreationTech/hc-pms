const { test, expect } = require('@playwright/test');

// B07 工勘闭环只读汇总 — 完整功能演示录像 (真实浏览器, 带旁白字幕叠加).
// 展示: 登记四次工勘全为草稿(SV-1/2/4 计划已过, SV-3 计划未来) -> 面板"工勘4/待提交4/逾期未确认3/闭环率0%"
//       -> SV-1 独立确认通过 -> SV-2 提交进入确认中(逾期) -> SV-4 提交后驳回 -> 面板四态各1/闭环率25%
//       -> 再批准 SV-2 + SV-3 -> 已确认3/待提交0/逾期未确认0/闭环率75% (SV-4 已驳回封顶).
// 只读派生不门控写操作, 真正的独立确认门控仍由服务端 submit-survey!/decide-survey! 在写入时强制.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
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

async function caption(page, text) {
  await page.evaluate(t => {
    let el = document.getElementById('b07-demo-caption');
    if (!el) {
      el = document.createElement('div');
      el.id = 'b07-demo-caption';
      el.style.cssText = 'position:fixed;left:50%;bottom:28px;transform:translateX(-50%);z-index:99999;'
        + 'max-width:82%;padding:14px 26px;border-radius:12px;background:rgba(17,24,39,0.92);color:#fff;'
        + 'font-size:20px;line-height:1.5;font-weight:600;box-shadow:0 8px 30px rgba(0,0,0,0.35);text-align:center;'
        + 'font-family:"PingFang SC","Microsoft YaHei",sans-serif;pointer-events:none;';
      document.body.appendChild(el);
    }
    el.textContent = t;
  }, text);
}

const pause = (page, ms = 1400) => page.waitForTimeout(ms);
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '工勘闭环汇总', exact: true }) }).first();

async function fixture(page, browser, label) {
  const suffix = serial();
  const menus = await api(page, 'GET', '/api/system/menu');
  const permissions = new Set(['pms:project:list', 'pms:project:query', 'pms:plan:approve', 'pms:quality:approve']);
  const menuIds = menus.filter(item => permissions.has(item.perms) || item.path === 'pms').map(item => item.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `工勘闭环审核${suffix}`, role_key: `pms_b07d_${suffix}`, 'menu-ids': menuIds });
  const roles = await api(page, 'GET', '/api/system/role');
  const roleId = roles.find(item => item.role_key === `pms_b07d_${suffix}`).role_id;
  const options = await api(page, 'GET', '/api/pms/options');
  const deptId = options.depts.find(item => item.dept_name === '研发部门').dept_id;
  const username = `pms_${suffix}`;
  const password = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: username, nick_name: '独立工勘审核人', password, dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'E2E' });
  const userId = (await api(page, 'GET', '/api/pms/options')).users.find(item => item.user_name === username).user_id;
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `B07D-${suffix}`, name: `${label} ${suffix}`, project_type: 'equipment', manager_id: options.currentUserId, dept_id: deptId, start_date: '2026-09-22', end_date: '2026-12-31' });
  const id = project.project_id;
  await api(page, 'POST', base(id) + '/members', { user_id: userId, role: 'viewer' });
  const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
  const reviewer = await context.newPage();
  await login(reviewer, username, password);
  return { id, userId, adminId: options.currentUserId, reviewer, context, suffix };
}

test.use({
  viewport: { width: 1600, height: 1000 },
  video: { mode: 'on', size: { width: 1600, height: 1000 } },
});

test.describe('B07 工勘闭环只读汇总 — 完整功能演示', () => {
  test.setTimeout(300000);

  test('四草稿逾期 -> 独立确认/确认中/驳回四态 -> 逐条批准刷新闭环率', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    await caption(page, 'B07 工勘闭环只读汇总 · 真实浏览器功能演示');
    await pause(page, 2000);

    await caption(page, '步骤 1/5 · 建项目 + 独立工勘审核人 + 证据文档');
    const f = await fixture(page, browser, '工勘闭环演示');
    const id = f.id;
    const command = (suffix, data, actor = page) => mutate(actor, id, suffix, data).then(r => r.result);
    const ev = (await command('/governance/documents', { code: 'B07-EV', title: '工勘证据', filename: 'b07.txt', content: '合成工勘交付物记录.' })).id;
    await pause(page, 600);

    await caption(page, '步骤 2/5 · 打开 工程交付 › 工勘与现场, 尚无工勘 -> 面板显示引导空态');
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无工勘任务, 按项目适用性登记各次工勘')).toBeVisible();
    await pause(page, 2600);

    await caption(page, '步骤 3/5 · 登记四次工勘全为草稿 (SV-1/2/4 计划已过, SV-3 计划未来) -> 工勘4 / 待提交4 / 逾期未确认3 / 闭环率 0%');
    const sv1 = await command('/delivery/surveys', { code: 'SV-1', title: '首次工勘', visit_no: 1, owner_id: f.adminId, planned_date: daysAgo(20), deliverable: '地基勘察' });
    const sv2 = await command('/delivery/surveys', { code: 'SV-2', title: '复勘', visit_no: 2, owner_id: f.adminId, planned_date: daysAgo(5), deliverable: '二次勘察' });
    const sv3 = await command('/delivery/surveys', { code: 'SV-3', title: '终勘', visit_no: 3, owner_id: f.adminId, planned_date: daysAhead(10), deliverable: '终勘' });
    const sv4 = await command('/delivery/surveys', { code: 'SV-4', title: '专项勘', visit_no: 4, owner_id: f.adminId, planned_date: daysAgo(8), deliverable: '专项勘察' });
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('工勘 4', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('待提交 4', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('逾期未确认 3', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('确认闭环率 0%', { exact: true })).toBeVisible();
    await pause(page, 3000);

    await caption(page, '步骤 4/5 · SV-1 独立确认通过 / SV-2 提交进入确认中(逾期) / SV-4 提交后驳回 -> 四态各1 / 闭环率 25%');
    await command(`/delivery/surveys/${sv1.id}/submit`, { reviewer_id: f.userId, evidence_ids: [ev], actual_date: daysAgo(1) });
    await command(`/delivery/surveys/${sv1.id}/decision`, { decision: 'approved', reason: '独立确认交付物' }, f.reviewer);
    await command(`/delivery/surveys/${sv2.id}/submit`, { reviewer_id: f.userId, evidence_ids: [ev], actual_date: daysAgo(1) });
    await command(`/delivery/surveys/${sv4.id}/submit`, { reviewer_id: f.userId, evidence_ids: [ev], actual_date: daysAgo(1) });
    await command(`/delivery/surveys/${sv4.id}/decision`, { decision: 'rejected', reason: '交付物不完整' }, f.reviewer);
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('待提交 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('确认中 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已确认 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('已驳回 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('逾期未确认 1', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('确认闭环率 25%', { exact: true })).toBeVisible();
    await pause(page, 3000);

    await caption(page, '步骤 5/5 · 再批准 SV-2 + SV-3 -> 已确认3 / 待提交0 / 逾期未确认0 / 闭环率 75% (SV-4 已驳回封顶, 只读汇总不门控写操作)');
    await command(`/delivery/surveys/${sv2.id}/decision`, { decision: 'approved', reason: '补交后确认' }, f.reviewer);
    await command(`/delivery/surveys/${sv3.id}/submit`, { reviewer_id: f.userId, evidence_ids: [ev], actual_date: daysAgo(0) });
    await command(`/delivery/surveys/${sv3.id}/decision`, { decision: 'approved', reason: '独立确认终勘' }, f.reviewer);
    await open(page, id, '工程交付', '工勘与现场');
    await panel(page).scrollIntoViewIfNeeded();
    await expect(panel(page).getByText('已确认 3', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('待提交 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('逾期未确认 0', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('确认闭环率 75%', { exact: true })).toBeVisible();
    await pause(page, 3000);

    const sc = (await api(page, 'GET', base(id) + '/delivery')).survey_closure;
    expect(sc).toMatchObject({ total: 4, draft: 0, 'in-review': 0, approved: 3, rejected: 1, open: 0, 'overdue-open': 0, 'closure-pct': 75 });
    expect(sc['nearest-planned']).toBeNull();

    await caption(page, '演示结束 · B07 工勘闭环只读汇总 (免迁移 / 无门控 / 无新命令 / 无新路由)');
    await pause(page, 2200);

    await f.context.close();
    expect(errors).toEqual([]);
  });
});
