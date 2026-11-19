const { test, expect } = require('playwright/test');
const path = require('node:path');

// C11 本地通知管道: 扫描把逾期/待确认事件投递为本人通知 (去重), 页面可标为已读与已处理,
// 投递失败可重试且不产生重复通知. 精确的撤权与跨项目隔离由 pms_notify_test 断言.
const output = path.resolve(__dirname, '../../reports/c11notify');

async function login(page) {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
}

async function api(page, method, url, data) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  // 全量进度扫描 (58 个执行中项目) 需要较长时间, 单请求放宽到 180s.
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data, timeout: 180000 });
  const body = await response.json();
  expect(body.code, `${method} ${url}: ${body.msg}`).toBe(200);
  return body.data;
}

async function shot(page, file) {
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => document.querySelectorAll('.ant-message').forEach(node => node.remove()));
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe('C11 本地通知管道', () => {
  test.setTimeout(180000);
  test.use({ viewport: { width: 1600, height: 1000 } });

  test('扫描投递 -> 收件箱 -> 已读/已处理 -> 失败重试不重复', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await login(page);

    const first = await api(page, 'POST', '/api/pms/scan', {});
    const again = await api(page, 'POST', '/api/pms/scan', {});
    expect(again.notifications.created).toBe(0);
    const inbox = await api(page, 'GET', '/api/pms/notifications');
    expect(inbox.channel).toBe('local');
    expect(inbox.total).toBeGreaterThanOrEqual(first.notifications.total);
    expect(inbox.total).toBe(new Set(inbox.rows.map(row => row.code)).size);

    await page.goto('/pms/notifications');
    await expect(page.getByText('我的通知', { exact: true }).first()).toBeVisible();
    await expect(page.getByText(`未读 ${inbox.summary.unread}`)).toBeVisible();
    await shot(page, 'c11-notify-inbox.png');

    const target = inbox.rows.find(row => row.status === 'unread');
    const row = page.locator('tr', { hasText: target.title }).first();
    await row.getByRole('button', { name: '标为已读' }).click();
    await expect(page.getByText('已读 1')).toBeVisible();
    await row.getByRole('button', { name: '已处理' }).click();
    await expect(page.getByText('已处理 1')).toBeVisible();
    await shot(page, 'c11-notify-handled.png');

    // 投递失败登记与重试: 条数不变, 失败数归零.
    const before = await api(page, 'GET', '/api/pms/notifications');
    const victim = before.rows.find(item => item.status === 'unread');
    await api(page, 'POST', `/api/pms/notifications/${victim.id}/delivery-failed`);
    const failed = await api(page, 'GET', '/api/pms/notifications');
    expect(failed.summary.failed).toBeGreaterThan(0);
    expect(failed.total).toBe(before.total);
    const retried = await api(page, 'POST', '/api/pms/notifications/retry');
    expect(retried.retried).toBeGreaterThan(0);
    const after = await api(page, 'GET', '/api/pms/notifications');
    expect(after.summary.failed).toBe(0);
    expect(after.total).toBe(before.total);

    expect(errors).toEqual([]);
  });
});