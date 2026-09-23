const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H18b 受控撤销作废 (restore) 浏览器端到端验收:
// 覆盖 需求/证据文档/干系人 三类已作废记录的"恢复"入口 -> 填写恢复原因 -> 受控命令:
//   处于 discarded 的最新版本 -> 恢复到作废前状态 (文档回到"已登记", 干系人回到"有效", 需求回到 registered),
//   恢复后作废入口重新出现, 恢复入口消失; 非作废记录不出现恢复入口;
//   恢复保留可追溯审计 (workflow_history 追加 restored 项, 记 restore_reason/restored_by/restored_on).
// 复用 H18 的治理类型与命令框架, restore 免迁移 (discarded 状态已由 202609220011 放开).
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h18b');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const discardBtn = page => page.getByRole('button', { name: /^作\s*废$/ });
const restoreBtn = page => page.getByRole('button', { name: /^恢\s*复$/ });

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

async function version(page, id) {
  const project = await api(page, 'GET', base(id));
  return project.version;
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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

// 点击某行的"作废"按钮并成功作废.
async function discardRow(page, rowText, title, reason) {
  await row(page, rowText).getByRole('button', { name: /^作\s*废$/ }).click();
  const form = modal(page, title);
  await expect(form).toBeVisible();
  await form.locator('#reason').fill(reason);
  const response = page.waitForResponse(r => r.url().includes('/discard') && r.request().method() === 'POST');
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
}

// 点击某行的"恢复"按钮并成功恢复.
async function restoreRow(page, rowText, title, reason) {
  await row(page, rowText).getByRole('button', { name: /^恢\s*复$/ }).click();
  const form = modal(page, title);
  await expect(form).toBeVisible();
  await expect(form.getByText('恢复不是新建')).toBeVisible();
  await form.locator('#reason').fill(reason);
  const response = page.waitForResponse(r => r.url().includes('/restore') && r.request().method() === 'POST');
  await form.getByRole('button', { name: /^保\s*存$/ }).click();
  const body = await (await response).json();
  expect(body.code, `${title}: ${body.msg}`).toBe(200);
  await expect(form).toBeHidden();
  await page.waitForLoadState('networkidle');
  return body.data;
}

test.describe('H18b 治理记录受控撤销作废浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('证据文档: 作废后显示"恢复"入口, 恢复回到"已登记"且入口翻转', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H18B-D-${suffix}`, name: `撤销作废验收-文档 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const doc = (await api(page, 'POST', base(id) + '/governance/documents', { code: `DOC-${suffix}`, title: `待恢复文档 ${suffix}`,
      filename: 'doc.txt', content: '这是一份会被作废再恢复的证据\n', version: await version(page, id) })).result;

    await open(page, id, '需求与治理', '证据版本');
    await expect(row(page, `DOC-${suffix}`)).toContainText('已登记');
    await expect(row(page, `DOC-${suffix}`).getByRole('button', { name: /^恢\s*复$/ })).toHaveCount(0);

    // 作废 -> "已作废", 作废入口消失, 恢复入口出现.
    await discardRow(page, `DOC-${suffix}`, '作废证据文档', '重复上传先作废');
    await expect(row(page, `DOC-${suffix}`)).toContainText('已作废');
    await expect(row(page, `DOC-${suffix}`).getByRole('button', { name: /^作\s*废$/ })).toHaveCount(0);
    await expect(row(page, `DOC-${suffix}`).getByRole('button', { name: /^恢\s*复$/ })).toHaveCount(1);
    await shot(page, 'h18b-1-document-discarded.png');

    // 恢复 -> 回到"已登记", 恢复入口消失, 作废入口重新出现.
    await restoreRow(page, `DOC-${suffix}`, '恢复证据文档', '误操作恢复归档');
    await expect(row(page, `DOC-${suffix}`)).toContainText('已登记');
    await expect(row(page, `DOC-${suffix}`).getByRole('button', { name: /^恢\s*复$/ })).toHaveCount(0);
    await expect(row(page, `DOC-${suffix}`).getByRole('button', { name: /^作\s*废$/ })).toHaveCount(1);
    await shot(page, 'h18b-2-document-restored.png');

    // 服务端二次确认: 状态回到 registered, 审计含 discarded + restored, 恢复字段齐全.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const after = gov.documents.find(d => d.code === `DOC-${suffix}`);
    expect(after.status).toBe('registered');
    expect(after.restore_reason).toBe('误操作恢复归档');
    expect(after.restored_by).toBe(adminId);
    expect(after.workflow_history.map(h => h.action)).toEqual(['discarded', 'restored']);
    expect(after.workflow_history[1].restored_to).toBe('registered');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });

  test('需求与干系人: 已作废记录可恢复到作废前状态', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H18B-R-${suffix}`, name: `撤销作废验收-需求干系人 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    const req = (await api(page, 'POST', base(id) + '/governance/requirements', { code: `URS-${suffix}`, text: `待恢复需求 ${suffix}`,
      category: '功能', priority: 'required', owner_id: adminId, version: await version(page, id) })).result;
    const sh = (await api(page, 'POST', base(id) + '/governance/stakeholders', { code: `SH-${suffix}`, name: '临时干系人',
      role: '评审', category: 'internal', interest: 'low', influence: 'low', owner_id: adminId, version: await version(page, id) })).result;

    // 需求: 作废后恢复入口翻转, 恢复回 registered (需求台账无状态列, 以入口翻转 + HTTP 状态为准).
    await open(page, id, '需求与治理', 'URS与追踪');
    await discardRow(page, `URS-${suffix}`, '作废URS需求', '先作废');
    await expect(row(page, `URS-${suffix}`).getByRole('button', { name: /^恢\s*复$/ })).toHaveCount(1);
    await expect(row(page, `URS-${suffix}`).getByRole('button', { name: /^作\s*废$/ })).toHaveCount(0);
    await restoreRow(page, `URS-${suffix}`, '恢复URS需求', '需求重新纳入');
    await expect(row(page, `URS-${suffix}`).getByRole('button', { name: /^恢\s*复$/ })).toHaveCount(0);
    await expect(row(page, `URS-${suffix}`).getByRole('button', { name: /^作\s*废$/ })).toHaveCount(1);

    // 干系人: 作废 -> "已作废", 恢复 -> "有效".
    await open(page, id, '需求与治理', '干系人与沟通');
    await discardRow(page, `SH-${suffix}`, '作废干系人', '人员退出项目');
    await expect(row(page, `SH-${suffix}`)).toContainText('已作废');
    await shot(page, 'h18b-3-stakeholder-discarded.png');
    await restoreRow(page, `SH-${suffix}`, '恢复干系人', '人员回归项目');
    await expect(row(page, `SH-${suffix}`)).toContainText('有效');
    await shot(page, 'h18b-4-stakeholder-restored.png');

    const gov = await api(page, 'GET', base(id) + '/governance');
    expect(gov.requirements.find(r => r.code === `URS-${suffix}`).status).toBe('registered');
    expect(gov.stakeholders.find(s => s.code === `SH-${suffix}`).status).toBe('active');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
