const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C09d 问题阻断级自动升级: 界面登记 blocker 问题 -> 登记即自动升级(未逾期->经理层, 逾期->管理层), 台账显示
// "待升级确认 / <level>" -> 未经独立确认提交解决被 409 门控, 登记人自确认被 403 -> 登记人之外的独立质量审批人
// "确认升级处置" -> 徽标转"升级已确认", 此后提交解决门控解除(真实HTTP 200). major 问题不触发升级可径直提交解决.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c09d');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
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

// 只取返回信封 code, 不做 200 断言, 用于验证门控(409/403)与解除门控(200).
async function callCode(page, method, url, data) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data });
  return response.json();
}

async function mutateCode(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  return callCode(page, 'POST', base(id) + suffix, { ...data, version: project.version });
}

// 断言 200 并返回信封 data (命令类写操作的数据在 data.result 下).
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

async function choose(page, form, key, text) {
  const input = form.locator(`#${key}`);
  await input.click();
  const list = await input.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await form.locator(`#${key}`).press('Escape');
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

async function issueRow(page, id, iid) {
  const gov = await api(page, 'GET', base(id) + '/governance');
  return gov.issues.find(r => r.id === iid);
}

// 严重程度下拉展示中文标签 (value -> label), 选项点击按可见文本命中, 断言仍用后端 value.
const sevLabel = { blocker: '阻断', major: '严重', minor: '一般' };

// 用真实登记人(admin)在"风险与问题"页签的"问题闭环"面板通过界面表单登记一个问题, 返回命令结果(记录在 result 下).
async function createIssue(page, id, { title, severity, due }) {
  await open(page, id, '需求与治理', '风险与问题');
  await drawer(page).getByRole('button', { name: '登记项目问题', exact: true }).click();
  const form = modal(page, '登记项目问题');
  await form.locator('#title').fill(title);
  await choose(page, form, 'severity', sevLabel[severity]);
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#due_date').fill(due);
  return save(page, '登记项目问题');
}

// 建一个只读且具备质量审批权限的独立审批人 + 项目成员, 返回 { approverName, approverPwd, approverId }.
async function makeApprover(page, id, suffix, deptId, adminId) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `问题升级审批${suffix}`, role_key: `iss_esc_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `iss_esc_${suffix}`).role_id;
  const approverName = `iss_${suffix}`;
  const approverPwd = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '问题升级独立审批人', password: approverPwd,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C09d E2E合成独立审批人' });
  const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;
  await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });
  return { approverName, approverPwd, approverId };
}

async function createProject(page, suffix, deptId, adminId) {
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C09D-${suffix}`, name: `问题升级门控验收 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
  return project.project_id;
}

test.describe('C09d 问题阻断级自动升级浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('阻断级问题自动升级 -> 提交解决被门控 -> 独立确认 -> 门控解除提交解决', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const id = await createProject(page, suffix, deptId, adminId);
    const { approverName, approverPwd, approverId } = await makeApprover(page, id, suffix, deptId, adminId);

    // 证据文档: 供门控解除后提交解决时引用.
    const evidence = (await mutateData(page, id, '/governance/documents',
      { code: `ISS-${suffix}`, title: '阻断级整改复验记录', filename: 'fix.txt', content: '阻断级问题的整改与复验证据已归档.' })).result;

    // 界面登记阻断级问题(到期日为未来 -> 升级到经理层 management, 未逾期).
    const title = `现场主机无法上电-${suffix}`;
    const created = await createIssue(page, id, { title, severity: 'blocker', due: '2026-12-20' });
    const iid = created.result.id;
    expect(created.result.escalated, '阻断级登记即自动升级').toBe(true);
    expect(created.result.escalation_state).toBe('pending');
    expect(created.result.escalation_level, '未逾期升级到经理层').toBe('management');

    // 截图1: 台账"超阈值升级"列显示"待升级确认 / management"; 登记人本人看不到"确认升级处置"入口.
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, title)).toBeVisible();
    await expect(row(page, title).getByText('待升级确认 / management')).toBeVisible();
    await expect(row(page, title).getByRole('button', { name: '确认升级处置', exact: true })).toHaveCount(0);
    await shot(page, 'c09d-1-pending-escalation.png');

    // 门控验证(真实HTTP): 未经独立确认提交解决 -> 409; 登记人自确认升级 -> 403.
    const blockedResolve = await mutateCode(page, id, `/governance/issues/${iid}/resolve`,
      { resolution: '已更换电源模块', reviewer_id: approverId, evidence_ids: [evidence.id] });
    expect(blockedResolve.code, '升级未确认前不得提交解决').toBe(409);
    const selfAck = await mutateCode(page, id, `/governance/issues/${iid}/escalate`,
      { decision: 'approved', note: '登记人自确认' });
    expect(selfAck.code, '登记人不得自确认升级').toBe(403);

    // 独立审批人第二浏览器上下文登录, 亲自确认升级处置.
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const approver = await context.newPage();
    approver.on('pageerror', error => errors.push(error.message));
    await login(approver, approverName, approverPwd);
    await open(approver, id, '需求与治理', '风险与问题');
    // 截图2: 审批人视角出现"确认升级处置"入口.
    await expect(row(approver, title).getByRole('button', { name: '确认升级处置', exact: true })).toBeVisible();
    await shot(approver, 'c09d-2-approver-ack.png');
    await row(approver, title).getByRole('button', { name: '确认升级处置', exact: true }).click();
    const ackForm = modal(approver, '确认问题升级');
    await choose(approver, ackForm, 'decision', '确认升级并责成处置');
    await ackForm.locator('#note').fill('经理层责成立即整改并安排复验, 同意按升级处置.');
    await save(approver, '确认问题升级');

    const acked = await issueRow(page, id, iid);
    expect(acked.escalation_state, '升级已由独立审批人确认').toBe('acknowledged');
    expect(acked.escalation_ack_by, '确认人为独立审批人').toBe(approverId);
    await open(approver, id, '需求与治理', '风险与问题');
    await expect(row(approver, title).getByText('升级已确认')).toBeVisible();
    await expect(row(approver, title).getByRole('button', { name: '确认升级处置', exact: true })).toHaveCount(0);
    await shot(approver, 'c09d-3-acknowledged.png');

    // 门控解除(真实HTTP): 确认后可提交解决并进入待验证.
    const released = await mutateCode(page, id, `/governance/issues/${iid}/resolve`,
      { resolution: '已更换电源模块并完成复验', reviewer_id: approverId, evidence_ids: [evidence.id] });
    expect(released.code, '升级已确认后应可提交解决').toBe(200);
    expect(released.data.result.status).toBe('in_review');
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, title).getByText('升级已确认')).toBeVisible();
    await shot(page, 'c09d-4-resolved.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
    await context.close();
  });

  test('逾期阻断级升级到管理层; major 问题不触发升级可径直提交解决', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const id = await createProject(page, `${suffix}b`, deptId, adminId);
    // 提交解决仍需指定独立审核人(不得为提交人本人), 造一个具备质量审批权限的独立成员.
    const { approverId } = await makeApprover(page, id, suffix, deptId, adminId);
    const evidence = (await mutateData(page, id, '/governance/documents',
      { code: `ISS2-${suffix}`, title: '复验证据', filename: 'v.txt', content: 'major 问题复验证据.' })).result;

    // 逾期阻断级 (到期日足够过去 -> 升级到管理层 steering).
    const overTitle = `安全联锁失效-${suffix}`;
    const over = await createIssue(page, id, { title: overTitle, severity: 'blocker', due: '2020-01-01' });
    expect(over.result.escalation_level, '登记时已逾期升级到管理层').toBe('steering');
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, overTitle).getByText('待升级确认 / steering')).toBeVisible();

    // major 问题不触发升级: 徽标"未触发", 无需独立升级确认即可径直提交解决(升级门控放行, 审核人仍独立).
    const majTitle = `外观轻微划痕-${suffix}`;
    const maj = await createIssue(page, id, { title: majTitle, severity: 'major', due: '2026-12-20' });
    expect(maj.result.escalated, '非阻断级不触发升级').toBeFalsy();
    const mid = maj.result.id;
    const resolved = await mutateCode(page, id, `/governance/issues/${mid}/resolve`,
      { resolution: '抛光处理后复验合格', reviewer_id: approverId, evidence_ids: [evidence.id] });
    expect(resolved.code, '未升级问题无需独立升级确认即可提交解决').toBe(200);
    expect(resolved.data.result.status).toBe('in_review');
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, majTitle).getByText('未触发')).toBeVisible();
    await shot(page, 'c09d-5-steering-and-major.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
