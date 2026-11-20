const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H03 范围排除项与验收准则逐条映射只读派生: 章程可选声明 scope_exclusions / acceptance_criteria (每行一条),
// 计划与执行 -> WBS与排程 的"范围排除项与验收准则映射"面板在读取时把每条声明比对 WBS 叶节点
// 编号/名称/描述及覆盖该叶节点的需求正文, 给出已映射/未映射. 造 叶"1.1 上位机软件"(含描述与需求正文)
// + 叶"1.2 风道结构", 章程声明排除项 2 条(命中 1 条)/验收准则 2 条(命中 1 条), 期望 已映射 2 (50%),
// 未映射 "客户现场安装" 与 "噪声<=60dB". 面板界面可见并以 GET /planning 与 GET /governance 服务端回显二次确认,
// 最后提交计划审批成功(200)证明未映射条目不阻断基线冻结. 空态(无已批准章程声明)单独截图.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h03sm');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
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
  const text = await response.text();
  let body;
  try { body = JSON.parse(text); } catch (e) { throw new Error(`${method} ${url} -> ${response.status()} ${text.slice(0, 200)}`); }
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(expected);
  return body.data;
}

async function mutate(page, id, suffix, data = {}) {
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
}

async function fill(form, values) {
  for (const [key, value] of Object.entries(values)) await form.locator(`#${key}`).fill(String(value));
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
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function wbsTask(page, code, name, days) {
  await drawer(page).getByRole('button', { name: '新建WBS任务', exact: true }).click();
  const form = modal(page, '新建WBS任务');
  await fill(form, { wbs_code: code, name, duration_days: days, start_date: '2026-09-22' });
  await choose(page, form, 'owner_id', /\/ admin$/);
  return (await save(page, '新建WBS任务')).result;
}

async function addRequirement(page, code, text) {
  await drawer(page).getByRole('button', { name: '新增URS需求', exact: true }).click();
  const form = modal(page, '新增URS需求');
  await fill(form, { code, text });
  await choose(page, form, 'owner_id', /\/ admin$/);
  return (await save(page, '新增URS需求')).result;
}

async function addTrace(page, requirementCode, targetName) {
  await drawer(page).getByRole('button', { name: '建立需求追踪', exact: true }).click();
  const form = modal(page, '建立需求追踪');
  await choose(page, form, 'requirement_id', requirementCode);
  await choose(page, form, 'target', targetName);
  await choose(page, form, 'relation', '满足需求');
  return (await save(page, '建立需求追踪')).result;
}

test.describe('H03 范围排除项与验收准则逐条映射浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('章程逐条声明 -> 面板映射可见, 服务端回显一致, 未映射不阻断计划冻结', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H03SM-${suffix}`, name: `范围声明映射验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await mutate(page, id, '/transition', { status: 'initiated', reason: 'E2E立项前置' });
    await mutate(page, id, '/transition', { status: 'planning', reason: 'E2E计划前置' });

    // 两个叶节点: 1.1 上位机软件(含描述), 1.2 风道结构.
    await open(page, id, '计划与执行', 'WBS与排程');
    await wbsTask(page, '1.1', '上位机软件', 2);
    await wbsTask(page, '1.2', '风道结构', 3);

    // 需求 + satisfies 追踪指向 1.1, 其正文含"上位机软件".
    const reqCode = `URS-H03SM-${suffix}`;
    await open(page, id, '需求与治理', 'URS与追踪');
    await addRequirement(page, reqCode, '上位机软件须支持自动标定');
    await addTrace(page, reqCode, '上位机软件');

    // 章程逐条声明: 排除项命中 1.2(风道结构), 另一条不命中; 验收准则命中 1.1, 另一条不命中.
    await open(page, id, '需求与治理', '章程');
    await drawer(page).getByRole('button', { name: '编制项目章程', exact: true }).click();
    const charterForm = modal(page, '编制项目章程');
    await fill(charterForm, { title: `章程-${suffix}`, objective: '完成整机交付', scope: '设备与培训',
      success_criteria: '验收清单全部通过' });
    await choose(page, charterForm, 'sponsor_id', /\/ admin$/);
    await charterForm.locator('#scope_exclusions').fill('风道结构\n客户现场安装');
    await charterForm.locator('#acceptance_criteria').fill('上位机软件\n噪声<=60dB');
    const charter = (await save(page, '编制项目章程')).result;
    expect(charter.scope_exclusions).toEqual(['风道结构', '客户现场安装']);
    expect(charter.acceptance_criteria).toEqual(['上位机软件', '噪声<=60dB']);

    // 独立批准: 提交人与审核人须为不同用户, 由第二位管理员级成员独立批准 (与 H01 同一口径).
    const rvUser = `h03rv${suffix}`;
    await api(page, 'POST', '/api/system/user',
      { user_name: rvUser, nick_name: `审核人 ${suffix}`, password: 'rvrv123456', status: '0',
        dept_id: deptId, posts: [], roles: [1] });
    const rvId = (await api(page, 'GET', `/api/system/user?page=1&size=20&user_name=${rvUser}`)).rows[0].user_id;
    await api(page, 'POST', base(id) + '/members', { user_id: rvId, role: 'viewer' });
    const rvToken = await page.evaluate(async ({ rvUser }) => {
      const res = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: rvUser, password: 'rvrv123456' }) });
      return (await res.json()).data.token;
    }, { rvUser });
    await mutate(page, id, `/governance/charters/${charter.id}/submit`, { reviewer_id: rvId });
    const decide = await page.evaluate(async ({ id, rid, rvToken }) => {
      const cur = await (await fetch(`/api/pms/projects/${id}`, { headers: { Authorization: `Bearer ${rvToken}` } })).json();
      const res = await fetch(`/api/pms/projects/${id}/governance/charters/${rid}/decision`, { method: 'POST',
        headers: { Authorization: `Bearer ${rvToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ decision: 'approved', reason: '范围与验收准则清晰', version: cur.data.version }) });
      return (await res.json()).code;
    }, { id, rid: charter.id, rvToken });
    expect(decide, '独立审核人批准章程').toBe(200);
    let detail = await api(page, 'GET', base(id) + '/governance');
    let stored = detail.charters.find(c => c.id === charter.id);
    expect(stored.status).toBe('approved');
    expect(stored.scope_exclusions).toEqual(['风道结构', '客户现场安装']);
    expect(stored.acceptance_criteria).toEqual(['上位机软件', '噪声<=60dB']);

    // 计划与执行: 映射面板徽标 + 逐条明细.
    await open(page, id, '计划与执行', 'WBS与排程');
    const smPanel = panel(page, '范围排除项与验收准则映射');
    await expect(smPanel.getByText('逐条声明 4', { exact: true })).toBeVisible();
    await expect(smPanel.getByText('已映射 2 (50%)', { exact: true })).toBeVisible();
    await expect(smPanel.getByText('排除项 1/2', { exact: true })).toBeVisible();
    await expect(smPanel.getByText('验收准则 1/2', { exact: true })).toBeVisible();
    await expect(smPanel.locator('.ant-tag').filter({ hasText: '风道结构 → 1.2' })).toBeVisible();
    await expect(smPanel.locator('.ant-tag').filter({ hasText: /^上位机软件 → .*1\.1/ })).toBeVisible();
    await expect(smPanel.locator('.ant-tag').filter({ hasText: /^客户现场安装$/ })).toBeVisible();
    await expect(smPanel.locator('.ant-tag').filter({ hasText: /^噪声<=60dB$/ })).toBeVisible();
    await panelShot(page, '范围排除项与验收准则映射', 'h03sm-1-panel.png');
    await shot(page, 'h03sm-2-page.png');

    // 服务端读取时派生回显二次确认(planning 与 governance 两个读模型同源).
    const plan = await api(page, 'GET', base(id) + '/planning');
    const review = plan.scope_statement_review;
    expect(review.available).toBe(true);
    expect(review['exclusions-total']).toBe(2);
    expect(review['exclusions-mapped']).toBe(1);
    expect(review['acceptance-total']).toBe(2);
    expect(review['acceptance-mapped']).toBe(1);
    expect(review['mapped-total']).toBe(2);
    expect(review['mapped-pct']).toBe(50);
    expect(review.unmapped).toEqual(['客户现场安装', '噪声<=60dB']);
    expect(detail.scope_statement_review.exclusions.map(e => e.mapped)).toEqual([true, false]);
    expect(detail.scope_statement_review['acceptance_criteria'].map(e => e.mapped)).toEqual([true, false]);

    // 只读不门控: 存在未映射声明, 提交计划审批仍成功并冻结.
    await open(page, id, '计划与执行', '审批与基线');
    await drawer(page).getByRole('button', { name: '提交计划审批', exact: true }).click();
    await fill(modal(page, '提交计划审批'), { comment: '含未映射声明仍提交, 验证逐条映射不阻断冻结' });
    await save(page, '提交计划审批');
    await expect(drawer(page).getByText('计划已冻结,等待独立审批.')).toBeVisible();

    // 空态: 新项目未声明排除项与验收准则, 面板提示未声明.
    const empty = await api(page, 'POST', '/api/pms/projects', { project_no: `H03SM0-${suffix}`, name: `范围声明空态 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    await mutate(page, empty.project_id, '/transition', { status: 'initiated', reason: 'E2E空态前置' });
    await mutate(page, empty.project_id, '/transition', { status: 'planning', reason: 'E2E空态前置' });
    await open(page, empty.project_id, '计划与执行', 'WBS与排程');
    const emptyPanel = panel(page, '范围排除项与验收准则映射');
    await expect(emptyPanel.getByText('章程未声明范围排除项与验收准则', { exact: true })).toBeVisible();
    await panelShot(page, '范围排除项与验收准则映射', 'h03sm-3-empty.png');
    const emptyPlan = await api(page, 'GET', base(empty.project_id) + '/planning');
    expect(emptyPlan.scope_statement_review.available).toBe(false);
    expect(emptyPlan.scope_statement_review['mapped-pct']).toBe(0);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});