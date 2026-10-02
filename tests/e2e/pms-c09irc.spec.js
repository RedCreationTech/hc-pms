const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C09 延伸 问题解决方式覆盖度只读派生汇总面板: 按每个问题最新有效版本统计提交解决时声明的六类处置方式
// (已修复/已规避/设计如此/重复/无法复现/不予修复)覆盖情况 (总数/已声明覆盖率/未设定/六类各自计数).
// 只读派生, 不改变问题状态, 不构成门控. 面板断言全部 scope 到面板 <section>, 避免与同页台账"未设定"等文案串扰.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c09irc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) });
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

async function mutateData(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  return api(page, 'POST', base(id) + suffix, { ...data, version: project.version });
}

async function tab(page, name) {
  await drawer(page).getByRole('tab', { name, exact: true }).click();
  await page.waitForLoadState('networkidle');
}

async function open(page, id) {
  await page.goto(`/pms/project?id=${id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await tab(page, '需求与治理');
  await tab(page, '风险与问题');
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

// 面板元素截图: 面板位于抽屉自身滚动容器靠下位置, 直接 scrollIntoView + 元素截图免疫主窗口滚动.
async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const el = panel(page, title);
  await el.scrollIntoViewIfNeeded();
  await expect(el.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await el.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

const sevLabel = { blocker: '阻断', major: '严重', minor: '一般' };

async function createIssue(page, id, { title, severity, due }) {
  await open(page, id);
  await drawer(page).getByRole('button', { name: '登记项目问题', exact: true }).click();
  const form = modal(page, '登记项目问题');
  await form.locator('#title').fill(title);
  await choose(page, form, 'severity', sevLabel[severity]);
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#due_date').fill(due);
  return save(page, '登记项目问题');
}

async function resolveViaUi(page, id, title, { resolution, typeLabel, evidenceCode, approverName }) {
  await open(page, id);
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
  await api(page, 'POST', '/api/system/role', { role_name: `覆盖度审批${suffix}`, role_key: `iss_irc_${suffix}`, 'menu-ids': menuIds });
  const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `iss_irc_${suffix}`).role_id;
  const approverName = `issirc_${suffix}`;
  await api(page, 'POST', '/api/system/user', { user_name: approverName, nick_name: '覆盖度独立审批人', password: `E2e!${suffix}`,
    dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C09 解决方式覆盖度 E2E合成独立审批人' });
  const approverId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === approverName).user_id;
  await api(page, 'POST', base(id) + '/members', { user_id: approverId, role: 'viewer' });
  return { approverName, approverId };
}

async function createProject(page, suffix, deptId, adminId) {
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C09IRC-${suffix}`, name: `问题解决方式覆盖度验收 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
  return project.project_id;
}

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('C09 问题解决方式覆盖度只读面板浏览器验收', () => {
  test.setTimeout(180000);

  test('登记并部分提交解决 -> 覆盖度面板按六类解决方式统计; 再声明一项 -> 覆盖率上升; 全程只读不改状态', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const id = await createProject(page, suffix, deptId, adminId);
    const { approverName } = await makeApprover(page, id, suffix, deptId);
    const evidenceCode = `ISS-IRC-${suffix}`;
    await mutateData(page, id, '/governance/documents',
      { code: evidenceCode, title: '整改复验证据', filename: 'irc.txt', content: '解决方式覆盖度验收证据.' });

    const PT = '问题解决方式覆盖度';
    await open(page, id);
    // 空态: 尚无问题 -> 占位提示.
    await expect(panel(page, PT)).toContainText('暂无项目问题');

    // 问题A 选"设计如此"(by-design), 问题B 选"已修复"(fixed), 问题C 不选方式(未设定), 问题D 未解决(open).
    const tA = `密封面渗漏-${suffix}`;
    await createIssue(page, id, { title: tA, severity: 'major', due: '2026-12-20' });
    await resolveViaUi(page, id, tA, { resolution: '按设计上限使用并加注规程', typeLabel: '设计如此', evidenceCode, approverName });
    const tB = `控制器死区过大-${suffix}`;
    await createIssue(page, id, { title: tB, severity: 'major', due: '2026-12-20' });
    await resolveViaUi(page, id, tB, { resolution: '更换传感器并复测', typeLabel: '已修复', evidenceCode, approverName });
    const tC = `铭牌字体偏小-${suffix}`;
    await createIssue(page, id, { title: tC, severity: 'minor', due: '2026-12-20' });
    await resolveViaUi(page, id, tC, { resolution: '仅文字说明处理', evidenceCode, approverName });
    const tD = `待评估的现场振动异常-${suffix}`;
    await createIssue(page, id, { title: tD, severity: 'minor', due: '2026-12-31' });

    // 覆盖度面板: 总数4 · 已声明50% · 未设定2 · 设计如此·1 · 已修复·1 · 其余0.
    await open(page, id);
    const p = panel(page, PT);
    await expect(p).toContainText('问题总数 4');
    await expect(p).toContainText('已声明解决方式 50%');
    await expect(p).toContainText('未设定 2');
    await expect(p.getByText('设计如此 · 1')).toHaveCount(1);
    await expect(p.getByText('已修复 · 1')).toHaveCount(1);
    await expect(p.getByText('重复 · 0')).toHaveCount(1);
    await panelShot(page, PT, 'c09irc-1-coverage-50.png');

    // 再登记问题E 并提交解决选"重复"(duplicate): 总数5 · 已声明60% · 未设定仍2 · 重复·1.
    const tE = `与既有缺陷重复的报警-${suffix}`;
    await createIssue(page, id, { title: tE, severity: 'major', due: '2026-12-20' });
    await resolveViaUi(page, id, tE, { resolution: '与既有问题重复, 合并跟踪', typeLabel: '重复', evidenceCode, approverName });
    await open(page, id);
    const p2 = panel(page, PT);
    await expect(p2).toContainText('问题总数 5');
    await expect(p2).toContainText('已声明解决方式 60%');
    await expect(p2).toContainText('未设定 2');
    await expect(p2.getByText('重复 · 1')).toHaveCount(1);
    await panelShot(page, PT, 'c09irc-2-coverage-60.png');

    // 真实 HTTP 交叉核验读模型派生值 (面板数据源).
    const gov = await api(page, 'GET', base(id) + '/governance');
    const cov = gov.issue_resolution_coverage;
    expect(cov.total, '读模型问题总数').toBe(5);
    expect(cov.declared, '已声明解决方式数').toBe(3);
    expect(cov.undeclared, '未设定数').toBe(2);
    expect(cov['coverage-pct'], '整数四舍五入覆盖率').toBe(60);
    const byRes = Object.fromEntries(cov['by-resolution'].map(x => [x.resolution, x.count]));
    expect(byRes['by-design']).toBe(1);
    expect(byRes['fixed']).toBe(1);
    expect(byRes['duplicate']).toBe(1);
    expect(byRes['workaround']).toBe(0);
    // 只读派生不改变问题状态: 未解决项仍 open, 已声明项仍 in_review 且带解决方式.
    const rowD = gov.issues.find(r => r.title === tD);
    expect(rowD.status, '读取覆盖度后未解决项状态保持 open').toBe('open');
    expect(rowD.resolution_type == null, '未解决项无解决方式').toBeTruthy();
    const rowA = gov.issues.find(r => r.title === tA);
    expect(rowA.resolution_type).toBe('by-design');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
