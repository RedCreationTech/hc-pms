const { test, expect } = require('@playwright/test');
const path = require('node:path');

// H19 切片: 平台能力页新增「运行就绪核对」只读面板 (免迁移, 实时连接探测 + 迁移对账 + 配置在位).
// 真实浏览器打开 /pms/platform -> 面板显示 "就绪 / 数据库连通 / 探测耗时 N ms (阈值 1000 ms) /
//   迁移 84/84 已应用 / 关键配置齐备 / 支持库 sqlite / mysql";
//   真实 HTTP GET /api/pms/platform/readiness 回显 ready=true, connected=true, pending-count=0,
//   read-only=true, 且响应体不含连接串 (配置不回显取值).
const output = path.resolve(__dirname, '../../reports/h19ready');

async function login(page, user = 'admin', password = 'admin123') {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

async function api(page, url) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method: 'GET', headers: { Authorization: `Bearer ${token}` } });
  const body = await response.json();
  expect(body.code, `GET ${url}: ${body.msg}`).toBe(200);
  return body.data;
}

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

test.describe('H19 运行就绪核对面板浏览器验收', () => {
  test.setTimeout(120000);

  test('就绪面板展示实时连接/迁移对账/配置在位, 且与真实HTTP回显一致', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    await page.goto('/pms/platform');
    await expect(page.getByRole('heading', { name: '平台能力', exact: true })).toBeVisible();
    const card = page.locator('.ant-card').filter({ has: page.getByText('运行就绪核对', { exact: true }) }).first();
    await expect(card).toBeVisible();
    await expect(card).toContainText('就绪判定: 就绪');
    await expect(card).toContainText('数据库连通');
    await expect(card).toContainText(/探测耗时 \d+ ms \(阈值 1000 ms\)/);
    await expect(card).toContainText(/迁移 \d+\/\d+ 已应用/);
    await expect(card).toContainText('关键配置齐备');
    await expect(card).toContainText('支持库 sqlite / mysql');
    await card.scrollIntoViewIfNeeded();
    await page.waitForLoadState('networkidle');
    await page.screenshot({ path: path.join(output, 'readiness-1-page.png'), animations: 'disabled' });
    await card.screenshot({ path: path.join(output, 'readiness-2-card.png'), animations: 'disabled' });

    const data = await api(page, '/api/pms/platform/readiness');
    expect(data.ready, 'ready').toBe(true);
    expect(data.database.connected, 'connected').toBe(true);
    expect(data['pending-count'], 'pending-count').toBe(0);
    expect(data.migrations.known, 'known').toBe(data.migrations.applied);
    expect(data['read-only'], 'read-only').toBe(true);
    expect(data.supports.sort(), 'supports').toEqual(['mysql', 'sqlite']);
    expect(data.config.every(c => c.value_exposed === false), '配置不回显取值').toBe(true);
    const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
    const raw = await (await page.request.fetch('/api/pms/platform/readiness', {
      method: 'GET', headers: { Authorization: `Bearer ${token}` },
    })).text();
    expect(raw.includes('jdbc:'), '响应体不得出现连接串').toBe(false);

    expect(errors, `未捕获的浏览器JS错误: ${errors.join('; ')}`).toEqual([]);
  });
});