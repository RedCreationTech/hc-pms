const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C09 问题可选解决方式枚举: 提交解决验证时可从 已修复/已规避/设计如此/重复/无法复现/不予修复 六类中选择一个"解决方式",
// 台账以彩色标签展示; 不选择则视为未设定 (灰字), 零回归. 非法取值经真实 HTTP 被服务端 s/enum! 拒绝 (400), 状态保持 open (回滚).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c09rt');
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

// 只取返回信封 code, 不做 200 断言, 用于验证门控/校验失败(400)等.
async function callCode(page, method, url, data) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data });
  return response.json();
}

async function mutateCode(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  return callCode(page, 'POST', base(id) + suffix, { ...data, version: project.version });
}

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
  if (typeof text === 'string' && await input.isEditable()) await input.fill(text);
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

const sevLabel = { blocker: '阻断', major: '严重', minor: '一般' };

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

// 通过界面"提交解决证据"表单提交解决, 选择解决方式标签 (null 表示不选, 视为未设定).
async function resolveViaUi(page, id, title, { resolution, typeLabel, evidenceCode, approverName }) {
  await open(page, id, '需求与治理', '风险与问题');
  await row(page, title).getByRole('button', { name: '提交解决证据', exact: true }).click();
  const form = modal(page, '提交问题解决验证');
  await form.locator('#resolution').fill(resolution);
  if (typeLabel) await choose(page, form, 'resolution_type', typeLabel);
  await choose(page, form, 'evidence_ids', evidenceCode);
  await choose(page, form, 'reviewer_id', approverName);
  return save(page, '提交问题解决验证');
}

async function makeApprover(page, id, suffix, deptId) {
  const menus = await api(page, 'GET', '/api/system/menu');
  const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
  const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
  await api(page, 'POST', '/api/system/role', { role_name: `解决方式审批${suffix}`, role_key: `iss_rt_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `iss_rt_${suffix}`).role_id;
  const approverName = `issrt_${suffix}`;
  const approverPwd = `E2e!${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '解决方式独立审批人', password: approverPwd,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C09 解决方式 E2E合成独立审批人' });
  const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;
  await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });
  return { approverName, approverPwd, approverId };
}

async function createProject(page, suffix, deptId, adminId) {
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C09RT-${suffix}`, name: `问题解决方式验收 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
  return project.project_id;
}

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('C09 问题可选解决方式枚举浏览器验收', () => {
  test.setTimeout(180000);

  test('界面提交解决选择解决方式 -> 台账彩色标签; 不选 -> 未设定; 非法取值真实 HTTP 被拒 -> 状态保持 open', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const id = await createProject(page, suffix, deptId, adminId);
    const { approverName, approverId } = await makeApprover(page, id, suffix, deptId);
    const evidenceCode = `ISS-RT-${suffix}`;
    const evidence = (await mutateData(page, id, '/governance/documents',
      { code: evidenceCode, title: '整改复验证据', filename: 'fix.txt', content: '问题解决复验证据已归档.' })).result;

    // 问题A: 界面提交解决并选择"设计如此" -> result.resolution_type=by-design, 台账显示对应标签.
    const titleA = `高温工况密封失效-${suffix}`;
    const a = await createIssue(page, id, { title: titleA, severity: 'major', due: '2026-12-20' });
    const ia = a.result.id;
    const resA = await resolveViaUi(page, id, titleA, { resolution: '按设计上限使用并加注操作规程', typeLabel: '设计如此', evidenceCode, approverName });
    expect(resA.result.status, '提交解决后进入待验证').toBe('in_review');
    expect(resA.result.resolution_type, '界面所选解决方式被持久化').toBe('by-design');
    const echoA = await issueRow(page, id, ia);
    expect(echoA.resolution_type, '读模型回显解决方式').toBe('by-design');
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, titleA).getByText('设计如此')).toBeVisible();
    await shot(page, 'c09rt-1-resolved-with-type.png');

    // 问题B: 界面提交解决但不选择解决方式 -> result 无 resolution_type, 台账显示"未设定" (零回归).
    const titleB = `铭牌字体偏小-${suffix}`;
    const b = await createIssue(page, id, { title: titleB, severity: 'minor', due: '2026-12-20' });
    const ib = b.result.id;
    const resB = await resolveViaUi(page, id, titleB, { resolution: '按客户要求仅文字说明处理', evidenceCode, approverName });
    expect(resB.result.status).toBe('in_review');
    expect(resB.result.resolution_type, '未选择解决方式则不写入').toBeFalsy();
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, titleB).getByText('未设定')).toBeVisible();
    await shot(page, 'c09rt-2-resolved-unset.png');

    // 问题C: 真实 HTTP 提交非法解决方式 -> s/enum! 400, 状态保持 open (事务回滚, 不进入 in_review).
    const titleC = `接口文档版本号缺失-${suffix}`;
    const c = await createIssue(page, id, { title: titleC, severity: 'major', due: '2026-12-20' });
    const ic = c.result.id;
    const bad = await mutateCode(page, id, `/governance/issues/${ic}/resolve`,
      { resolution: '尝试非法取值', resolution_type: 'not-a-real-type', reviewer_id: approverId, evidence_ids: [evidence.id] });
    expect(bad.code, '非法解决方式取值应被服务端拒绝').toBe(400);
    const afterBad = await issueRow(page, id, ic);
    expect(afterBad.status, '非法提交被拒后状态保持 open').toBe('open');
    // 合法取值经真实 HTTP 亦可提交 (覆盖枚举全集之一: 重复 duplicate).
    const okDuplicate = await mutateCode(page, id, `/governance/issues/${ic}/resolve`,
      { resolution: '与既有问题重复', resolution_type: 'duplicate', reviewer_id: approverId, evidence_ids: [evidence.id] });
    expect(okDuplicate.code, '合法解决方式应被接受').toBe(200);
    expect(okDuplicate.data.result.resolution_type).toBe('duplicate');
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, titleC).locator('.ant-tag', { hasText: '重复' })).toBeVisible();
    await shot(page, 'c09rt-3-illegal-400-then-duplicate.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
