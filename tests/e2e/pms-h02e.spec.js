const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H02 RACI职责负载只读洞察: 界面为某干系人在多个活动上指派"执行(R)" ->
// "RACI职责矩阵"台账"R职责负载"列按人汇总其被指派的R活动数(执行 R x N) ->
// 达到阈值(3)时追加"职责过载"红标, 提醒责任集中; 负责(A)/咨询(C)/知会(I)不计入R负载.
// 复用既有 raci 类型与只读读模型, 免迁移.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/h02e');
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

// 界面指派一条RACI职责: 活动文本唯一, 干系人按编号下拉, 职责按中文标签选择.
async function assignRaci(page, activity, stakeholderCode, respLabel) {
  await drawer(page).getByRole('button', { name: '指派RACI职责', exact: true }).click();
  const form = modal(page, '指派RACI职责');
  await form.locator('#activity').fill(activity);
  await choose(page, form, 'stakeholder_id', stakeholderCode);
  await choose(page, form, 'responsibility', respLabel);
  await save(page, '指派RACI职责');
}

test.describe('H02 RACI职责负载与过载浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('同一干系人承担多条执行R -> 负载计数 + 达到阈值过载预警', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `H02E-${suffix}`, name: `RACI职责负载验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // API 基准: 两名有效干系人, 供界面RACI下拉选用(需先存在再打开工作台).
    const shR = (await api(page, 'POST', base(id) + '/governance/stakeholders', { code: `SH-R-${suffix}`, name: '首席工程师', role: '装配执行',
      category: 'internal', interest: 'high', influence: 'high', owner_id: adminId, version: await version(page, id) })).result;
    const shO = (await api(page, 'POST', base(id) + '/governance/stakeholders', { code: `SH-O-${suffix}`, name: '文控专员', role: '文档归档',
      category: 'internal', interest: 'medium', influence: 'low', owner_id: adminId, version: await version(page, id) })).result;

    await open(page, id, '需求与治理', '干系人与沟通');
    // SH-R 在三个活动上承担执行(R), 累计负载达到阈值3.
    await assignRaci(page, `机械装配-${suffix}`, shR.code, '执行 R');
    await assignRaci(page, `出厂测试-${suffix}`, shR.code, '执行 R');
    await assignRaci(page, `现场验证-${suffix}`, shR.code, '执行 R');
    // SH-O 仅承担1条执行(R)与1条负责(A), 不触发过载; A 不计入R负载.
    await assignRaci(page, `文件归档-${suffix}`, shO.code, '执行 R');
    await assignRaci(page, `需求评审-${suffix}`, shO.code, '负责 A');

    await open(page, id, '需求与治理', '干系人与沟通');
    await expect(row(page, `机械装配-${suffix}`).getByText('执行 R x 3')).toBeVisible();
    await expect(row(page, `机械装配-${suffix}`).getByText('职责过载')).toBeVisible();
    await expect(row(page, `出厂测试-${suffix}`).getByText('执行 R x 3')).toBeVisible();
    await expect(row(page, `文件归档-${suffix}`).getByText('执行 R x 1')).toBeVisible();
    await expect(row(page, `文件归档-${suffix}`).getByText('职责过载')).toHaveCount(0);
    // SH-O 的"负责(A)"行: 该干系人R负载为1, A不额外计入, 也不过载.
    await expect(row(page, `需求评审-${suffix}`).getByText('执行 R x 1')).toBeVisible();
    await expect(row(page, `需求评审-${suffix}`).getByText('职责过载')).toHaveCount(0);
    await shot(page, 'h02e-1-rac-load.png');

    // 服务端只读读模型二次确认.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const loadOf = activity => gov.raci.find(r => r.activity === activity).raci_r_load;
    expect(loadOf(`机械装配-${suffix}`)).toBe(3);
    expect(loadOf(`现场验证-${suffix}`)).toBe(3);
    expect(loadOf(`文件归档-${suffix}`)).toBe(1);
    expect(loadOf(`需求评审-${suffix}`), '负责A行仍显示该干系人R负载1').toBe(1);
    expect(gov.raci.find(r => r.activity === `机械装配-${suffix}`).raci_overloaded).toBe(true);
    expect(gov.raci.find(r => r.activity === `文件归档-${suffix}`).raci_overloaded).toBe(false);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
