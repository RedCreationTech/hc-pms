const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C06 延伸: 证据发布覆盖度只读派生洞察 (免迁移, 无新命令/新kind).
// 界面登记若干证据文档, 覆盖已发布/待审/已驳回/未提交四种发布生命周期状态 ->
// "证据版本"页签新增只读"证据发布覆盖度"面板: 按每个文档编号最新版本聚合各状态计数 + 已发布率百分比;
// 修订不重复计数(总数不变而覆盖率随最新版本回退), 受控作废的最新版本从分母剔除.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c06b');
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
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').filter({ has: page.locator(`[id="${list}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click();
  await input.press('Escape');
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

async function registerDoc(page, id, { code, title }) {
  await drawer(page).getByRole('button', { name: '登记证据文档', exact: true }).click();
  const form = modal(page, '登记证据文档');
  await form.locator('#code').fill(code);
  await form.locator('#title').fill(title);
  await form.locator('#filename').fill(`${code.toLowerCase()}.txt`);
  await form.locator('#content').fill(`${title}: 设计评审与验证结论已闭环, 内容真实可校验.`);
  return (await save(page, '登记证据文档')).result;
}

async function submitDoc(page, id, code, reviewerName) {
  await row(page, code).getByRole('button', { name: '提交发布', exact: true }).click();
  const form = modal(page, '提交文档发布审批');
  await choose(page, form, 'reviewer_id', reviewerName);
  await save(page, '提交文档发布审批');
}

