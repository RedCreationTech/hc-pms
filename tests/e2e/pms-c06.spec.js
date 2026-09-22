const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C06 文档独立发布审批与正式签发: admin 界面登记证据文档 -> 提交发布选独立审核人 ->
// 审核人第二浏览器上下文批准发布 -> 发布状态"已发布" -> admin 建新版本回到"已登记"且旧批准版本不漂移;
// 真实 HTTP GET 回显 released_by 与 approved 状态.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c06');
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

test.describe('C06 文档独立发布审批浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('登记 -> 提交发布 -> 独立审核人批准 -> 新修订不漂移旧批准', async ({ page, browser }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    // 合成一个具备质量审批权限的只读审核角色和独立审核用户, 不改动任何系统内置账号.
    const menus = await api(page, 'GET', '/api/system/menu');
    const perms = new Set(['pms:project:list', 'pms:project:query', 'pms:quality:approve']);
    const menuIds = menus.filter(m => perms.has(m.perms) || m.path === 'pms').map(m => m.menu_id);
    await api(page, 'POST', '/api/system/role', { role_name: `文档发布审核${suffix}`, role_key: `doc_rel_${suffix}`, 'menu-ids': menuIds });
    const roleId = (await api(page, 'GET', '/api/system/role')).find(r => r.role_key === `doc_rel_${suffix}`).role_id;

    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const reviewerName = `dr_${suffix}`;
    const reviewerPwd = `E2e!${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: reviewerName, nick_name: '文档发布审核人', password: reviewerPwd,
      dept_id: deptId, roles: [roleId], posts: [], status: '0', remark: 'C06 E2E合成审核人' });
    const reviewerId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === reviewerName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C06-${suffix}`, name: `文档发布审批验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: reviewerId, role: 'viewer' });

    const code = `REL-${suffix}`;
    // 1) admin 界面登记证据文档, 初始发布状态"已登记".
    await open(page, id, '需求与治理', '证据版本');
    await drawer(page).getByRole('button', { name: '登记证据文档', exact: true }).click();
    let form = modal(page, '登记证据文档');
    await form.locator('#code').fill(code);
    await form.locator('#title').fill('设计评审记录');
    await form.locator('#filename').fill('design-review.txt');
    await form.locator('#content').fill('设计评审结论: 架构与接口定义满足需求, 遗留项已闭环.');
    const doc = (await save(page, '登记证据文档')).result;
    const docId = doc.id;
    await expect(row(page, code)).toBeVisible();
    await expect(row(page, code).getByText('已登记')).toBeVisible();
    await shot(page, 'c06-1-registered.png');

    // 2) admin 提交发布, 选择独立审核人 -> 待发布审批.
    await row(page, code).getByRole('button', { name: '提交发布', exact: true }).click();
    form = modal(page, '提交文档发布审批');
    await choose(page, form, 'reviewer_id', reviewerName);
    await save(page, '提交文档发布审批');
    await open(page, id, '需求与治理', '证据版本');
    await expect(row(page, code).getByText('待发布审批')).toBeVisible();
    // admin 非指定审核人, 不应出现批准入口.
    await expect(row(page, code).getByRole('button', { name: '批准发布', exact: true })).toHaveCount(0);
    await shot(page, 'c06-2-in-review.png');

    // 3) 独立审核人第二上下文登录并批准发布 -> 已发布.
    const context = await browser.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:3000', viewport: { width: 1600, height: 1000 } });
    const reviewer = await context.newPage();
    reviewer.on('pageerror', error => errors.push(error.message));
    await login(reviewer, reviewerName, reviewerPwd);
    await open(reviewer, id, '需求与治理', '证据版本');
    await expect(row(reviewer, code).getByRole('button', { name: '批准发布', exact: true })).toBeVisible();
    await row(reviewer, code).getByRole('button', { name: '批准发布', exact: true }).click();
    const decForm = modal(reviewer, '正式签发发布');
    await decForm.locator('#reason').fill('已独立核对文档内容与摘要, 同意正式签发发布.');
    await save(reviewer, '正式签发发布');
    await open(reviewer, id, '需求与治理', '证据版本');
    await expect(row(reviewer, code).getByText('已发布')).toBeVisible();
    await shot(reviewer, 'c06-3-released.png');
    await context.close();

    // 4) 真实 HTTP 回显: 文档 approved 且 released_by = 审核人.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const released = gov.documents.find(d => d.id === docId);
    expect(released.status).toBe('approved');
    expect(released.released_by).toBe(reviewerId);

    // 5) admin 建新版本 -> V2"已登记", 旧 V1 仍"已发布"不漂移.
    await open(page, id, '需求与治理', '证据版本');
    await row(page, code).getByRole('button', { name: '新版本', exact: true }).click();
    form = modal(page, '新增证据文档版本');
    await form.locator('#code').fill(code);
    await form.locator('#title').fill('设计评审记录(修订)');
    await form.locator('#filename').fill('design-review-v2.txt');
    await form.locator('#content').fill('设计评审结论修订: 补充接口容错说明.');
    const v2 = (await save(page, '新增证据文档版本')).result;
    expect(v2.revision).toBe(2);
    expect(v2.status).toBe('registered');
    await open(page, id, '需求与治理', '证据版本');
    const gov2 = await api(page, 'GET', base(id) + '/governance');
    const old = gov2.documents.find(d => d.id === docId);
    const latest = gov2.documents.find(d => d.id === v2.id);
    expect(old.status, '旧批准版本保持已发布不漂移').toBe('approved');
    expect(latest.status, '新修订尚未发布').toBe('registered');
    await expect(row(page, 'design-review-v2.txt')).toBeVisible();
    await expect(row(page, 'design-review-v2.txt').getByText('已登记')).toBeVisible();
    await shot(page, 'c06-4-revision-no-drift.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
