const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H09 延伸: 变更量化影响数值窗口只读派生 (免迁移, 无新命令/新kind/新路由).
// "需求与治理 -> 变更控制" 在数值分档分布之后新增只读"变更量化影响数值窗口"面板: 按每个变更最新有效版本(s/latest, 修订链只计最新版)
// 给出两维已量化数值的最大值与平均值 (未申报该维不计入分母), 以及已量化/纯文字/高影响数.
// 与分档分布互补: 分档看计数落在哪一档, 本项看数值本身有多大.
// 造 工期12+成本10万 / 工期5 仅工期 / 仅文字 / 成本500.50 -> 期望 总数4 / 已量化3 / 纯文字1 / 工期最大12 / 工期平均9 / 成本最大100000 元 / 成本平均 50250.25 元 / 高影响1.
// 再给工期12那条出修订撤销量化 -> 窗口回落 (工期最大5/平均5, 成本回落到仅剩的500.50, 高影响0). 空态单独截图.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/ciwn');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const panel = (page, title) => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: title, exact: true }) }).first();
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });
const base = id => `/api/pms/projects/${id}`;
const TITLE = '变更量化影响数值窗口';

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

async function registerChange(page, { title, days, cost }) {
  await drawer(page).getByRole('button', { name: '提出项目变更', exact: true }).click();
  const form = modal(page, '提出项目变更');
  await form.locator('#title').fill(title);
  await form.locator('#reason').fill('合同范围调整');
  await form.locator('#scope_impact').fill('新增两台设备');
  await form.locator('#schedule_impact').fill('工期相应延长');
  await form.locator('#cost_impact').fill('需要重新估价');
  await form.locator('#quality_impact').fill('追加联调测试');
  await form.locator('#resource_impact').fill('追加一名工程师');
  if (days != null) await form.locator('#schedule_impact_days').fill(String(days));
  if (cost != null) await form.locator('#cost_impact_amount').fill(String(cost));
  return (await save(page, '提出项目变更')).result;
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

async function panelShot(page, title, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  const card = panel(page, title);
  await expect(card.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.use({ viewport: { width: 1600, height: 1000 } });

test.describe('变更量化影响数值窗口只读派生浏览器验收', () => {
  test.setTimeout(240000);

  test('数值窗口按最新有效版本给出两维极值与均值, 服务端同源回显, 修订撤销量化后回落', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;
    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `CIWN-${suffix}`, name: `变更量化影响数值窗口验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;
    await open(page, id, '需求与治理', '变更控制');

    // 1) 界面登记四条: 两维皆报(12天/10万) / 仅工期5天 / 仅文字 / 仅成本500.50.
    const c1 = await registerChange(page, { title: `两维高影响变更-${suffix}`, days: 12, cost: '100000' });
    await registerChange(page, { title: `仅工期变更-${suffix}`, days: 5 });
    const c3 = await registerChange(page, { title: `仅文字描述变更-${suffix}` });
    await registerChange(page, { title: `仅小成本变更-${suffix}`, cost: '500.50' });
    expect(c1.cost_impact_amount).toBe('100000.00');
    expect(c3.schedule_impact_days == null && c3.cost_impact_amount == null).toBeTruthy();

    // 2) 服务端只读派生: 总数4 / 已量化3 / 纯文字1 / 工期 max12 avg9 / 成本 max100000.00 avg50250.25 / 高影响1.
    const gov = await api(page, 'GET', base(id) + '/governance');
    const w = gov.change_impact_window;
    expect(w.available).toBe(true);
    expect(w.total).toBe(4);
    expect(w.quantified).toBe(3);
    expect(w.unquantified).toBe(1);
    expect(w['max-schedule-days']).toBe(12);
    expect(w['avg-schedule-days']).toBe(9);
    expect(w['max-cost-minor']).toBe(10000000);
    expect(w['avg-cost-minor']).toBe(5025025);
    expect(w['high-impact']).toBe(1);

    // 3) 面板可见且与服务端同源.
    const winPanel = panel(page, TITLE);
    await expect(winPanel.getByText('变更总数 4', { exact: true })).toBeVisible();
    await expect(winPanel.getByText('已量化 3', { exact: true })).toBeVisible();
    await expect(winPanel.getByText('纯文字 1', { exact: true })).toBeVisible();
    await expect(winPanel.getByText('高影响 1', { exact: true })).toBeVisible();
    await expect(winPanel.getByText('工期最大 12 天', { exact: true })).toBeVisible();
    await expect(winPanel.getByText('工期平均 9 天', { exact: true })).toBeVisible();
    await expect(winPanel.getByText('成本最大 100000.00 元', { exact: true })).toBeVisible();
    await expect(winPanel.getByText('成本平均 50250.25 元', { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'ciwn-1-panel.png');
    await shot(page, 'ciwn-2-page.png');

    // 4) 给两维那条出修订撤销量化 -> 最新版不计入, 窗口回落 (工期 max5, 成本未量化, 高影响0).
    const pv = (await api(page, 'GET', base(id))).version;
    await api(page, 'POST', base(id) + `/governance/changes/${c1.id}/revisions`, { title: `两维高影响变更(撤销量化)-${suffix}`,
      reason: '合同范围调整', scope_impact: '新增两台设备', schedule_impact: '工期相应延长', cost_impact: '需要重新估价',
      quality_impact: '追加联调测试', resource_impact: '追加一名工程师', version: pv });
    await open(page, id, '需求与治理', '变更控制');
    const winPanel2 = panel(page, TITLE);
    await expect(winPanel2.getByText('变更总数 4', { exact: true })).toBeVisible();
    await expect(winPanel2.getByText('已量化 2', { exact: true })).toBeVisible();
    await expect(winPanel2.getByText('纯文字 2', { exact: true })).toBeVisible();
    await expect(winPanel2.getByText('高影响 0', { exact: true })).toBeVisible();
    await expect(winPanel2.getByText('工期最大 5 天', { exact: true })).toBeVisible();
    // 撤销量化的只是两维那条; 仅小成本 500.50 仍已量化, 故成本窗口回落到它.
    await expect(winPanel2.getByText('成本最大 500.50 元', { exact: true })).toBeVisible();
    await expect(winPanel2.getByText('工期平均 5 天', { exact: true })).toBeVisible();
    const gov2 = await api(page, 'GET', base(id) + '/governance');
    expect(gov2.change_impact_window['max-schedule-days']).toBe(5);
    expect(gov2.change_impact_window['max-cost-minor']).toBe(50050);
    expect(gov2.change_impact_window['high-impact']).toBe(0);

    // 5) 空态: 新项目无变更时面板提示暂无变更.
    const empty = await api(page, 'POST', '/api/pms/projects', { project_no: `CIWN0-${suffix}`, name: `数值窗口空态 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    await open(page, empty.project_id, '需求与治理', '变更控制');
    const emptyPanel = panel(page, TITLE);
    await expect(emptyPanel.getByText('暂无项目变更, 登记变更后可在此查看量化影响数值窗口.', { exact: true })).toBeVisible();
    await panelShot(page, TITLE, 'ciwn-3-empty.png');
    const emptyGov = await api(page, 'GET', base(empty.project_id) + '/governance');
    expect(emptyGov.change_impact_window.available).toBe(false);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});