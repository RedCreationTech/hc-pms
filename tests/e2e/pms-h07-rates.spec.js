const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H 节: 有效期费率表 (H07) 的真实浏览器验收.
// 流程: 模板与规则 > 费率表 建立 (角色费率 + 人员费率) -> 发布 ->
// 有已批准工时的项目在 项目费用 > 实际工时 看到按生效费率折算的人工成本面板 (人员费率优先, 生效前分钟未覆盖).
// 派生数值本身由 pms_finance_test (approved-times-derive-labor-cost-with-effective-rates) 精确断言, 本用例验证界面闭环.
const output = path.resolve(__dirname, '../../reports/h07-rates');
const drawer = page => page.getByRole('dialog').filter({ has: page.getByRole('tab', { name: '项目概况', exact: true }) });
const modal = (page, title) => page.getByRole('dialog', { name: title, exact: true });

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

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-message, .ant-message-notice, .ant-message-list')).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test('费率表建立发布与人工成本面板按生效费率折算', async ({ page }) => {
  test.setTimeout(120000);
  await login(page);

  // 清场: 退役既有费率表, 保证本用例可重复运行并产出确定的费率行.
  const prior = (await api(page, 'GET', '/api/pms/config/rate-table')).rows;
  for (const row of prior.filter(r => r.status !== 'retired')) {
    await api(page, 'POST', `/api/pms/config/rate-table/${row.id}/retire`, { reason: 'E2E 重跑清理' });
  }

  // 1. 模板与规则 > 费率表: 建立角色费率 + 人员费率.
  await page.goto('/pms/config');
  await page.getByRole('tab', { name: '费率表', exact: true }).click();
  await expect(page.getByText('有效期费率表')).toBeVisible();
  await page.getByRole('button', { name: '建立费率表' }).click();
  const dlg = modal(page, '建立费率表');
  await expect(dlg).toBeVisible();

  // 行0: 按角色 (默认项目经理) 100.00/h 自 2026-01-01.
  await dlg.getByPlaceholder('编码').nth(0).fill('R-MGR');
  await dlg.getByPlaceholder('每小时费率').nth(0).fill('100.00');
  await dlg.locator('input[type="date"]').nth(0).fill('2026-01-01');

  // 行1: 按人员 admin 150.00/h 自 2026-09-01 (人员费率优先于角色费率).
  await dlg.getByRole('button', { name: '添加费率行' }).click();
  await dlg.getByPlaceholder('编码').nth(1).fill('P-ADMIN');
  await dlg.locator('.ant-select').nth(3).click(); // 行1 范围下拉 (0=币种,1/3=行0/行1范围)
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
    .filter({ hasText: '按人员' }).first().click();
  await dlg.locator('.ant-select').nth(4).click(); // 行1 人员下拉
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
    .filter({ hasText: 'admin' }).first().click();
  await page.keyboard.press('Escape');
  await dlg.getByPlaceholder('每小时费率').nth(1).fill('150.00');
  await dlg.locator('input[type="date"]').nth(2).fill('2026-09-01');

  const created = page.waitForResponse(r => r.url().includes('/api/pms/config/rate-table') && r.request().method() === 'POST');
  await dlg.getByRole('button', { name: /^保\s*存$/ }).click();
  expect((await (await created).json()).code).toBe(200);
  await expect(dlg).toBeHidden();

  // 同一编码只保留最新草稿版本, 历史退役版本仍在列表 -> 按状态定位当前草稿行.
  const draftRow = page.locator('table tbody tr').filter({ hasText: 'RATE-CNY' }).filter({ hasText: '草稿' });
  await expect(draftRow).toBeVisible();
  await expect(draftRow).toContainText('草稿');

  // 2. 发布费率表.
  await draftRow.getByRole('button', { name: '发布', exact: true }).click();
  const publish = modal(page, '发布费率表');
  await expect(publish).toBeVisible();
  await publish.locator('textarea').fill('E2E 启用费率');
  const publishedResp = page.waitForResponse(r => r.url().includes('/publish') && r.request().method() === 'POST');
  await publish.getByRole('button', { name: /^保\s*存$/ }).click();
  expect((await (await publishedResp).json()).code).toBe(200);
  await expect(publish).toBeHidden();
  await expect(page.locator('table tbody tr').filter({ hasText: 'RATE-CNY' }).filter({ hasText: '已发布' })).toHaveCount(1);
  await shot(page, 'h07-rate-table-published.png');

  // 3. 找一个有 admin 已批准工时 (不早于人员费率生效日) 的项目, 校验人工成本面板.
  const projects = (await api(page, 'GET', '/api/pms/projects?page=1&size=100')).rows;
  let target = null;
  for (const p of projects) {
    const fin = await api(page, 'GET', `/api/pms/projects/${p.project_id}/finance`);
    const hit = (fin.time_entries || []).some(t => t.status === 'approved' && t.user_id === 1 && t.work_date >= '2026-09-01');
    if (hit) { target = p; break; }
  }
  expect(target, '存在 admin 于人员费率生效后的已批准工时').toBeTruthy();

  await page.goto(`/pms/project?id=${target.project_id}`);
  await expect(drawer(page).getByRole('tab', { name: '项目概况', exact: true })).toBeVisible();
  await drawer(page).getByRole('tab', { name: '项目费用', exact: true }).click();
  await page.waitForLoadState('networkidle');
  await expect(page.getByText('人工成本 (费率折算)')).toBeVisible();
  await expect(page.getByText(/已覆盖 \d+\.\d{2} CNY/)).toBeVisible();
  await expect(page.getByRole('cell', { name: /P-ADMIN \(人员\)/ })).toBeVisible();
  await shot(page, 'h07-labor-cost-panel.png');
});
