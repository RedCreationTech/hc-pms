const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H01 章程显式授权项目经理: 界面在"编制项目章程"里选授权项目经理 -> 台账"授权PM"列回显姓名;
// 未选则显示"未指定"且不含该键; 修订形成新不可变版本, 旧版本授权PM不漂移; 非法人员经真实HTTP 400; 授权PM为章程专属字段.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h01pm');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const charterForm = page => modal(page, '编制项目章程');

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

async function save(page, title) {
  const form = modal(page, title);
  const response = page.waitForResponse(r => r.url().includes('/api/pms/') && ['POST', 'PUT', 'DELETE'].includes(r.request().method()));
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body.data;
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 同表单多个 antd Select 的下拉面板会同时留在 DOM, 通用 :not(hidden) 会命中隐藏面板.
// 沿用仓库既有 choose 套路: 点 #key 打开, 用该 combobox 的 aria-controls 过滤出它自己的下拉, 选完按 Escape 收起.
async function pickSelect(page, form, key, optionText) {
  const input = form.locator(`#${key}`);
  await input.click();
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: optionText }).first().click();
  await form.locator(`#${key}`).press('Escape');
  await page.waitForLoadState('networkidle');
}

// 打开"编制项目章程"并填写必填项 + 发起人, 若给定 pmLabel 则再选授权项目经理; 停在保存前以便截图.
async function fillCharter(page, { title, objective, scope, criteria, sponsor, pmLabel }) {
  await drawer(page).getByRole('button', { name: '编制项目章程', exact: true }).click();
  const form = charterForm(page);
  await form.locator('#title').fill(title);
  await form.locator('#objective').fill(objective);
  await form.locator('#scope').fill(scope);
  await form.locator('#success_criteria').fill(criteria);
  await pickSelect(page, form, 'sponsor_id', sponsor);
  if (pmLabel) await pickSelect(page, form, 'authorized_pm_id', pmLabel);
}

