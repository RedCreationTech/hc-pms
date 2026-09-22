const { test, expect } = require('@playwright/test');
const path = require('node:path');

// C09 问题责任人转派: 界面转派->新责任人+原因留痕(审计), 并以真实HTTP验证非成员/缺原因被拒.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/c09');
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
  return gov.issues.find(i => i.id === iid);
}

test.describe('C09 问题责任人转派浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('界面转派问题责任人并保留原责任人与原因, 非成员/缺原因经真实HTTP被拒', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const memberName = `iss_${suffix}`;
    await api(page, 'POST', '/api/system/user', { user_name: memberName, nick_name: '接手工程师', password: `E2e!${suffix}`,
      dept_id: deptId, roles: [], posts: [], status: '0', remark: 'C09 转派目标' });
    const memberId = (await api(page, 'GET', '/api/pms/options')).users.find(u => u.user_name === memberName).user_id;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `C09-${suffix}`, name: `问题转派验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await api(page, 'POST', base(id) + '/members', { user_id: memberId, role: 'editor' });

    const issue = (await mutate(page, id, '/governance/issues',
      { title: '接线端子松动', severity: 'major', owner_id: adminId, due_date: '2026-10-01' })).result;
    const iid = issue.id;
    expect(issue.owner_id).toBe(adminId);

    // 真实HTTP拒绝路径: 新责任人非项目成员 400, 缺原因 400.
    const nonMember = await api(page, 'GET', base(id));
    const badVersion = nonMember.version;
    const rejectNonMember = await page.evaluate(async ({ id, iid, v }) => {
      const token = localStorage.getItem('ruoyi_token');
      const r = await fetch(`/api/pms/projects/${id}/governance/issues/${iid}/reassign`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ owner_id: 999999, reason: '转给非成员', version: v }) });
      return (await r.json()).code;
    }, { id, iid, v: badVersion });
    expect(rejectNonMember, '非成员转派应被拒').toBe(400);
    const rejectNoReason = await page.evaluate(async ({ id, iid, v }) => {
      const token = localStorage.getItem('ruoyi_token');
      const r = await fetch(`/api/pms/projects/${id}/governance/issues/${iid}/reassign`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ owner_id: 1, reason: '', version: v }) });
      return (await r.json()).code;
    }, { id, iid, v: badVersion });
    expect(rejectNoReason, '缺原因转派应被拒').toBe(400);

    // 截图1: 治理 -> 风险与问题, 问题行出现"转派"入口.
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, '接线端子松动')).toBeVisible();
    await expect(row(page, '接线端子松动').getByRole('button', { name: '转派', exact: true })).toBeVisible();
    await shot(page, 'c09-1-issue-row.png');

    // 界面转派: 选择新责任人(项目成员)并填写原因.
    await row(page, '接线端子松动').getByRole('button', { name: '转派', exact: true }).click();
    const form = modal(page, '转派问题责任人');
    await choose(page, form, 'owner_id', memberName);
    await form.locator('#reason').fill('原责任人出差, 转派接手工程师跟进.');
    await shot(page, 'c09-2-reassign-dialog.png');
    await save(page, '转派问题责任人');

    // 断言转派结果与审计留痕.
    const after = await issueRow(page, id, iid);
    expect(after.owner_id, '责任人应变更').toBe(memberId);
    expect(after.reassigned_from, '应保留原责任人').toBe(adminId);
    expect(after.reassign_reason).toBe('原责任人出差, 转派接手工程师跟进.');
    expect(after.reassigned_by).toBe(adminId);
    expect(after.status, '转派不改变状态').toBe('open');

    // 截图3: 转派后问题仍在台账且可再次转派.
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, '接线端子松动').getByRole('button', { name: '转派', exact: true })).toBeVisible();
    await shot(page, 'c09-3-after-reassign.png');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
