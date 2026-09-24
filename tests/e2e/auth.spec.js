const { test, expect } = require('playwright/test');
const { login, logout } = require('./auth-helper');

test.describe('认证流程', () => {
  test('使用默认管理员账号登录并登出', async ({ page }) => {
    await login(page);

    // 验证进入首页
    await expect(page).toHaveURL('/');
    await expect(page.getByText('红创PMS').nth(1)).toBeVisible();

    // 左侧菜单应包含系统管理
    await expect(page.getByText('系统管理').first()).toBeVisible();

    await logout(page);

    // 验证回到登录页
    await expect(page.getByPlaceholder('用户名')).toBeVisible();
    await expect(page.getByRole('button', { name: /登\s*录/ })).toBeVisible();
  });
});
