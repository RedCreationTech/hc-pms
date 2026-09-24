const { test, expect } = require('@playwright/test');
const path = require('node:path');

// S1 安全与权限底线: 登录页按参数隐藏验证码, 统一错误提示与 5 次锁定 + 解锁,
// 普通角色只看到办公自助菜单且系统接口 403, 管理员强退/停用后被撤销会话回到登录页.
const output = path.resolve(__dirname, '../../reports/s1-security');
const serial = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;

async function fillLogin(page, user, password) {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
}

async function login(page, user = 'admin', password = 'admin123') {
  await fillLogin(page, user, password);
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ruoyi_token'))).toBeTruthy();
  await page.getByText('红创PMS').nth(1).waitFor();
}

async function api(page, method, url, data) {
  const token = await page.evaluate(() => localStorage.getItem('ruoyi_token'));
  const response = await page.request.fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, data });
  return { status: response.status(), body: await response.json().catch(() => ({})) };
}

async function shot(page, file) {
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
}

test.describe.configure({ mode: 'serial' });

test('登录页不显示验证码, 错误提示统一, 连续失败锁定后可在登录日志解锁', async ({ page, browser }) => {
  const admin = await browser.newPage();
  await login(admin);
  const user = `s1lock${serial()}`;
  const created = await api(admin, 'POST', '/api/system/user', { user_name: user, nick_name: '锁定测试', password: 'Pass-1234', roles: [2] });
  expect(created.body.code).toBe(200);

  await page.goto('/');
  await page.getByPlaceholder('用户名').waitFor();
  await expect(page.getByPlaceholder('验证码')).toHaveCount(0);
  await shot(page, 's1-1-login-no-captcha.png');

  await fillLogin(page, 'no-such-user-' + serial(), 'whatever1');
  await expect(page.getByText('用户名或密码错误').first()).toBeVisible();
  for (let i = 0; i < 5; i += 1) {
    const res = await page.request.post('/api/auth/login', { data: { username: user, password: 'wrong-pass' } });
    expect((await res.json()).code).not.toBe(200);
  }
  await fillLogin(page, user, 'Pass-1234');
  await expect(page.getByText(/账户锁定10分钟/).first()).toBeVisible();
  await expect(page.getByRole('button', { name: /登\s*录/ })).toBeEnabled();
  await shot(page, 's1-2-account-locked.png');

  await admin.goto('/monitor/logininfor');
  const row = admin.locator('tbody tr').filter({ hasText: user }).first();
  await row.waitFor();
  await row.locator('input[type=checkbox]').check();
  await admin.getByRole('button', { name: '解锁' }).click();
  await expect(admin.getByText(`用户 ${user} 解锁成功`)).toBeVisible();
  await shot(admin, 's1-3-unlock-from-login-log.png');

  await login(page, user, 'Pass-1234');
  await admin.close();
});

test('普通角色只看到办公自助菜单, 系统接口按权限拒绝', async ({ page, browser }) => {
  const admin = await browser.newPage();
  await login(admin);
  const user = `s1emp${serial()}`;
  expect((await api(admin, 'POST', '/api/system/user', { user_name: user, nick_name: '普通员工', password: 'Pass-1234', roles: [2] })).body.code).toBe(200);
  await admin.close();

  await login(page, user, 'Pass-1234');
  const sider = page.locator('.ant-layout-sider');
  await expect(sider.getByText('我的待办').first()).toBeVisible();
  await expect(sider.getByText('系统管理')).toHaveCount(0);
  const info = await api(page, 'GET', '/api/auth/getInfo');
  expect(info.body.data.permissions).toContain('bpm:instance:start');
  expect(info.body.data.permissions).not.toContain('system:user:list');
  const users = await api(page, 'GET', '/api/system/user?page=1&size=10');
  expect(users.status).toBe(403);
  const options = await api(page, 'GET', '/api/system/user/options');
  expect(options.body.code).toBe(200);
  expect(Object.keys(options.body.data[0]).sort()).toEqual(['dept_id', 'nick_name', 'user_id', 'user_name']);
  await shot(page, 's1-4-employee-menus.png');
});

test('管理员强退或停用后, 被撤销的会话在下一次请求回到登录页', async ({ page, browser }) => {
  const admin = await browser.newPage();
  await login(admin);
  const user = `s1kick${serial()}`;
  expect((await api(admin, 'POST', '/api/system/user', { user_name: user, nick_name: '强退测试', password: 'Pass-1234', roles: [2] })).body.code).toBe(200);

  await login(page, user, 'Pass-1234');
  await admin.goto('/monitor/online');
  const row = admin.locator('tbody tr').filter({ hasText: user }).first();
  await row.waitFor();
  const online = await api(admin, 'GET', `/api/system/online?user_name=${user}`);
  const rows = online.body.data.rows;
  expect(rows[0]['token-id']).toBeTruthy();
  expect(rows[0].token).toBeUndefined();
  await shot(admin, 's1-5-online-session-id-only.png');
  await row.getByRole('button', { name: '强退' }).click();
  await admin.getByRole('button', { name: /确\s*定|OK/ }).click().catch(() => {});

  await page.goto('/office/bpm/todo');
  await expect(page.getByRole('button', { name: /登\s*录/ })).toBeVisible();
  await shot(page, 's1-6-kicked-back-to-login.png');

  // 停用: 再次登录后停用, 旧令牌立即失效
  await login(page, user, 'Pass-1234');
  const list = await api(admin, 'GET', `/api/system/user?user_name=${user}&page=1&size=10`);
  const id = list.body.data.rows[0].user_id;
  expect((await api(admin, 'PUT', `/api/system/user/${id}/status/1`)).body.code).toBe(200);
  const after = await api(page, 'GET', '/api/auth/getInfo');
  expect(after.status).toBe(401);
  await admin.close();
});
