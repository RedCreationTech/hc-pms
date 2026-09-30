const { test, expect } = require('@playwright/test');
const path = require('node:path');

// B08 延伸: DQ 台账"必需检查就绪度"只读派生列 (免迁移, 无新命令/新kind/新路由/不门控).
// 既有"检查通过"列按全部清单项计数(dq_passed/dq_total), 看不出签认所真正依赖的"必需项还差几条".
// 本用例核验新派生列只按必需项(required!=false)计就绪度: admin 建项目 -> 真实HTTP登记一个混合清单 DQ(两条必需 R-1/R-2 + 一条可选 O-1) ->
//   初始"必需检查就绪度"红标"必需 0/2 缺 2";
//   真实HTTP填检查: 通过 R-1 + 可选 O-1, R-2 未过 -> 红标"必需 1/2 缺 1"(可选已过不影响就绪缺口), 而"检查通过"已 2/3;
//   真实HTTP填检查: R-1/R-2 全过, 可选 O-1 退回未过 -> 绿标"必需就绪 2/2"(必需全过即就绪), 状态转 ready, 而"检查通过"仍 2/3(可选未过).
// 全程真实HTTP + 真实浏览器渲染 + 真实浏览器截图, 只读派生不改不可变版本、不门控.
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const output = path.resolve(__dirname, '../../reports/dqreq');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const row = (page, text) => drawer(page).locator('tbody tr:visible').filter({ hasText: text }).first();
const dqCard = page => drawer(page).locator('section').filter({ has: page.getByRole('heading', { name: 'DQ 编制与确认', exact: true }) });
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

async function command(page, id, suffix, data = {}) {
  const project = await api(page, 'GET', base(id));
  const result = await api(page, 'POST', base(id) + '/governance' + suffix, { ...data, version: project.version });
  return result.result;
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

async function shotCard(page, card, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message-notice-content')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('B08 DQ 必需检查就绪度只读列浏览器验收', () => {
  test.use({ viewport: { width: 1600, height: 1000 } });
  test.setTimeout(180000);

  test('混合清单 -> 必需全过即"必需就绪"且与可选项无关, 未过显红缺口', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const suffix = serial();
    const options = await api(page, 'GET', '/api/pms/options');
    const deptId = options.depts.find(d => d.dept_name === '研发部门').dept_id;
    const adminId = options.currentUserId;

    const project = await api(page, 'POST', '/api/pms/projects', { project_no: `DQREQ-${suffix}`, name: `DQ必需检查就绪度验收 / ${suffix}`,
      project_type: 'line', manager_id: adminId, dept_id: deptId, start_date: '2026-09-01', end_date: '2026-12-31' });
    const id = project.project_id;

    // 1) 真实HTTP: 登记混合清单 DQ (R-1/R-2 必需, O-1 可选).
    const dqTitle = `必需检查就绪度-${suffix}`;
    const dq = await command(page, id, '/dqs', { code: `DQ-${suffix}`, title: dqTitle, owner_id: adminId,
      checklist: [{ code: 'R-1', title: '必需一', required: true }, { code: 'R-2', title: '必需二', required: true }, { code: 'O-1', title: '可选一', required: false }],
      deliverable_ids: [] });

    // 2) 初始: 全部未过 -> 红标"必需 0/2 缺 2", "检查通过" 0/3.
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(dqCard(page).getByRole('columnheader', { name: '必需检查就绪度', exact: true })).toBeVisible();
    await expect(row(page, dqTitle).getByText('必需 0/2 缺 2', { exact: true })).toBeVisible();
    await expect(row(page, dqTitle).getByText('必需就绪 2/2', { exact: true })).toHaveCount(0);
    await expect(row(page, dqTitle).getByText('0/3', { exact: true })).toBeVisible();
    await shotCard(page, dqCard(page), 'dqreq-1-initial.png');

    const g0 = await api(page, 'GET', base(id) + '/governance');
    const d0 = g0.dqs.find(d => d.id === dq.id);
    expect(d0.dq_required_total, '必需项总数 2').toBe(2);
    expect(d0.dq_required_passed, '必需已通过 0').toBe(0);
    expect(d0.dq_required_missing, '必需缺口 2').toBe(2);
    expect(d0.dq_required_met, '未就绪').toBe(false);

    // 3) 真实HTTP: 通过 R-1 + 可选 O-1, R-2 未过 -> 红标"必需 1/2 缺 1"; 可选已过不影响就绪缺口; "检查通过"已 2/3.
    await command(page, id, `/dqs/${dq.id}/checks`, { results: [
      { code: 'R-1', passed: true, note: '' }, { code: 'R-2', passed: false, note: '' }, { code: 'O-1', passed: true, note: '' }] });
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(row(page, dqTitle).getByText('必需 1/2 缺 1', { exact: true })).toBeVisible();
    await expect(row(page, dqTitle).getByText('2/3', { exact: true })).toBeVisible();
    await shotCard(page, dqCard(page), 'dqreq-2-partial.png');

    const g1 = await api(page, 'GET', base(id) + '/governance');
    const d1 = g1.dqs.find(d => d.id === dq.id);
    expect(d1.dq_required_passed, '必需已通过 1').toBe(1);
    expect(d1.dq_required_missing, '必需缺口 1').toBe(1);
    expect(d1.dq_required_met, '仍不就绪').toBe(false);
    expect(d1.dq_passed, '总通过数含可选项为 2').toBe(2);
    expect(d1.dq_total, '总项数 3').toBe(3);

    // 4) 真实HTTP: R-1/R-2 全过, 可选 O-1 退回未过 -> 绿标"必需就绪 2/2" (必需全过即就绪), 无红缺口; 状态转 ready; "检查通过"仍 2/3 (可选未过).
    await command(page, id, `/dqs/${dq.id}/checks`, { results: [
      { code: 'R-1', passed: true, note: '' }, { code: 'R-2', passed: true, note: '' }, { code: 'O-1', passed: false, note: '' }] });
    await open(page, id, '需求与治理', 'DQ与局部暂停');
    await expect(row(page, dqTitle).getByText('必需就绪 2/2', { exact: true })).toBeVisible();
    await expect(row(page, dqTitle).getByText('缺 1', { exact: true })).toHaveCount(0);
    await expect(row(page, dqTitle).getByText('2/3', { exact: true })).toBeVisible();
    await shotCard(page, dqCard(page), 'dqreq-3-ready.png');

    const g2 = await api(page, 'GET', base(id) + '/governance');
    const d2 = g2.dqs.find(d => d.id === dq.id);
    expect(d2.dq_required_met, '必需全过 -> 就绪 true').toBe(true);
    expect(d2.dq_required_missing, '缺口归零').toBe(0);
    expect(d2.dq_required_passed, '必需通过 2').toBe(2);
    expect(d2.dq_passed, '总通过 2 (可选未过不计就绪)').toBe(2);
    expect(d2.status, '必需全过 -> 状态 ready').toBe('ready');

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});
