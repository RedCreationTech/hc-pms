const { test, expect } = require('@playwright/test');
const path = require('node:path');

// B08 延伸: DQ 检查项"例外放行"(check-level exception waiver), 复用 Gate 例外语义 (免迁移, payload 存字段, 无新kind/命令/路由).
// 真实HTTP建交付件文档 -> 登记 DQ (2 必需检查项 R-1/R-2 + 绑定交付件) ->
//   界面对 R-1 选"检查通过", R-2 选"检查未通过" -> 重载后台账"必需检查就绪度"列显示红色"必需 1/2 缺 1"(未满足), 且无"例外";
//   再次"填写检查"把 R-2 选"例外放行"并填例外说明 -> 重载后同列翻绿"必需就绪 2/2" + 金色"例外 1";
//   "DQ与局部暂停"页签只读汇总面板显示"必需项全满足 1" + "靠例外满足 1";
//   提交给独立质量审批人并在第二真实上下文签认通过 -> 状态 approved (证明经例外满足的必需项可放行提交与签认);
//   真实HTTP GET /governance 回显 dq_required_waived=1 / dq_required_satisfied=2 / dq_required_met=true / dq_waived=1
//   以及 dq_summary['exception-met']=1, required-met=1, 与界面一致.
//   另有真实HTTP 防御性门控: 已选"通过"又叠加"例外" -> 400; 例外放行缺说明 -> 400 (均在写入前抛出, 不改变状态).
// 全程真实点击 + 真实HTTP + 真实浏览器截图, 免迁移读模型仅界面可见, 无例外时零回归.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/b08dqx');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
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

