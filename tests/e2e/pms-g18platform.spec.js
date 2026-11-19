const { test, expect } = require('playwright/test');
const path = require('node:path');

// G18 平台能力与兼容基线: 页面展示的 JDK / 数据库 / 浏览器基线与指标口径必须与后端只读核对同源,
// 未上报 User-Agent 时客户端判定为未上报 (不能当作通过). 精确解析规则由 pms_platform_test 断言.
const output = path.resolve(__dirname, '../../reports/g18platform');

async function login(page) {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

async function capabilities(page, userAgent) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.get('/api/pms/platform/capabilities', {
    headers: userAgent ? { Authorization: `Bearer ${token}`, 'User-Agent': userAgent } : { Authorization: `Bearer ${token}` },
  });
  const body = await response.json();
  expect(body.code, body.msg).toBe(200);
  return body.data;
}

test.describe('G18 平台能力与兼容基线', () => {
  test.setTimeout(180000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('平台能力页与后端只读核对一致, 客户端判定随真实 UA', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);
    const data = await capabilities(page);

    expect(data.database.supported).toBe(true);
    expect(data.databases.filter(item => item.current)).toHaveLength(1);
    expect(data.java.required).toBeGreaterThanOrEqual(21);
    expect(data.client.family).toBe('chrome');
    expect(data.client.supported).toBe(true);
    const counted = data.metrics.definitions.reduce((sum, item) => sum + item.metric_count, 0);
    expect(counted).toBe(data.metrics.published);

    await page.goto('/pms/platform');
    await expect(page.getByText('平台能力', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('运行环境', { exact: true })).toBeVisible();
    await expect(page.getByText(new RegExp(`${data.java.major}, 基线 ${data.java.required}`))).toBeVisible();
    await expect(page.getByText(/SQLite \/ 迁移目录 migrations-sqlite/)).toBeVisible();
    await page.waitForLoadState('networkidle');
    await page.screenshot({ path: path.join(output, 'pms-g18-capabilities.png'), fullPage: true, animations: 'disabled' });

    await page.getByRole('tab', { name: '浏览器' }).click();
    await expect(page.getByText('Chrome / Edge (Chromium)', { exact: true })).toBeVisible();
    await page.getByRole('tab', { name: '指标口径' }).click();
    await expect(page.getByText(`${data.metrics.published} 个.`, { exact: false })).toBeVisible();
    await page.waitForLoadState('networkidle');
    await page.screenshot({ path: path.join(output, 'pms-g18-matrix.png'), fullPage: true, animations: 'disabled' });

    // 未上报 User-Agent 的请求不能被判定为兼容.
    const unknown = await capabilities(page, 'curl/8.4.0');
    expect(unknown.client.reported).toBe(false);
    expect(unknown.client.supported).toBeNull();
    const legacy = await capabilities(page, 'Mozilla/5.0 (X11; Linux) Firefox/60.0');
    expect(legacy.client.family).toBe('firefox');
    expect(legacy.client.supported).toBe(false);

    expect(errors).toEqual([]);
  });
});