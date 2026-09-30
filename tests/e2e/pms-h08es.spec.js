const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H08 延伸: 风险超阈值升级处置闭环汇总只读派生洞察 (免迁移, 无新命令/新kind/新路由/不构成门控).
// 界面登记 5x5(steering)/4x4(management)/3x5(未达阈值) 三条风险 -> "风险与问题"页签新增只读"风险升级处置汇总"面板:
// 按每个风险最新有效版本聚合升级总数, 待独立确认/已确认/已豁免计数及管理层/经理层分级;
// 由登记人之外的独立质量审批人在第二真实浏览器上下文逐条确认(批准责成/评估豁免) -> 面板待确认翻转归零.
// 只读派生不改变风险状态 (未升级风险仍"登记"态).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h08es');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
// shared/panel 渲染 <section>, 按面板标题(heading)定位到具体面板, 避免"风险总数 N"跨面板文本串台.
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
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
  await page.waitForLoadState('networkidle');
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

// 界面"登记项目风险": 按给定概率 x 影响评分 (5x5=25 steering, 4x4=16 management, 3x5=15 未达阈值不升级).
async function registerRisk(page, { title, probability, impact }) {
  await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
  const form = modal(page, '登记项目风险');
  await form.locator('#title').fill(title);
  await form.locator('#probability').fill(String(probability));
  await form.locator('#impact').fill(String(impact));
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#mitigation').fill('预留纠偏窗口并持续监控.');
  await form.locator('#due_date').fill('2026-10-20');
  return save(page, '登记项目风险');
}

// 独立审批人在第二上下文对某风险行点"确认升级处置", 选决定并填写处置意见后保存.
async function ackEscalation(page, title, decisionLabel, note) {
  await row(page, title).getByRole('button', { name: '确认升级处置', exact: true }).click();
  const form = modal(page, '确认风险升级');
  await choose(page, form, 'decision', decisionLabel);
  await form.locator('#note').fill(note);
  return save(page, '确认风险升级');
}