test.describe('H01 章程显式授权项目经理浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('界面选授权PM入台账列, 未选显示未指定, 修订不漂移, 非法人员经真实HTTP被拒', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const users = options.users || [];
    const pmUser = users.find(u => u.user_name !== 'admin' && (u.status == null || u.status === '0')) || users.find(u => u.user_name === 'admin');
    expect(pmUser, '至少有一个可选用户').toBeTruthy();
    const pmId = pmUser.user_id;
    const pmLabel = pmUser.nick_name || pmUser.user_name;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H01PM-${suffix}`, name: `章程授权PM验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    // 工作台人员下拉只列项目成员 (detail.cljs member-options), 非 admin 的候选授权 PM 须先加入成员, 否则下拉中不存在该选项.
    if (pmId !== adminId) await api(page, 'POST', base(id) + '/members', { user_id: pmId, role: 'viewer' });
    await open(page, id, '需求与治理', '章程');

    // 1) 界面登记: 选择授权项目经理 (与发起人分列在两个下拉), 保存前截图证明该显式字段可选.
    //    注意: 每项目章程 code 固定为 "charter", 多次"编制"会堆叠为同一逻辑章程的修订版, 故此处只建一份再逐版修订.
    const title = `授权PM章程-${suffix}`;
    await fillCharter(page, { title, objective: '建成并验证产线', scope: '设备与培训', criteria: 'SAT全部通过', sponsor: 'admin', pmLabel: pmUser.user_name });
    await shot(page, 'h01pm-1-dialog-select.png');
    const created = await save(page, '编制项目章程');
    const rid1 = created.result.id;
    expect(created.result.authorized_pm_id, '命令响应回显显式授权PM').toBe(pmId);
    expect(created.result.revision, '首版revision为1').toBe(1);

    // 2) 修订: 显式改授权PM -> 生成新不可变版本, 旧版本保持原授权PM(批准依据不漂移).
    const r2 = await api(page, 'POST', `${base(id)}/governance/charters/${rid1}/revisions`, {
      title, objective: '建成并验证产线', scope: '设备与培训(改授权PM)', success_criteria: 'SAT全部通过',
      sponsor_id: adminId, authorized_pm_id: adminId, version: (await api(page, 'GET', base(id))).version });
    expect(r2.result.revision, '第二版').toBe(2);
    expect(r2.result.authorized_pm_id, '修订保留授权PM').toBe(adminId);

    // 3) 修订: 取消授权PM -> 新不可变版本不含该键(由项目 manager_id 隐含承载), 台账该行显示"未指定".
    const r3 = await api(page, 'POST', `${base(id)}/governance/charters/${r2.result.id}/revisions`, {
      title, objective: '建成并验证产线', scope: '设备与培训(取消授权PM)', success_criteria: 'SAT全部通过',
      sponsor_id: adminId, version: (await api(page, 'GET', base(id))).version });
    expect(r3.result.revision, '第三版').toBe(3);
    expect(r3.result.authorized_pm_id == null, '第三版不含授权PM').toBeTruthy();

    // 4) 读模型回显: 三个不可变版本并存, 首版授权PM不漂移.
    const ws = await api(page, 'GET', base(id) + '/governance');
    const v1 = ws.charters.find(c => c.id === rid1);
    const v2 = ws.charters.find(c => c.id === r2.result.id);
    const v3 = ws.charters.find(c => c.id === r3.result.id);
    expect(v1.authorized_pm_id, '首版授权PM不漂移').toBe(pmId);
    expect(v2.authorized_pm_id, '第二版保留授权PM').toBe(adminId);
    expect(v3.authorized_pm_id == null, '第三版无授权PM').toBeTruthy();

    // 5) 台账界面 (重新进入拉取最新读模型): "授权PM"列回显姓名, 取消授权PM的行显示"未指定".
    //    限定到表格单元格, 避免与"项目概况"页签里同样显示为管理员昵称的负责人描述项(隐藏)串台.
    await open(page, id, '需求与治理', '章程');
    await expect(drawer(page).locator('.ant-table-cell', { hasText: pmLabel }).first()).toBeVisible();
    await expect(drawer(page).locator('.ant-table-cell', { hasText: '未指定' }).first()).toBeVisible();
    await shot(page, 'h01pm-2-ledger-column.png');

    // 6) 真实HTTP拒绝路径: 不存在的人员作为授权PM -> 400.
    const badPm = await page.evaluate(async ({ id, suffix }) => {
      const token = localStorage.getItem('ruoyi_token');
      const version = (await (await fetch(`/api/pms/projects/${id}`, { headers: { Authorization: `Bearer ${token}` } })).json()).data.version;
      const r = await fetch(`/api/pms/projects/${id}/governance/charters`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: `BAD-${suffix}`, objective: 'o', scope: 's', success_criteria: 'c', sponsor_id: 1, authorized_pm_id: 999999, version }) });
      return (await r.json()).code;
    }, { id, suffix });
    expect(badPm, '不存在的授权PM应被拒').toBe(400);

    // 7) 授权PM是章程专属字段: 在合法变更申请体上追加应被白名单拒绝 (400).
    const badChange = await page.evaluate(async ({ id, suffix }) => {
      const token = localStorage.getItem('ruoyi_token');
      const version = (await (await fetch(`/api/pms/projects/${id}`, { headers: { Authorization: `Bearer ${token}` } })).json()).data.version;
      const r = await fetch(`/api/pms/projects/${id}/governance/changes`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: `CHG-${suffix}`, reason: 'r', scope_impact: 's', schedule_impact: 's', cost_impact: 'c', quality_impact: 'q', resource_impact: 'r', authorized_pm_id: 1, version }) });
      return (await r.json()).code;
    }, { id, suffix });
    expect(badChange, '授权PM为章程专属字段, 变更体拒绝').toBe(400);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