async function shotCard(page, card, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 合成一名具备质量审批权限的独立签认人 (登记/检查人须避开自审门控).
async function reviewerUser(page, suffix, deptId) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `DQ签认审批${suffix}`, role_key: `dqxw_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `dqxw_${suffix}`).role_id;
  const name = `dqxw_rev_${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: name, nick_name: 'DQ例外放行独立签认人', password: `E2e!${suffix}`,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'B08 DQ 例外放行 E2E 合成独立签认人' });
  return { id: (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === name).user_id, name, pwd: `E2e!${suffix}` };
}

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('B08 延伸 DQ 检查项例外放行浏览器验收', () => {
  test.setTimeout(180000);

  test('必需项经例外放行 -> 台账翻绿计入满足, 独立审批人可签认, 防御门控 400', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const reviewer = await reviewerUser(page, suffix, deptId);

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `DQXW-${suffix}`, name: `DQ检查项例外放行验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: reviewer.id, role: 'viewer' });

    const doc = await command(page, id, '/documents', { code: `XWDOC-${suffix}`, title: `交付件-${suffix}`, filename: '交付.txt', content: '确定版本交付件\n' });
    const dqTitle = `DQ例外放行主任务-${suffix}`;
    const dq = await command(page, id, '/dqs', { code: `DQXW-${suffix}`, title: dqTitle, owner_id: adminId,
      checklist: [{ code: 'R-1', title: '关键测试记录齐全', required: true }, { code: 'R-2', title: '计量器具校准到位', required: true }],
      deliverable_ids: [doc.id] });

    // 防御性门控 (真实HTTP, 写入前抛出 -> 不改变状态): 已选通过又叠加例外 -> 400; 例外缺说明 -> 400.
    await command(page, id, `/dqs/${dq.id}/checks`, { results: [{ code: 'R-1', passed: true, waived: true, waiver_reason: '矛盾', note: '' },
      { code: 'R-2', passed: false, note: '' }] }, 400);
    await command(page, id, `/dqs/${dq.id}/checks`, { results: [{ code: 'R-1', passed: true, note: '' },
      { code: 'R-2', passed: false, waived: true, waiver_reason: '', note: '' }] }, 400);

    // 界面第一轮: R-1 通过, R-2 未通过 -> 必需项未满足.
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await row(page, dqTitle).getByRole('button', { name: '填写检查', exact: true }).click();
    let form = modal(page, '填写DQ检查结果');
    await choose(page, form, 'passed_R-1', '检查通过');
    await choose(page, form, 'passed_R-2', '检查未通过');
    await shot(page, 'b08dqx-1-check-dialog.png');
    await save(page, '填写DQ检查结果');

    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(row(page, dqTitle).getByText('必需 1/2 缺 1')).toBeVisible();
    await expect(row(page, dqTitle).getByText('例外 1')).toHaveCount(0);
    await expect(row(page, dqTitle).getByText('必需就绪 2/2')).toHaveCount(0);
    await shotCard(page, drawer(page).locator('section').filter({ hasText: 'DQ 质量检查闭环汇总' }).first(), 'b08dqx-2-ledger-pending.png');

    // 必需项未满足 -> 提交签认被拒 (真实HTTP 409): 仍有必需检查项未通过.
    await command(page, id, `/dqs/${dq.id}/submit`, { reviewer_id: reviewer.id }, 409);

    // 界面第二轮: R-2 改为"例外放行"并填说明 -> 必需项全部满足.
    await row(page, dqTitle).getByRole('button', { name: '填写检查', exact: true }).click();
    form = modal(page, '填写DQ检查结果');
    await choose(page, form, 'passed_R-1', '检查通过');
    await choose(page, form, 'passed_R-2', '例外放行');
    await form.locator('#waiver_R-2').fill('计量器具下周一送检, 责任人同意先行签认.');
    await shot(page, 'b08dqx-3-waive-dialog.png');
    await save(page, '填写DQ检查结果');

    // 重载: 台账翻绿计入满足 + 例外计数; 汇总面板显示靠例外满足.
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(row(page, dqTitle).getByText('必需就绪 2/2')).toBeVisible();
    await expect(row(page, dqTitle).getByText('例外 1')).toBeVisible();
    await expect(row(page, dqTitle).getByText('必需 1/2 缺 1')).toHaveCount(0);
    const sum = panel(page, 'DQ 质量检查闭环汇总');
    await expect(sum.getByText(/必需项全满足\s*1/)).toBeVisible();
    await expect(sum.getByText(/靠例外满足\s*1/)).toBeVisible();
    await shotCard(page, sum, 'b08dqx-4-summary-exception.png');

    // 真实HTTP读模型回显.
    let ws = await api(page, 'GET', base(id) + '/governance');
    let d = ws.dqs.find(x => x.id === dq.id);
    expect(d.dq_required_satisfied, '必需项经通过或例外满足 2').toBe(2);
    expect(d.dq_required_waived, '靠例外满足的必需项 1').toBe(1);
    expect(d.dq_required_met, '必需项已全满足').toBe(true);
    expect(d.dq_waived, '例外检查项计数 1').toBe(1);
    expect(d.dq_passed, '纯通过计数 1 (不含例外)').toBe(1);
    expect(d.status, '全部必需满足 -> ready').toBe('ready');
    expect(ws.dq_summary['required-met'], '汇总必需项全满足 DQ 数 1').toBe(1);
    expect(ws.dq_summary['exception-met'], '汇总靠例外满足 DQ 数 1').toBe(1);

    // 提交给独立审批人并第二真实上下文签认通过 -> approved (经例外满足的必需项可正常提交签认).
    await command(page, id, `/dqs/${dq.id}/submit`, { reviewer_id: reviewer.id });
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const approver = await context.newPage();
    approver.on('pageerror', error => errors.push(error.message));
    await login(approver, reviewer.name, reviewer.pwd);
    await command(approver, id, `/dqs/${dq.id}/decision`, { decision: 'approved', reason: '例外项已获责任人书面确认, 予以签认.' });
    await context.close();

    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(sum.getByText(/已签认\s*100%/)).toBeVisible();
    ws = await api(page, 'GET', base(id) + '/governance');
    d = ws.dqs.find(x => x.id === dq.id);
    expect(d.status, '签认后 approved').toBe('approved');
    expect(d.decided_by, '签认人为独立审批人').toBe(reviewer.id);
    expect(d.dq_required_waived, '只读派生不改例外事实').toBe(1);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
