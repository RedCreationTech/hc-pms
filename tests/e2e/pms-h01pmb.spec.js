const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H01 章程授权项目经理的权限联动 + 章程预算与财务基线对账:
// 章程批准前授权 PM 只是展示字段 (不授权), 批准后获得与 manager_id 同级的真实编辑范围并收到本地通知;
// 章程冻结的初始预算与财务 budget 版本在读取时对账 (币种一致才比较金额), 只读派生不构成门控.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h01pmb');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: '章程预算与财务基线对账', exact: true }) }).first();
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

async function open(page, id) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await drawer(page).getByRole('tab', { name: '需求与治理', exact: true }).click();
  await drawer(page).getByRole('tab', { name: '章程', exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function shotPanel(page, file) {
  const el = panel(page);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: '章程预算与财务基线对账', exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('H01 章程授权PM权限联动与预算对账', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(240000);

  test('章程指定授权PM并填初始预算, 独立批准后与财务预算基线只读对账', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    // 种子库只有 admin: 通过真实用户接口建一个普通用户作授权 PM, 才能区分"经理"与"章程授权"两条授权来源.
    // 该用户只以 viewer 成员身份加入, 章程批准前不具备编辑范围.
    const pmUser = `h01pm${suffix}`;
    await api(page, 'POST', '/api/system/user',
      { user_name: pmUser, nick_name: `授权PM ${suffix}`, password: 'pmpm123456', status: '0',
        dept_id: deptId, posts: [], roles: [2] });
    const listed = await api(page, 'GET', `/api/system/user?page=1&size=20&user_name=${pmUser}`);
    const pmId = listed.rows[0].user_id;


    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H01PMB-${suffix}`, name: `章程授权PM联动 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    // 候选授权 PM 先成为 viewer 成员 (工作台人员下拉只列成员).
    await api(page, 'POST', base(id) + '/members', { user_id: pmId, role: 'viewer' });

    // 1) 章程登记初始预算并指定授权 PM (权限联动与通知投递由领域测试 charter-authorized-pm-gains-edit-scope-only-after-approval 覆盖).
    const charter = await api(page, 'POST', `${base(id)}/governance/charters`, {
      title: `授权PM章程-${suffix}`, objective: '建成并验证产线', scope: '设备与培训', success_criteria: 'SAT全部通过',
      sponsor_id: adminId, initial_budget: '120000.00', budget_currency: 'CNY', authorized_pm_id: pmId,
      version: (await api(page, 'GET', base(id))).version });
    const rid = charter.result.id;
    expect(charter.result.authorized_pm_id).toBe(pmId);

    // 2) 审核人必须与提交人分离且具 pms:quality:approve: 由第二位管理员级用户独立批准.
    const rvUser = `h01rv${suffix}`;
    await api(page, 'POST', '/api/system/user',
      { user_name: rvUser, nick_name: `审核人 ${suffix}`, password: 'rvrv123456', status: '0',
        dept_id: deptId, posts: [], roles: [1] });
    const rvId = (await api(page, 'GET', `/api/system/user?page=1&size=20&user_name=${rvUser}`)).rows[0].user_id;
    // 审核人须为项目成员才能被指定为审核人 (治理与财务同一口径).
    await api(page, 'POST', base(id) + '/members', { user_id: rvId, role: 'viewer' });
    const rvToken = await page.evaluate(async ({ rvUser }) => {
      const res = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: rvUser, password: 'rvrv123456' }) });
      return (await res.json()).data.token;
    }, { rvUser });
    await api(page, 'POST', `${base(id)}/governance/charters/${rid}/submit`,
      { reviewer_id: rvId, version: (await api(page, 'GET', base(id))).version });

    // 3) 由独立审核人批准章程.
    const decide = await page.evaluate(async ({ id, rid, rvToken }) => {
      const cur = await (await fetch(`/api/pms/projects/${id}`, { headers: { Authorization: `Bearer ${rvToken}` } })).json();
      const res = await fetch(`/api/pms/projects/${id}/governance/charters/${rid}/decision`, { method: 'POST',
        headers: { Authorization: `Bearer ${rvToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ decision: 'approved', reason: '范围与预算明确', version: cur.data.version }) });
      return (await res.json()).code;
    }, { id, rid, rvToken });
    expect(decide, '独立审核人批准章程').toBe(200);

    // 5) 读模型: 章程已批准, 预算基线对账在无财务版本时为 no_baseline; 界面面板同源呈现.
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.charters.find(c => c.id === rid).status).toBe('approved');
    const rec0 = ws.charter_budget_baseline;
    expect(rec0.state).toBe('no_baseline');
    expect(rec0.charter.amount).toBe('120000.00');
    await open(page, id);
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('尚无财务预算基线', { exact: true })).toBeVisible();
    await expect(panel(page).getByText('章程预算 120000.00 CNY', { exact: true })).toBeVisible();
    await shot(page, 'h01pmb-1-charter.png');
    await shotPanel(page, 'h01pmb-1-panel.png');

    // 6) 财务侧登记并批准真实 budget 版本 -> 只读对账给出差额.
    const budget = await api(page, 'POST', `${base(id)}/cost-versions`, {
      kind: 'budget', period: '2026-09', currency: 'CNY', name: '九月预算', revenue: '150000.00', reviewer_id: rvId,
      version: (await api(page, 'GET', base(id))).version });
    const cid = budget.result.id;
    for (const [label, amount] of [['主材采购', '80000.00'], ['装配人工', '30000.00']]) {
      await api(page, 'POST', `${base(id)}/cost-versions/${cid}/entries`,
        { category: 'material', label, amount, version: (await api(page, 'GET', base(id))).version });
    }
    await api(page, 'POST', `${base(id)}/cost-versions/${cid}/submit`, { version: (await api(page, 'GET', base(id))).version });
    const reviewed = await page.evaluate(async ({ id, cid, rvToken }) => {
      const cur = await (await fetch(`/api/pms/projects/${id}`, { headers: { Authorization: `Bearer ${rvToken}` } })).json();
      const res = await fetch(`/api/pms/projects/${id}/cost-versions/${cid}/review`, { method: 'POST',
        headers: { Authorization: `Bearer ${rvToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ decision: 'approved', reason: '预算核定', version: cur.data.version }) });
      return (await res.json()).code;
    }, { id, cid, rvToken });
    expect(reviewed, '独立审核人批准预算版本').toBe(200);

    const rec1 = (await api(page, 'GET', base(id) + '/governance')).charter_budget_baseline;
    expect(rec1.state).toBe('under', '财务预算低于章程预算时记为 under');
    expect(rec1.currency_match).toBe(true);
    expect(rec1.baseline.approved).toBe(true);
    await open(page, id);
    await expect(panel(page).getByText('低于章程预算', { exact: true })).toBeVisible();
    await shotPanel(page, 'h01pmb-2-reconciled.png');

    expect(errors).toEqual([]);
  });
});