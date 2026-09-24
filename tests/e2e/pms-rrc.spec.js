const { test, expect } = require('@playwright/test');
const path = require('node:path');

// 风险"复审到期倒计时"只读派生列: 风险台账按服务端派生的下次复审剩余天数渲染"到期倒计时"列,
// 逾期红色, 临期(1-3天)金色, 尚远蓝色. 日期用相对服务器当天的偏移(±1 天漂移免疫: 取窗口内 n=2/30/-5),
// 断言按类别(正则匹配文本)与服务端回显(review_due_in_days / review_due_soon / review_overdue)双重确认.
// 与问题/行动台账共用同一 due-countdown-column 派生口径; 复审批准后重算下一次日期的路径由后端用例确定性覆盖.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/rrc');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const dayOffset = n => { const d = new Date(); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };

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
  // 共享库累积用户/任务后长列表被 antd 虚拟滚动裁剪, 可搜索的下拉先按标签过滤再选.
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

// 界面登记一个风险(责任人 admin, 2x3=6 低分不触发升级), 到期日决定复审倒计时档位.
async function createRisk(page, id, { title, due }) {
  await open(page, id, '需求与治理', '风险与问题');
  await drawer(page).getByRole('button', { name: '登记项目风险', exact: true }).click();
  const form = modal(page, '登记项目风险');
  await form.locator('#title').fill(title);
  await form.locator('#probability').fill('2');
  await form.locator('#impact').fill('3');
  await choose(page, form, 'owner_id', 'admin');
  await form.locator('#mitigation').fill('例会持续跟踪并预留纠偏窗口.');
  await form.locator('#due_date').fill(due);
  return save(page, '登记项目风险');
}

async function createProject(page, suffix, deptId, adminId) {
  const project = await api(page, 'POST', '/api/pms/projects', { project_no: `RRC-${suffix}`, name: `复审倒计时验收 / ${suffix}`,
    project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
  return project.project_id;
}

test.describe('风险复审到期倒计时只读派生列浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('三档复审倒计时(临期/尚远/逾期)在风险台账回显并经服务端二次确认', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const id = await createProject(page, suffix, deptId, adminId);

    // 三条风险到期日相对服务器当天分别约 +2 / +30 / -5 天(标题刻意避开"临期"/"逾期"/"剩"子串, 免与倒计时标签冲突).
    const soonTitle = `来料延迟风险-${suffix}`;
    const farTitle = `软件需求待定风险-${suffix}`;
    const overTitle = `供应商产能风险-${suffix}`;
    await createRisk(page, id, { title: soonTitle, due: dayOffset(2) });
    await createRisk(page, id, { title: farTitle, due: dayOffset(30) });
    await createRisk(page, id, { title: overTitle, due: dayOffset(-5) });

    // 截图1: "风险与问题"页签三档"到期倒计时"徽标.
    await open(page, id, '需求与治理', '风险与问题');
    await expect(row(page, soonTitle).getByText(/临期/)).toBeVisible();
    await expect(row(page, farTitle).getByText(/^剩 \d+ 天$/)).toBeVisible();
    await expect(row(page, farTitle).getByText(/临期/)).toHaveCount(0);
    await expect(row(page, overTitle).getByText(/已逾期 \d+ 天/)).toBeVisible();
    await shot(page, 'rrc-1-risk-review-countdown.png');

    // 服务端回显二次确认(读取时派生, 不落库): 剩余天数落在窗口内且临期/逾期布尔一致.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const riskBy = title => gov.risks.find(r => r.title === title);
    const soon = riskBy(soonTitle), far = riskBy(farTitle), over = riskBy(overTitle);
    expect(soon.review_due_in_days).toBeGreaterThanOrEqual(1);
    expect(soon.review_due_in_days).toBeLessThanOrEqual(3);
    expect(soon.review_due_soon).toBe(true);
    expect(soon.review_overdue).toBe(false);
    expect(far.review_due_in_days).toBeGreaterThanOrEqual(28);
    expect(far.review_due_soon).toBe(false);
    expect(far.review_overdue).toBe(false);
    expect(over.review_due_in_days).toBeLessThanOrEqual(-4);
    expect(over.review_due_soon).toBe(false);
    expect(over.review_overdue).toBe(true);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