test.describe('H08 延伸 风险升级处置闭环汇总只读洞察浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('升级汇总面板聚合待确认/分级, 独立审批人逐条确认后待归零且状态不漂移', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    // 合成只读且具备质量审批权限的独立审批人 (不改任何内置账号); admin 是登记人须避开自确认门控.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    const suffix = serial();
    await api(page, 'POST', '/api/system/role', { role_name: `升级汇总审批${suffix}`, role_key: `esc_sum_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `esc_sum_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const approverName = `escs_${suffix}`;
    const approverPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '升级汇总独立审批人', password: approverPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'H08 升级汇总 E2E 合成独立审批人' });
    const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `ESC-${suffix}`, name: `风险升级处置汇总验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });

    // 界面登记三条风险: 5x5=25 升管理层, 4x4=16 升经理层, 3x5=15 未达阈值不升级.
    const steering = `关键物料断供风险-${suffix}`;
    const management = `成本超支风险-${suffix}`;
    const mild = `人员波动风险-${suffix}`;
    await open(page, id, '需求与治理', '风险与问题');
    await registerRisk(page, { title: steering, probability: 5, impact: 5 });
    await registerRisk(page, { title: management, probability: 4, impact: 4 });
    await registerRisk(page, { title: mild, probability: 3, impact: 5 });

    // 面板初态: 总数3, 已超阈值升级2, 待独立确认2, 管理层·1, 经理层·1 (尚无已确认/已豁免).
    await open(page, id, '需求与治理', '风险与问题');
    const esc = panel(page, '风险升级处置汇总');
    await expect(esc).toBeVisible();
    await expect(esc.getByText(/风险总数\s*3/)).toBeVisible();
    await expect(esc.getByText(/已超阈值升级\s*2/)).toBeVisible();
    await expect(esc.getByText(/待独立确认\s*2/)).toBeVisible();
    await expect(esc.getByText(/管理层\s*·\s*1/)).toBeVisible();
    await expect(esc.getByText(/经理层\s*·\s*1/)).toBeVisible();
    await expect(esc.getByText(/已确认责成处置/)).toHaveCount(0);
    await expect(esc.getByText(/评估后豁免/)).toHaveCount(0);
    await shot(page, 'h08es-1-escalated-pending.png');

    // 真实HTTP读模型回显 risk_escalation_summary 初始聚合.
    let ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.risk_escalation_summary.total, '分母=最新有效版本风险数').toBe(3);
    expect(ws.risk_escalation_summary.escalated, '超阈值升级数').toBe(2);
    expect(ws.risk_escalation_summary['not-escalated'], '未升级数').toBe(1);
    expect(ws.risk_escalation_summary.pending, '待确认数').toBe(2);
    expect(ws.risk_escalation_summary.acknowledged, '已确认数').toBe(0);
    expect(ws.risk_escalation_summary.waived, '已豁免数').toBe(0);
    expect(ws.risk_escalation_summary['by-level'].find(l => l.level === 'steering').count, '管理层计数').toBe(1);
    expect(ws.risk_escalation_summary['by-level'].find(l => l.level === 'management').count, '经理层计数').toBe(1);

    // 独立审批人第二真实上下文亲自逐条确认: 批准责成管理层升级.
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const approver = await context.newPage();
    approver.on('pageerror', error => errors.push(error.message));
    await login(approver, approverName, approverPwd);
    await open(approver, id, '需求与治理', '风险与问题');
    await expect(row(approver, steering).getByRole('button', { name: '确认升级处置', exact: true })).toBeVisible();
    await ackEscalation(approver, steering, '确认升级并责成处置', '管理层责成启动备选供应商并加严来料检验.');

    // 回到登记人视角重载: 面板待确认减一, 出现"已确认责成处置 1", 升级总数与分级不变.
    await open(page, id, '需求与治理', '风险与问题');
    await expect(esc.getByText(/待独立确认\s*1/)).toBeVisible();
    await expect(esc.getByText(/已确认责成处置\s*1/)).toBeVisible();
    await expect(esc.getByText(/已超阈值升级\s*2/)).toBeVisible();
    await expect(esc.getByText(/管理层\s*·\s*1/)).toBeVisible();
    await shot(page, 'h08es-2-one-acknowledged.png');

    // 独立审批人对经理层升级选择"评估后可在现层处置" -> 豁免, 待确认归零.
    await open(approver, id, '需求与治理', '风险与问题');
    await ackEscalation(approver, management, '评估后可在现层处置', '影响可控, 评估后可在经理层处置并解除升级门控.');
    await context.close();

    // 面板终态: 待独立确认消失, 已确认1, 评估后豁免1, 分级不漂移.
    await open(page, id, '需求与治理', '风险与问题');
    await expect(esc.getByText(/待独立确认/)).toHaveCount(0);
    await expect(esc.getByText(/已确认责成处置\s*1/)).toBeVisible();
    await expect(esc.getByText(/评估后豁免\s*1/)).toBeVisible();
    await expect(esc.getByText(/已超阈值升级\s*2/)).toBeVisible();
    await shot(page, 'h08es-3-closed-disposition.png');

    // 真实HTTP读模型终态 + 只读派生不改变风险状态: 未升级风险仍登记态, 已确认风险仍登记态且升级字段回显正确.
    ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.risk_escalation_summary.pending, '待确认归零').toBe(0);
    expect(ws.risk_escalation_summary.acknowledged, '已确认1').toBe(1);
    expect(ws.risk_escalation_summary.waived, '已豁免1').toBe(1);
    const mildRow = ws.risks.find(r => r.title === mild);
    expect(mildRow.escalated, '未达阈值风险不升级').toBe(false);
    expect(mildRow.status, '只读派生不改风险状态').toBe('open');
    const steeringRow = ws.risks.find(r => r.title === steering);
    expect(steeringRow.escalation_state, '管理层升级已确认').toBe('acknowledged');
    expect(steeringRow.escalation_ack_by, '确认人为独立审批人').toBe(approverId);
    expect(steeringRow.status, '确认后风险仍登记态').toBe('open');
    const managementRow = ws.risks.find(r => r.title === management);
    expect(managementRow.escalation_state, '经理层升级已豁免').toBe('waived');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