test.describe('C06 延伸 证据发布覆盖度只读洞察浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('发布覆盖度面板按最新版本聚合四种生命周期状态, 修订不重复计数, 作废从分母剔除', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    // 合成一个具备质量审批权限的只读审核角色和独立审核用户, 不改动任何系统内置账号.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `发布覆盖审核${suffix}`, role_key: `relcov_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `relcov_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const reviewerName = `rc_${suffix}`;
    const reviewerPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: reviewerName, nick_name: '发布覆盖审核人', password: reviewerPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C06b E2E合成审核人' });
    const reviewerId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === reviewerName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RCOV-${suffix}`, name: `证据发布覆盖度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: reviewerId, role: 'viewer' });

    const codeA = `RC-A-${suffix}`, codeB = `RC-B-${suffix}`, codeC = `RC-C-${suffix}`, codeD = `RC-D-${suffix}`;

    // 1) 界面登记四份证据文档, 均初始为"已登记".
    await open(page, id, '需求与治理', '证据版本');
    const docA = await registerDoc(page, id, { code: codeA, title: '架构设计评审记录' });
    const docB = await registerDoc(page, id, { code: codeB, title: '接口验证测试报告' });
    const docC = await registerDoc(page, id, { code: codeC, title: '环境部署验收记录' });
    const docD = await registerDoc(page, id, { code: codeD, title: '用户手册评审记录' });

    // 2) A/B/C 提交发布(独立审核人), D 保持未提交.
    await open(page, id, '需求与治理', '证据版本');
    await submitDoc(page, id, codeA, reviewerName);
    await submitDoc(page, id, codeB, reviewerName);
    await submitDoc(page, id, codeC, reviewerName);

    // 3) 独立审核人第二上下文: 批准 A -> 已发布, 驳回 C -> 已驳回; B 停留在待审.
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const reviewer = await context.newPage();
    reviewer.on('pageerror', error => errors.push(error.message));
    await login(reviewer, reviewerName, reviewerPwd);
    await open(reviewer, id, '需求与治理', '证据版本');
    await row(reviewer, codeA).getByRole('button', { name: '批准发布', exact: true }).click();
    let decForm = modal(reviewer, '正式签发发布');
    await decForm.locator('#reason').fill('已独立核对架构评审文档内容与摘要, 同意正式签发发布.');
    await save(reviewer, '正式签发发布');
    await row(reviewer, codeC).getByRole('button', { name: '驳回', exact: true }).click();
    decForm = modal(reviewer, '驳回文档发布');
    await decForm.locator('#reason').fill('环境部署证据不足, 暂不予发布, 请补充后重提.');
    await save(reviewer, '驳回文档发布');
    await context.close();

    // 4) 发布覆盖度面板: 总数4, 已发布1 -> 25%, 待审1, 未提交1, 已驳回1.
    await open(page, id, '需求与治理', '证据版本');
    await expect(drawer(page).getByText('证据发布覆盖度', { exact: true })).toBeVisible();
    await expect(drawer(page).getByText(/覆盖文档\s*4/)).toBeVisible();
    await expect(drawer(page).getByText(/已发布率\s*25%/)).toBeVisible();
    await expect(drawer(page).getByText(/已发布\s*1/)).toBeVisible();
    await expect(drawer(page).getByText(/待审\s*1/)).toBeVisible();
    await expect(drawer(page).getByText(/未提交\s*1/)).toBeVisible();
    await expect(drawer(page).getByText(/已驳回\s*1/)).toBeVisible();
    await shot(page, 'c06b-1-release-coverage-partial.png');

    // 5) 真实HTTP读模型回显 release_coverage.
    const ws = await api(page, 'GET', base(id) + '/governance');
    expect(ws.release_coverage.total, '覆盖度分母=最新版本证据数').toBe(4);
    expect(ws.release_coverage.approved, '已发布计数').toBe(1);
    expect(ws.release_coverage['in-review'], '待审计数').toBe(1);
    expect(ws.release_coverage.rejected, '已驳回计数').toBe(1);
    expect(ws.release_coverage.registered, '未提交计数').toBe(1);
    expect(ws.release_coverage['released-pct'], '已发布率').toBe(25);

    // 6) 给已发布的 A 建新版本 -> A 最新版本回退为未提交: 总数不变(按code去重), 已发布率降为0, 未提交变2.
    await open(page, id, '需求与治理', '证据版本');
    await row(page, codeA).getByRole('button', { name: '新版本', exact: true }).click();
    const revForm = modal(page, '新增证据文档版本');
    await revForm.locator('#code').fill(codeA);
    await revForm.locator('#title').fill('架构设计评审记录(修订)');
    await revForm.locator('#filename').fill('rc-a-v2.txt');
    await revForm.locator('#content').fill('架构设计评审记录修订: 补充接口容错与回退策略说明.');
    const rev = (await save(page, '新增证据文档版本')).result;
    expect(rev.revision, '新版本号为2').toBe(2);
    await open(page, id, '需求与治理', '证据版本');
    await expect(drawer(page).getByText(/覆盖文档\s*4/)).toBeVisible();
    await expect(drawer(page).getByText(/已发布率\s*0%/)).toBeVisible();
    await expect(drawer(page).getByText(/未提交\s*2/)).toBeVisible();
    await expect(drawer(page).getByText(/待审\s*1/)).toBeVisible();
    await shot(page, 'c06b-2-revision-dedup.png');

    // 7) 受控作废处于已驳回(可作废状态)的 C -> 从覆盖度分母剔除: 总数3, 已驳回标签消失, 待审仍在.
    const pv = (await api(page, 'GET', base(id))).version;
    await api(page, 'POST', base(id) + `/governance/documents/${docC.id}/discard`, { reason: '与接口验证证据重复, 受控作废', version: pv });
    const ws2 = await api(page, 'GET', base(id) + '/governance');
    expect(ws2.release_coverage.total, '作废后分母=3').toBe(3);
    expect(ws2.release_coverage.rejected, '作废后已驳回计数=0').toBe(0);
    expect(ws2.release_coverage['in-review'], '待审的 B 仍在').toBe(1);
    await open(page, id, '需求与治理', '证据版本');
    await expect(drawer(page).getByText(/覆盖文档\s*3/)).toBeVisible();
    await expect(drawer(page).getByText(/已驳回\s*\d/)).toHaveCount(0);
    await expect(drawer(page).getByText(/待审\s*1/)).toBeVisible();
    await shot(page, 'c06b-3-discarded-excluded.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
